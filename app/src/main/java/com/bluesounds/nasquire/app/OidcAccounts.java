package com.bluesounds.nasquire.app;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 최초 연결 허용값은 관리자가 지정하며 실제 issuer/sub 연결은 덮어쓰지 않는다. */
@Service
class OidcAccounts {
    private final JdbcTemplate db;
    private final Storage storage;
    private final OidcSettings settings;
    private final TransactionTemplate tx;
    OidcAccounts(JdbcTemplate db, Storage storage, OidcSettings settings, PlatformTransactionManager manager) {
        this.db = db; this.storage = storage; this.settings = settings; tx = new TransactionTemplate(manager);
    }
    record Deferred(int id, long generation, long revision, String role) implements java.io.Serializable { }
    record Login(Storage.Account account, Deferred deferred) { }
    Storage.Account complete(Deferred deferred) {
        synchronized (settings) {
            if (settings.revision() != deferred.revision()) throw new IllegalArgumentException("OIDC 시도 만료");
            return tx.execute(status -> {
                db.queryForObject("SELECT id FROM account_lock WHERE id=1 FOR UPDATE", Integer.class);
                var a = storage.account(deferred.id());
                if (a == null || !a.active() || a.generation() != deferred.generation()) throw new IllegalArgumentException("OIDC 시도 만료");
                sync(a, deferred.role()); return storage.account(a.id());
            });
        }
    }
    private void sync(Storage.Account a, String role) {
        if (role == null || role.equals(a.role())) return;
        if (a.role().equals("ADMIN") && db.queryForObject("SELECT COUNT(*) FROM administrator WHERE active=TRUE AND deleted=FALSE AND role='ADMIN'", Integer.class) <= 1) throw new IllegalArgumentException("OIDC 인증 실패");
        db.update("UPDATE administrator SET role=?,generation=generation+1 WHERE id=?", role, a.id());
        storage.audit("user:" + a.id(), "oidc-role-sync", 200);
    }
    Login login(JWTClaimsSet claims, long expectedRevision) throws Exception {
        synchronized (settings) {
            if (settings.revision() != expectedRevision) throw new IllegalArgumentException("OIDC 시도 만료");
            var p = settings.snapshot(); String issuer = claims.getIssuer(), sub = claims.getSubject();
            String match = p.getProperty("linkBy").equals("email") ? Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")) ? claims.getStringClaim("email") : null : sub;
            Object groups = p.getProperty("groupClaim").isBlank() ? null : claims.getClaim(p.getProperty("groupClaim"));
            return tx.execute(status -> {
                db.queryForObject("SELECT id FROM account_lock WHERE id=1 FOR UPDATE", Integer.class);
                var ids = db.queryForList("SELECT user_id FROM oidc_links WHERE issuer=? AND subject=?", Integer.class, issuer, sub);
                boolean created = false; int id;
                if (!ids.isEmpty()) id = ids.getFirst();
                else {
                    if (match == null) throw new IllegalArgumentException("OIDC 인증 실패");
                    var candidates = db.queryForList("SELECT id FROM administrator WHERE deleted=FALSE AND oidc_issuer=? AND oidc_match=? AND oidc_match_by=?", Integer.class, issuer, match, p.getProperty("linkBy"));
                    if (candidates.size() > 1) throw new IllegalArgumentException("OIDC 인증 실패");
                    if (candidates.size() == 1) {
                        id = candidates.getFirst();
                        if (db.queryForObject("SELECT COUNT(*) FROM oidc_links WHERE user_id=?", Integer.class, id) != 0) throw new IllegalArgumentException("OIDC 인증 실패");
                    } else {
                        if (!"true".equals(p.getProperty("autoSignup"))) throw new IllegalArgumentException("OIDC 인증 실패");
                        // 허용값이 이미 다른 sub와 연결되었으면 자동 가입으로 우회하지 않는다.
                        if (db.queryForObject("SELECT COUNT(*) FROM administrator WHERE oidc_issuer=? AND oidc_match=? AND oidc_match_by=?", Integer.class, issuer, match, p.getProperty("linkBy")) > 0) throw new IllegalArgumentException("OIDC 인증 실패");
                        id = db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM administrator", Integer.class);
                        db.update("INSERT INTO administrator(id,username,password_hash,role,active) VALUES (?,?,?,'USER',TRUE)", id, "oidc-" + UUID.randomUUID(), ""); created = true;
                    }
                    var account = storage.account(id); if (account == null || !account.active()) throw new IllegalArgumentException("OIDC 인증 실패");
                    db.update("INSERT INTO oidc_links(user_id,issuer,subject) VALUES (?,?,?)", id, issuer, sub);
                }
                var a = storage.account(id); if (a == null || !a.active()) throw new IllegalArgumentException("OIDC 인증 실패");
                String role = !created && !p.getProperty("groupClaim").isBlank() && !p.getProperty("adminGroup").isBlank()
                        ? groups instanceof List<?> list && list.contains(p.getProperty("adminGroup")) ? "ADMIN" : "USER" : null;
                boolean totp = db.queryForObject("SELECT totp_secret IS NOT NULL FROM administrator WHERE id=?", Boolean.class, id);
                Deferred deferred = totp ? new Deferred(id, a.generation(), expectedRevision, role) : null;
                if (!totp) sync(a, role);
                storage.audit("user:" + id, "oidc-identity", 200); return new Login(storage.account(id), deferred);
            });
        }
    }
}

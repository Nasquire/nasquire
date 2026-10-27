package com.bluesounds.nasquire.app;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 계정 변경은 공통 DB 행 잠금 아래 처리하여 마지막 활성 관리자를 보존한다. */
@RestController
@RequestMapping("/api/users")
class UsersController {
    record Input(String username, String password, String role, Boolean active, List<String> sources) { }
    record OidcInput(String match) { }
    private final OidcSettings oidc;
    private final JdbcTemplate db;
    private final Storage storage;
    private final TransactionTemplate transaction;
    private final BCryptPasswordEncoder passwords;
    UsersController(JdbcTemplate db, Storage storage, PlatformTransactionManager manager, BCryptPasswordEncoder passwords, OidcSettings oidc) {
        this.oidc = oidc;
        this.db = db; this.storage = storage; this.transaction = new TransactionTemplate(manager); this.passwords = passwords;
    }
    @GetMapping List<Map<String, Object>> list() {
        var users = db.query("SELECT id,username,role,active,totp_secret IS NOT NULL AS totp FROM administrator WHERE deleted=FALSE ORDER BY id", (r,n) -> {
            Map<String,Object> user = new LinkedHashMap<>();
            user.put("id", r.getInt("id")); user.put("username", r.getString("username")); user.put("role", r.getString("role"));
            user.put("active", r.getBoolean("active")); user.put("totpEnabled", r.getBoolean("totp")); return user;
        });
        for (var user : users) user.put("sources", db.queryForList("SELECT source_id FROM account_sources WHERE user_id=?", String.class, user.get("id")));
        for (var user : users) {
            user.put("oidcMatch", db.queryForObject("SELECT COALESCE(oidc_match,'') FROM administrator WHERE id=?", String.class, user.get("id")));
            var links = db.query("SELECT issuer,subject FROM oidc_links WHERE user_id=?", (r,n) -> Map.of("issuer",r.getString(1),"subject",r.getString(2)), user.get("id"));
            user.put("oidcIdentity", links.isEmpty() ? Map.of() : links.getFirst());
            user.put("oidcLinked", db.queryForObject("SELECT COUNT(*) FROM oidc_links WHERE user_id=?", Integer.class, user.get("id")) > 0);
        }
        return users;
    }
    @PostMapping Map<String, Integer> create(@RequestBody Input input) {
        Storage.validateUsername(input.username); PasswordPolicy.validate(input.password);
        String role = role(input.role == null ? "USER" : input.role);
        return transaction.execute(status -> {
            lock();
            int id = db.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM administrator", Integer.class);
            db.update("INSERT INTO administrator(id,username,password_hash,role,active) VALUES (?,?,?,?,?)", id, input.username, passwords.encode(input.password), role, input.active == null || input.active);
            sources(id, input.sources == null ? List.of() : input.sources);
            storage.audit("admin", "user-create " + id, 200); return Map.of("id", id);
        });
    }
    @PutMapping("/{id}") void update(@PathVariable int id, @RequestBody Input input) {
        transaction.executeWithoutResult(status -> {
            lock(); var account = require(id);
            String role = input.role == null ? account.role() : role(input.role);
            boolean active = input.active == null ? account.active() : input.active;
            protect(account, role.equals("ADMIN") && active);
            if (input.sources != null) sources(id, input.sources);
            db.update("UPDATE administrator SET role=?,active=?,generation=generation+1 WHERE id=?", role, active, id);
            storage.audit("admin", "user-update " + id, 200);
        });
    }
    @PostMapping("/{id}/password") void password(@PathVariable int id, @RequestBody Input input) {
        PasswordPolicy.validate(input.password);
        transaction.executeWithoutResult(status -> {
            lock(); require(id);
            db.update("UPDATE administrator SET password_hash=?,generation=generation+1 WHERE id=?", passwords.encode(input.password), id);
            storage.audit("admin", "user-password-reset " + id, 200);
        });
    }
    @PostMapping("/{id}/totp-reset") void totp(@PathVariable int id) {
        transaction.executeWithoutResult(status -> {
            lock(); require(id);
            db.update("UPDATE administrator SET totp_secret=NULL,totp_step=-1,generation=generation+1 WHERE id=?", id);
            db.update("DELETE FROM recovery_codes WHERE user_id=?", id);
            storage.audit("admin", "user-totp-reset " + id, 200);
        });
    }
    @DeleteMapping("/{id}") void delete(@PathVariable int id) {
        transaction.executeWithoutResult(status -> {
            lock(); protect(require(id), false);
            // ID를 재사용하지 않도록 행을 보존하고 식별값만 익명화한다. 기존 자격 증명은 즉시 폐기한다.
            db.update("UPDATE administrator SET deleted=TRUE,active=FALSE,role='USER',username=?,password_hash='',totp_secret=NULL,totp_step=-1,oidc_match=NULL,oidc_match_by=NULL,oidc_issuer=NULL,generation=generation+1 WHERE id=?", "deleted-" + id + "-" + UUID.randomUUID(), id);
            db.update("DELETE FROM recovery_codes WHERE user_id=?", id);
            db.update("DELETE FROM account_sources WHERE user_id=?", id);
            db.update("UPDATE tokens SET revoked=TRUE WHERE owner_id=?", id);
            db.update("DELETE FROM oidc_links WHERE user_id=?", id);
            storage.audit("admin", "user-delete " + id, 200);
        });
    }
    @PutMapping("/{id}/oidc") void oidcMatch(@PathVariable int id, @RequestBody OidcInput input) {
        if (input.match == null || input.match.length() > 320 || input.match.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("연결 값 오류");
        var p = oidc.snapshot(); OidcSettings.url(p.getProperty("issuer"));
        transaction.executeWithoutResult(status -> {
            lock(); require(id);
            db.update("UPDATE administrator SET oidc_match=?,oidc_match_by=?,oidc_issuer=?,generation=generation+1 WHERE id=?", input.match.trim(), p.getProperty("linkBy"), p.getProperty("issuer"), id);
            storage.audit("admin", "oidc-match-update " + id, 200);
        });
    }
    @DeleteMapping("/{id}/oidc") void unlink(@PathVariable int id) {
        transaction.executeWithoutResult(status -> {
            lock(); require(id); db.update("DELETE FROM oidc_links WHERE user_id=?", id);
            db.update("UPDATE administrator SET oidc_match=NULL,oidc_match_by=NULL,oidc_issuer=NULL,generation=generation+1 WHERE id=?", id);
            storage.audit("admin", "oidc-unlink " + id, 200);
        });
    }
    private void lock() { db.queryForObject("SELECT id FROM account_lock WHERE id=1 FOR UPDATE", Integer.class); }
    private Storage.Account require(int id) { var a = storage.account(id); if (a == null) throw new NoSuchElementException(); return a; }
    private void protect(Storage.Account a, boolean remainsAdmin) {
        if (a.active() && a.role().equals("ADMIN") && !remainsAdmin && db.queryForObject("SELECT COUNT(*) FROM administrator WHERE role='ADMIN' AND active=TRUE", Integer.class) <= 1)
            throw new IllegalStateException("마지막 활성 관리자는 유지해야 합니다");
    }
    private String role(String value) { if (!Set.of("ADMIN", "USER").contains(value)) throw new IllegalArgumentException("역할 오류"); return value; }
    private void sources(int id, List<String> sources) {
        if (sources.size() > 100) throw new IllegalArgumentException("소스 상한 초과");
        sources.forEach(storage::source);
        db.update("DELETE FROM account_sources WHERE user_id=?", id);
        for (String source : new HashSet<>(sources)) db.update("INSERT INTO account_sources(user_id,source_id) VALUES (?,?)", id, source);
    }
}

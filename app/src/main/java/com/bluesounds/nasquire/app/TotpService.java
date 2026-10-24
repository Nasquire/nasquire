package com.bluesounds.nasquire.app;

import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
class TotpService {
    private final JdbcTemplate db;
    private final EmbeddingSettings secrets;
    private final TransactionTemplate transaction;
    TotpService(JdbcTemplate db, EmbeddingSettings secrets, PlatformTransactionManager manager) {
        this.db = db; this.secrets = secrets; transaction = new TransactionTemplate(manager);
    }
    boolean enabled() { return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM administrator WHERE totp_secret IS NOT NULL", Boolean.class)); }
    synchronized List<String> enable(String encrypted, String code) throws Exception {
        if (enabled()) throw new IllegalArgumentException("이미 활성화되었습니다");
        long step = Totp.match(secrets.decrypt(encrypted), code, System.currentTimeMillis(), -1);
        if (step < 0) throw new IllegalArgumentException("코드 오류");
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) { byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes); codes.add(HexFormat.of().formatHex(bytes)); }
        transaction.executeWithoutResult(status -> {
            db.update("UPDATE administrator SET totp_secret=?,totp_step=? WHERE id=1", encrypted, step);
            db.update("DELETE FROM recovery_codes");
            for (String value : codes) db.update("INSERT INTO recovery_codes VALUES (?)", SecurityConfiguration.hash(value));
        });
        return codes;
    }
    synchronized boolean verify(String code, boolean recovery) throws Exception {
        if (!enabled() || code == null || code.length() > 100) return false;
        if (recovery && code.matches("[0-9a-f]{40}"))
            return db.update("DELETE FROM recovery_codes WHERE code_hash=?", SecurityConfiguration.hash(code)) == 1;
        var row = db.queryForMap("SELECT totp_secret,totp_step FROM administrator WHERE id=1");
        long last = ((Number)row.get("TOTP_STEP")).longValue();
        long step = Totp.match(secrets.decrypt((String)row.get("TOTP_SECRET")), code, System.currentTimeMillis(), last);
        return step >= 0 && db.update("UPDATE administrator SET totp_step=? WHERE id=1 AND totp_step=?", step, last) == 1;
    }
    synchronized boolean disable(String code) throws Exception {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            try {
                if (!verify(code, false)) return false;
                db.update("UPDATE administrator SET totp_secret=NULL,totp_step=-1 WHERE id=1");
                db.update("DELETE FROM recovery_codes"); return true;
            } catch (Exception e) { throw new IllegalStateException("인증 실패", e); }
        }));
    }
}

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
    private final Storage storage;
    private final EmbeddingSettings secrets;
    private final TransactionTemplate transaction;
    TotpService(JdbcTemplate db, Storage storage, EmbeddingSettings secrets, PlatformTransactionManager manager) {
        // Storage 주입은 administrator 스키마 생성 뒤에 시작 검사를 하도록 순서를 보장한다.
        this.db = db; this.storage = storage; this.secrets = secrets; transaction = new TransactionTemplate(manager);
        secrets.storedSecrets(this::anyEnabled);
        for (String encrypted : db.queryForList("SELECT totp_secret FROM administrator WHERE totp_secret IS NOT NULL", String.class)) {
            try { secrets.decrypt(EmbeddingSettings.TOTP, encrypted); }
            catch (EmbeddingSettings.KeyUnavailable e) { LOG.error("TOTP 비밀을 복호화할 수 없습니다: {}", e.getMessage()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TotpService.class);
    /** 복구 코드 입력 정규화: 앞뒤 공백 제거·소문자화·공백/하이픈 제거. 40자리 16진수가 아니면 null. */
    static String recoveryCode(String input) {
        if (input == null || input.length() > 100) return null;
        String value = input.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return value.matches("[0-9a-f]{40}") ? value : null;
    }
    boolean anyEnabled() { return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM administrator WHERE totp_secret IS NOT NULL", Boolean.class)); }
    boolean enabled(String username) { var a = storage.account(username); return a != null && enabled(a.id()); }
    boolean enabled() { return enabled(storage.currentId()); }
    private boolean enabled(int id) { return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM administrator WHERE id=? AND totp_secret IS NOT NULL", Boolean.class, id)); }
    synchronized List<String> enable(String encrypted, String code) throws Exception {
        if (enabled()) throw new IllegalArgumentException("이미 활성화되었습니다");
        long step = Totp.match(secrets.decrypt(EmbeddingSettings.TOTP, encrypted), code, System.currentTimeMillis(), -1);
        if (step < 0) throw new IllegalArgumentException("코드 오류");
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) { byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes); codes.add(HexFormat.of().formatHex(bytes)); }
        transaction.executeWithoutResult(status -> {
            db.update("UPDATE administrator SET totp_secret=?,totp_step=? WHERE id=?", encrypted, step, storage.currentId());
            db.update("DELETE FROM recovery_codes WHERE user_id=?", storage.currentId());
            for (String value : codes) db.update("INSERT INTO recovery_codes(code_hash,user_id) VALUES (?,?)", SecurityConfiguration.hash(value), storage.currentId());
        });
        return codes;
    }
    synchronized boolean verify(String code, boolean recovery) throws Exception {
        return verify(storage.currentId(), code, recovery);
    }
    synchronized boolean verify(int id, String code, boolean recovery) throws Exception {
        if (!enabled(id) || code == null || code.length() > 100) return false;
        String normalized = recovery ? recoveryCode(code) : null;
        if (normalized != null)
            return db.update("DELETE FROM recovery_codes WHERE code_hash=? AND user_id=?", SecurityConfiguration.hash(normalized), id) == 1;
        var row = db.queryForMap("SELECT totp_secret,totp_step FROM administrator WHERE id=?", id);
        long last = ((Number)row.get("TOTP_STEP")).longValue();
        String stored = (String)row.get("TOTP_SECRET"), secret = secrets.decrypt(EmbeddingSettings.TOTP, stored);
        long step = Totp.match(secret, code.strip(), System.currentTimeMillis(), last);
        if (step < 0) return false;
        // AAD 없는 기존 암호문은 승인 단계를 저장할 때 용도별 AAD 형식으로 다시 저장한다.
        String next = EmbeddingSettings.legacy(stored) ? secrets.encrypt(EmbeddingSettings.TOTP, secret) : stored;
        return db.update("UPDATE administrator SET totp_step=?,totp_secret=? WHERE id=? AND totp_step=?", step, next, id, last) == 1;
    }
    synchronized boolean disable(String code) throws Exception {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            try {
                if (!verify(code, false)) return false;
                db.update("UPDATE administrator SET totp_secret=NULL,totp_step=-1 WHERE id=?", storage.currentId());
                db.update("DELETE FROM recovery_codes WHERE user_id=?", storage.currentId()); return true;
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("인증 실패", e); }
        }));
    }
}

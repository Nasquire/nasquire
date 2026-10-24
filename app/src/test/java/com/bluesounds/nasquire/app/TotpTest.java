package com.bluesounds.nasquire.app;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    @TempDir Path data;
    @Test void concurrentFailuresStayWithinLimitAndLockIsNotShortened() throws Exception {
        var attempts = new LoginAttempts(); long now = 1_000_000_000L;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(32);
        var acquired = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var start = new java.util.concurrent.CyclicBarrier(32); var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) futures.add(pool.submit(() -> {
                start.await();
                var ticket = attempts.acquire("", "192.0.2.1", "admin", now);
                if (ticket == null) return null;
                acquired.incrementAndGet(); Thread.sleep(50); attempts.failed(ticket, now); return null;
            }));
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }
        assertTrue(acquired.get() >= 1 && acquired.get() <= LoginAttempts.LIMIT, String.valueOf(acquired.get()));
        // 진행 중 예약: 실패 3회 상태에서 동시에 2개만 허용하고 둘 다 실패하면 15분 잠금.
        var other = new LoginAttempts(); long at = now;
        for (int i = 0; i < 3; i++) { var t = other.acquire("", "192.0.2.2", "user", at); assertNotNull(t); other.failed(t, at); at += 1000L << i; }
        var first = other.acquire("", "192.0.2.2", "user", at); var second = other.acquire("", "192.0.2.2", "user", at);
        assertNotNull(first); assertNotNull(second); assertNull(other.acquire("", "192.0.2.2", "user", at));
        other.failed(first, at); other.failed(second, at);
        assertNull(other.acquire("", "192.0.2.2", "user", at + LoginAttempts.LOCK_MILLIS - 1));
        // 잠금 뒤에는 한 번에 하나만, 실패하면 횟수를 되돌리지 않고 다시 15분 잠근다(1초로 줄이지 않음).
        long open = at + LoginAttempts.LOCK_MILLIS;
        var retry = other.acquire("", "192.0.2.2", "user", open); assertNotNull(retry);
        assertNull(other.acquire("", "192.0.2.2", "user", open));
        other.failed(retry, open);
        assertNull(other.acquire("", "192.0.2.2", "user", open + 1000));
        assertNull(other.acquire("", "192.0.2.2", "user", open + LoginAttempts.LOCK_MILLIS - 1));
        // 등록/해제용 접두어 키는 로그인 키와 분리된다.
        assertNotNull(other.acquire(LoginAttempts.MANAGE, "192.0.2.3", "user2", open + 1000));
        var manage = new LoginAttempts();
        var bad = manage.acquire(LoginAttempts.MANAGE, "192.0.2.4", "admin", now); manage.failed(bad, now);
        assertNull(manage.acquire(LoginAttempts.MANAGE, "192.0.2.4", "admin", now));
        try (var login = manage.acquire("", "192.0.2.4", "admin", now)) { assertNotNull(login); }
        assertNotNull(manage.acquire("", "192.0.2.4", "admin", now));
    }
    @Test void recoveryCodeNormalization() {
        String code = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(code, TotpService.recoveryCode(code));
        assertEquals(code, TotpService.recoveryCode("  0123456789ABCDEF-01234567 89abcdef-01234567\t"));
        assertNull(TotpService.recoveryCode("0123456789abcdef0123456789abcdef0123456"));
        assertNull(TotpService.recoveryCode("123456")); assertNull(TotpService.recoveryCode(null));
    }
    @Test void pipedRecoveryPasswordStripsCarriageReturn() throws Exception {
        assertArrayEquals("new-password-123".toCharArray(), AdminRecovery.readLine(new java.io.ByteArrayInputStream("new-password-123\r\n".getBytes())));
        assertArrayEquals("new-password-123".toCharArray(), AdminRecovery.readLine(new java.io.ByteArrayInputStream("new-password-123".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> AdminRecovery.readLine(new java.io.ByteArrayInputStream("x".repeat(73).getBytes())));
    }
    @Test void purposeBoundCiphertextLegacyFormatAndMissingKey() throws Exception {
        try (var index = new com.bluesounds.nasquire.core.SearchIndex(data.resolve("index"))) {
            Path folder = Files.createDirectories(data.resolve("secrets"));
            var settings = new EmbeddingSettings(index, folder, new org.springframework.mock.env.MockEnvironment());
            String totp = settings.encrypt(EmbeddingSettings.TOTP, "secret-value");
            assertTrue(totp.startsWith("v2:")); assertFalse(EmbeddingSettings.legacy(totp));
            assertEquals("secret-value", settings.decrypt(EmbeddingSettings.TOTP, totp));
            // 다른 용도로는 같은 키의 암호문을 읽을 수 없다.
            assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> settings.decrypt(EmbeddingSettings.EMBEDDING, totp));
            // AAD 없는 기존 형식은 계속 읽는다.
            byte[] key = Files.readAllBytes(folder.resolve("embedding.key")), nonce = new byte[12];
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] body = cipher.doFinal("legacy-value".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String legacy = java.util.Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12 + body.length).put(nonce).put(body).array());
            assertTrue(EmbeddingSettings.legacy(legacy)); assertEquals("legacy-value", settings.decrypt(EmbeddingSettings.TOTP, legacy));
            // 키가 바뀌면 통제된 오류로 실패한다.
            Files.write(folder.resolve("embedding.key"), new byte[32]);
            assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> settings.decrypt(EmbeddingSettings.TOTP, totp));
            // 키 파일이 없고 DB 암호문이 있으면 새 키를 만들지 않는다.
            Files.delete(folder.resolve("embedding.key")); settings.storedSecrets(() -> true);
            assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> settings.encrypt(EmbeddingSettings.TOTP, "next"));
            assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> settings.decrypt(EmbeddingSettings.TOTP, totp));
            assertFalse(Files.exists(folder.resolve("embedding.key")));
            // 저장된 암호문이 없을 때만 새 키를 만든다.
            settings.storedSecrets(() -> false);
            assertTrue(settings.encrypt(EmbeddingSettings.TOTP, "next").startsWith("v2:")); assertTrue(Files.exists(folder.resolve("embedding.key")));
        }
    }
    @Test void rfc6238Sha1VectorsAndWindow() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        long[] seconds = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < seconds.length; i++) assertEquals(expected[i], Totp.code(secret, seconds[i]/30));
        long now = 1234567890000L, step = now/30000;
        for (long at = step-1; at <= step+1; at++) assertEquals(at, Totp.match(secret, Totp.code(secret, at), now, -1));
        assertEquals(-1, Totp.match(secret, Totp.code(secret, step-2), now, -1));
        assertEquals(-1, Totp.match(secret, Totp.code(secret, step+2), now, -1));
        assertEquals(-1, Totp.match(secret, Totp.code(secret, step), now, step));
    }
    @Test void offlineCommandResetsPasswordAndTotpWithoutStartingServer() throws Exception {
        String url = "jdbc:h2:file:" + data.resolve("metadata");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true)); var storage = new Storage(db);
            storage.setup("admin", "old-password-123");
            db.update("UPDATE administrator SET totp_secret='encrypted-placeholder',totp_step=123");
            db.update("INSERT INTO recovery_codes VALUES (?)", SecurityConfiguration.hash("recovery"));
        }
        var previous = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("new-password-123\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            NasquireApplication.main(new String[]{"--recover-admin", "--data=" + data});
        } finally { System.setIn(previous); }
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true)); var storage = new Storage(db);
            assertTrue(new BCryptPasswordEncoder().matches("new-password-123", storage.user("admin").getPassword()));
            assertNull(db.queryForObject("SELECT totp_secret FROM administrator", String.class));
            assertEquals(-1L, db.queryForObject("SELECT totp_step FROM administrator", Long.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
            assertTrue(storage.audit().toString().contains("admin-recovery"));
        }
        assertThrows(java.nio.file.NoSuchFileException.class, () -> AdminRecovery.run(new String[]{"--recover-admin", "--data=" + data.resolve("missing")}));
    }
}

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

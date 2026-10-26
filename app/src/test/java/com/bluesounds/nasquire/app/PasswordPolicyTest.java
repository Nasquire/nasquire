package com.bluesounds.nasquire.app;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @TempDir Path data;
    record Sample(String password, boolean valid) {}
    static Stream<Sample> samples() {
        return Stream.of(new Sample("", false), new Sample("abcde1!", false), new Sample("abcdefgh!", false),
            new Sample("abcdef12", false), new Sample("1234567!", false),
            new Sample("abcdef1 ", false), new Sample("abcdef1\t", false),
            new Sample("abcdef1。", false), new Sample("abcdef1\u007f", false),
            new Sample("a1!" + "x".repeat(70), false), new Sample("a1!" + "한".repeat(24), false),
            new Sample("abcdef1!", true), new Sample("ABCDEF1!", true),
            new Sample("a1!" + "x".repeat(69), true), new Sample("a1!" + "한".repeat(23), true));
    }
    @ParameterizedTest @MethodSource("samples")
    void setupAndOfflineRecoveryUseSamePolicy(Sample sample) throws Exception {
        String url = "jdbc:h2:file:" + data.resolve("metadata");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var storage = new Storage(db);
            if (sample.valid()) {
                storage.setup("admin", sample.password());
                assertTrue(new BCryptPasswordEncoder().matches(sample.password(), storage.user("admin").getPassword()));
                db.update("DELETE FROM administrator");
            } else {
                var error = assertThrows(IllegalArgumentException.class, () -> storage.setup("admin", sample.password()));
                assertEquals(PasswordPolicy.MESSAGE, error.getMessage()); assertFalse(storage.configured());
            }
            storage.setup("admin", "original1!");
            db.update("UPDATE administrator SET totp_secret='preserved',totp_step=123");
            db.update("INSERT INTO recovery_codes VALUES ('preserved')");
        }
        var previous = System.in;
        try {
            System.setIn(new ByteArrayInputStream((sample.password() + "\n").getBytes(StandardCharsets.UTF_8)));
            if (sample.valid()) AdminRecovery.run(new String[]{"--recover-admin", "--data=" + data});
            else {
                var error = assertThrows(IllegalArgumentException.class, () -> AdminRecovery.run(new String[]{"--recover-admin", "--data=" + data}));
                assertEquals(PasswordPolicy.MESSAGE, error.getMessage());
            }
        } finally { System.setIn(previous); }
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var storage = new Storage(db);
            assertTrue(new BCryptPasswordEncoder().matches(sample.valid() ? sample.password() : "original1!", storage.user("admin").getPassword()));
            assertEquals(sample.valid() ? null : "preserved", db.queryForObject("SELECT totp_secret FROM administrator", String.class));
            assertEquals(sample.valid() ? 0 : 1, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
            assertEquals(sample.valid() ? 1 : 0, storage.audit().size());
        }
    }
    @Test void nullAndAllAsciiPunctuation() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(null));
        for (char c = '!'; c <= '~'; c++) {
            if (c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') continue;
            PasswordPolicy.validate("abcdef1" + c);
        }
    }
}

package com.bluesounds.nasquire.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OAuthRateLimitTest {
    @Test void ipv6Uses64BitsAndFullTableEvictsOldestWithoutGlobalDenial() {
        var attempts = new LoginAttempts();
        for (int i = 0; i < 10; i++) assertTrue(attempts.allowOAuth("register", "2001:db8:1:2::" + Integer.toHexString(i), 1000));
        assertFalse(attempts.allowOAuth("register", "2001:db8:1:2:ffff::1", 1001));
        assertTrue(attempts.allowOAuth("register", "2001:db8:1:3::1", 1001));
        assertTrue(attempts.allowOAuth("token", "2001:db8:1:2::1", 1001));
        assertEquals(LoginAttempts.network("2001:db8:1:2::1"), LoginAttempts.network("2001:0db8:0001:0002:0:0:0:2"));
        for (int i = 0; i < 4096; i++) assertTrue(attempts.allowOAuth("register", "address-" + i, 1002));
        assertTrue(attempts.allowOAuth("register", "new-address", 1003));
        assertTrue(attempts.allowOAuth("register", "2001:db8:1:2::1", 1003));
    }
    @Test void auditIsOncePerNetworkAndKindPerMinuteAndCannotFloodOnEviction() {
        var attempts = new LoginAttempts();
        assertTrue(attempts.auditOAuth("rate-limit", "2001:db8::1", 1000));
        assertFalse(attempts.auditOAuth("rate-limit", "2001:db8::2", 2000));
        assertTrue(attempts.auditOAuth("register-evict", "2001:db8::2", 2000));
        assertTrue(attempts.auditOAuth("rate-limit", "2001:db8::2", 61000));
        for (int i = 0; i < 5000; i++) attempts.auditOAuth("rate-limit", "address-" + i, 62000);
        assertFalse(attempts.auditOAuth("rate-limit", "new", 62000));
        assertTrue(attempts.auditOAuth("rate-limit", "new", 122001));
    }
}

package com.bluesounds.nasquire.app;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityBoundaryTest {
    @Test void passwordAuthenticationNeverStoresAdminBeforeTotpSuccessHandler() {
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        request.getSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        new SecurityConfiguration.PasswordContextRepository(() -> true).saveContext(context, request, response);
        assertNull(request.getSession().getAttribute("SPRING_SECURITY_CONTEXT"));
        new SecurityConfiguration.PasswordContextRepository(() -> false).saveContext(context, request, response);
        assertNotNull(request.getSession().getAttribute("SPRING_SECURITY_CONTEXT"));
    }
    @Test void totpPendingSessionExpiresWithoutCreatingAnotherSession() {
        var request = new MockHttpServletRequest();
        assertNull(TotpController.pending(request)); assertNull(request.getSession(false));
        request.getSession().setAttribute("totp.pending", new TotpController.Pending("admin", System.currentTimeMillis()));
        assertNotNull(TotpController.pending(request));
        request.getSession().setAttribute("totp.pending", new TotpController.Pending("admin", System.currentTimeMillis() - 300001));
        assertNull(TotpController.pending(request)); assertNull(request.getSession().getAttribute("totp.pending"));
    }
    @Test void forwardedAddressesRequireTrustedConnectionAndWalkFromRight() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 192.0.2.11");
        assertEquals("192.0.2.10", new ClientAddresses("").address(request));
        assertEquals("192.0.2.11", new ClientAddresses("192.0.2.10").address(request));
        assertEquals("198.51.100.1", new ClientAddresses("192.0.2.10,192.0.2.11").address(request));
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "invalid.example.com");
        assertEquals("192.0.2.10", new ClientAddresses("192.0.2.10").address(request));
    }
    @Test void loginDelayLocksBothAccountAndIpAndResetsOnSuccess() {
        var attempts = new LoginAttempts(); long now = 0;
        for (int i = 0; i < 5; i++) {
            assertTrue(attempts.allowed("ip-one", "admin", now));
            attempts.failed("ip-one", "admin", now);
            assertFalse(attempts.allowed("ip-two", "admin", now));
            assertFalse(attempts.allowed("ip-one", "another", now));
            now += i == 4 ? 900000 : 1000L << i;
            assertFalse(attempts.allowed("ip-one", "admin", now - 1));
        }
        assertTrue(attempts.allowed("ip-one", "admin", now));
        attempts.failed("ip-one", "admin", now);
        attempts.succeeded("ip-one", "admin");
        assertTrue(attempts.allowed("ip-one", "admin", now));
        for (int i = 0; i < 10000; i++) attempts.failed("ip-" + i, "account-" + i, now);
        assertTrue(attempts.allowed("new-ip", "new-account", now));
    }
    @Test void multipartIsRejectedWithoutReadingAnyParameterOrPart() throws Exception {
        var request = new MockHttpServletRequest() {
            @Override public String getParameter(String name) { fail("인증 전 파라미터 파싱"); return null; }
            @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() { fail("인증 전 multipart 파싱"); return null; }
        };
        request.setMethod("POST"); request.setServletPath("/api/uploads/example"); request.setContentType("multipart/form-data; boundary=example");
        var response = new MockHttpServletResponse();
        new SecurityConfiguration.MultipartGuard(4096).doFilter(request, response, (req, res) -> fail("익명 본문 전달"));
        assertEquals(401, response.getStatus());
    }
    @Test void publicRateLimitEvictsLeastRecentlyUsedAddress() {
        var limiter = new ShareSecurity.PublicFilter(1, null);
        assertTrue(limiter.allow("old", 0));
        for (int i = 0; i < 4095; i++) assertTrue(limiter.allow("ip-" + i, 0));
        assertFalse(limiter.allow("ip-0", 0)); // 최근 접근은 보존한다.
        assertTrue(limiter.allow("new", 0));
        assertFalse(limiter.allow("ip-0", 0));
        assertTrue(limiter.allow("old", 0));
    }
}

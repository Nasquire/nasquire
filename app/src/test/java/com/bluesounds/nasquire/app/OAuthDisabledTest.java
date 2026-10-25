package com.bluesounds.nasquire.app;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuthDisabledTest {
    static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-oauth-off-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("nasquire.data", () -> TEMP.toString()); }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired Storage storage;
    @Autowired PlatformTransactionManager transactions;
    @Test void disabledEndpointsAndInvalidPublicUrls(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server", "/oauth/authorize", "/oauth/token", "/oauth/register", "/oauth/revoke")) {
            for (String method : List.of("GET", "POST")) assertEquals(404, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        for (String url : List.of("", "http://example.com", "https://example.com/path", "https://user@example.com", "https://example.com?query=1")) {
            var oauth = new OAuthService(db, storage, transactions, true, url, new LoginAttempts(), "");
            assertFalse(oauth.enabled()); assertFalse(oauth.disabledReason.isEmpty());
            assertEquals(404, assertThrows(OAuthService.Error.class, () -> oauth.register(Map.of())).status);
        }
        assertFalse(new OAuthService(db, storage, transactions, false, "https://example.com", new LoginAttempts(), "").enabled());
        assertTrue(new OAuthService(db, storage, transactions, true, "http://127.0.0.1:8101", new LoginAttempts(), "").enabled());
        assertTrue(output.getOut().contains("OAuth enabled without nasquire.trusted-proxies"));
    }
}

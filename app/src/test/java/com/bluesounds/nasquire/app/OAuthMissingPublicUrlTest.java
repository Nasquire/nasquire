package com.bluesounds.nasquire.app;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuthMissingPublicUrlTest {
    static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-oauth-no-url-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.toString()); r.add("nasquire.oauth.enabled", () -> "true");
    }
    @Value("${local.server.port}") int port;
    @Autowired OAuthService oauth;
    @Test void allEndpointsAreHiddenAndReasonIsAvailable() throws Exception {
        assertFalse(oauth.enabled()); assertTrue(oauth.view().get("disabledReason").toString().contains("HTTPS"));
        var client = HttpClient.newHttpClient();
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server", "/oauth/authorize", "/oauth/token", "/oauth/register", "/oauth/revoke")) {
            for (String method : List.of("GET", "POST")) assertEquals(404, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}

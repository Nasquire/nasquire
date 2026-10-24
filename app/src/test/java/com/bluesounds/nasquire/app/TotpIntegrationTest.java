package com.bluesounds.nasquire.app;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TotpIntegrationTest {
    private static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-integration-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString());
        r.add("nasquire.upload-max-bytes", () -> "4096");
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "50");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
    @Autowired LoginAttempts attempts;
    @Autowired DocumentService documents;
    @Autowired UploadService uploads;
    @Autowired DownloadService downloads;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String csrf;
    HttpResponse<String> call(String path, String method, Object body, String bearer, boolean useCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (useCsrf) request.header("X-CSRF-TOKEN", csrf);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (path.equals("/mcp")) request.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response, int status) { assertEquals(status, response.statusCode(), response.body()); return mapper.readTree(response.body()); }
    void auth() throws Exception { csrf = json(call("/api/auth", "GET", null, null, false), 200).get("csrfToken").asText(); }
    int login() throws Exception {
        auth();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login")).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=admin&password=example-password-123")).build();
        int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(); auth(); return status;
    }
    void resetAttempts() { attempts.succeeded("127.0.0.1", "admin"); }
    String sessionId() { return ((CookieManager)client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    @Test void enrollmentChallengeRecoveryAndDisable() throws Exception {
        auth();
        json(call("/api/setup", "POST", Map.of("username", "admin", "password", "example-password-123"), null, true), 200);
        assertEquals(200, login());
        assertFalse(json(call("/api/totp", "GET", null, null, false), 200).get("enabled").asBoolean());
        assertEquals(403, call("/api/totp/enroll", "POST", null, null, false).statusCode());
        var enrollment = json(call("/api/totp/enroll", "POST", null, null, true), 200);
        String secret = enrollment.get("secret").asText();
        assertTrue(enrollment.get("uri").asText().contains("digits=6&period=30"));
        assertEquals(401, call("/api/totp/enable", "POST", Map.of("code", "bad"), null, true).statusCode()); resetAttempts();
        long step = System.currentTimeMillis()/30000;
        var codes = json(call("/api/totp/enable", "POST", Map.of("code", Totp.code(secret, step)), null, true), 200).get("recoveryCodes");
        assertEquals(10, codes.size()); assertEquals(10, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
        assertNotEquals(secret, db.queryForObject("SELECT totp_secret FROM administrator", String.class));
        assertFalse(call("/api/totp", "GET", null, null, false).body().contains(secret));
        String recovery = codes.get(0).asText();
        assertEquals(40, recovery.length());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM recovery_codes WHERE code_hash=?", Integer.class, recovery));
        String token = json(call("/api/tokens", "POST", Map.of("name", "totp-test", "expiresAt", System.currentTimeMillis()+600000), null, true), 200).get("value").asText();
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode());
        assertEquals(202, login());
        var pending = json(call("/api/auth", "GET", null, null, false), 200);
        assertTrue(pending.get("totpRequired").asBoolean()); assertFalse(pending.get("authenticated").asBoolean());
        for (String path : List.of("/api/sources", "/api/tokens", "/api/embeddings", "/api/totp", "/api/search?query=test")) {
            int status = call(path, "GET", null, null, false).statusCode(); assertTrue(status == 401 || status == 403, path);
        }
        int denied = call("/api/totp/enroll", "POST", null, null, true).statusCode(); assertTrue(denied == 401 || denied == 403);
        assertEquals(200, call("/api/search?query=test", "GET", null, token, false).statusCode());
        var tools = json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"), token, false), 200);
        assertEquals(3, tools.at("/result/tools").size());
        assertEquals(401, call("/mcp", "POST", Map.of(), null, true).statusCode());
        assertEquals(403, call("/api/login/totp", "POST", Map.of("code", recovery), null, false).statusCode());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", "invalid"), null, true).statusCode());
        assertEquals(429, call("/api/login/totp", "POST", Map.of("code", recovery), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", Totp.code(secret, step)), null, true).statusCode()); resetAttempts();
        String before = sessionId();
        assertEquals(200, call("/api/login/totp", "POST", Map.of("code", Totp.code(secret, step+1)), null, true).statusCode());
        assertNotEquals(before, sessionId()); auth();
        assertTrue(json(call("/api/auth", "GET", null, null, false), 200).get("authenticated").asBoolean());
        assertEquals(200, call("/api/sources", "GET", null, null, false).statusCode());
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "wrong", "code", Totp.code(secret, step+1)), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "example-password-123", "code", recovery), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "example-password-123", "code", "bad"), null, true).statusCode()); resetAttempts();
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(202, login());
        assertEquals(200, call("/api/login/totp", "POST", Map.of("code", recovery), null, true).statusCode()); auth();
        assertEquals(9, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(202, login());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", recovery), null, true).statusCode()); resetAttempts();
        assertEquals(200, call("/api/login/totp", "POST", Map.of("code", codes.get(1).asText()), null, true).statusCode()); auth();
        // 시간 진행 없이 해제 성공 조건을 검증하도록 DB의 재생 기준만 이전 단계로 이동한다.
        db.update("UPDATE administrator SET totp_step=?", step-1);
        assertEquals(200, call("/api/totp/disable", "POST", Map.of("password", "example-password-123", "code", Totp.code(secret, step)), null, true).statusCode());
        assertFalse(json(call("/api/totp", "GET", null, null, false), 200).get("enabled").asBoolean());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
        String audit = call("/api/audit", "GET", null, null, false).body();
        assertTrue(audit.contains("login-recovery-code")); assertTrue(audit.contains("totp-disable"));
        assertFalse(audit.contains(secret)); for (var code : codes) assertFalse(audit.contains(code.asText()));
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(200, login());
    }
}

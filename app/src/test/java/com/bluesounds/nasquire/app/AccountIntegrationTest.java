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
class AccountIntegrationTest {
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
    String username = "admin", password = "example-password-123";
    int login() throws Exception {
        auth();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login")).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(); auth(); return status;
    }
    void resetAttempts() { attempts.reset("", "127.0.0.1", "admin"); attempts.reset(LoginAttempts.MANAGE, "127.0.0.1", "admin"); }
    int auditCount(String action, int status) { return db.queryForObject("SELECT COUNT(*) FROM audit WHERE action=? AND status=?", Integer.class, action, status); }
    String sessionId() { return ((CookieManager)client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    Map<String, String> change(String current, String next) { return Map.of("currentPassword", current, "newPassword", next, "confirmation", next); }
    @Test void accountChangesAndTokenDeletion() throws Exception {
        auth();
        assertEquals(401, call("/api/account/password", "PUT", change(password, "changed-password-123"), null, true).statusCode());
        assertEquals(200, call("/api/setup", "POST", Map.of("username", username, "password", password), null, true).statusCode());
        assertEquals(200, login());
        assertEquals(403, call("/api/account/password", "PUT", change(password, "changed-password-123"), null, false).statusCode());
        assertEquals(401, call("/api/account/password", "PUT", change("wrong", "changed-password-123"), null, true).statusCode());
        assertEquals(429, call("/api/account/password", "PUT", change(password, "changed-password-123"), null, true).statusCode());
        attempts.reset("account-change:", "127.0.0.1", "administrator");
        long base = System.currentTimeMillis() - 20000;
        for (long at : new long[]{base, base+1000, base+3000, base+7000, base+15000}) {
            try (var ticket = attempts.acquire("account-change:", "127.0.0.1", "administrator", at)) { assertNotNull(ticket); attempts.failed(ticket, at); }
        }
        assertEquals(429, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", "renamed"), null, true).statusCode());
        attempts.reset("account-change:", "127.0.0.1", "administrator");
        for (String invalid : List.of("short1!", "abcdefgh1", "abcdefgh!", "12345678!", "a1!" + "가".repeat(24))) {
            assertEquals(400, call("/api/account/password", "PUT", change(password, invalid), null, true).statusCode());
        }
        assertEquals(400, call("/api/account/password", "PUT", Map.of("currentPassword", password, "newPassword", "changed-password-123", "confirmation", "mismatch"), null, true).statusCode());
        for (String invalid : List.of("ab", "a".repeat(101), "has space")) {
            assertEquals(400, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", invalid), null, true).statusCode());
        }
        // 독립 로그인 세션과 변경 전 CSRF가 모두 무효화되는지 실제 HTTP로 확인한다.
        var other = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String origin = "http://127.0.0.1:" + port;
        var state = mapper.readTree(other.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(200, other.send(HttpRequest.newBuilder(URI.create(origin+"/api/login")).header("X-CSRF-TOKEN", state.get("csrfToken").asText()).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=admin&password="+password)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String before = sessionId(), previousCsrf = csrf;
        assertEquals(200, call("/api/account/password", "PUT", change(password, "changed-password-123"), null, true).statusCode());
        assertNotEquals(before, sessionId()); auth(); assertNotEquals(previousCsrf, csrf);
        assertEquals(401, other.send(HttpRequest.newBuilder(URI.create(origin+"/api/account")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(origin+"/api/account/username")).header("X-CSRF-TOKEN", previousCsrf).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode());
        assertEquals(401, login()); attempts.reset("", "127.0.0.1", username);
        password = "changed-password-123"; assertEquals(200, login());
        before = sessionId();
        assertEquals(200, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", "renamed"), null, true).statusCode());
        assertNotEquals(before, sessionId()); auth();
        assertEquals("renamed", json(call("/api/account", "GET", null, null, false), 200).get("username").asText());
        username = "renamed";
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(200, login());
        var enrollment = json(call("/api/totp/enroll", "POST", Map.of("password", password), null, true), 200);
        String secret = enrollment.get("secret").asText(); long step = System.currentTimeMillis()/30000;
        var recovery = json(call("/api/totp/enable", "POST", Map.of("code", Totp.code(secret, step)), null, true), 200).get("recoveryCodes").get(0).asText();
        assertEquals(401, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", "finaladmin"), null, true).statusCode());
        attempts.reset("account-change:", "127.0.0.1", "administrator");
        assertEquals(401, call("/api/account/password", "PUT", change(password, "final-password-123"), null, true).statusCode());
        attempts.reset("account-change:", "127.0.0.1", "administrator");
        assertEquals(401, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", "finaladmin", "code", recovery), null, true).statusCode());
        attempts.reset("account-change:", "127.0.0.1", "administrator");
        state = mapper.readTree(other.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(202, other.send(HttpRequest.newBuilder(URI.create(origin+"/api/login")).header("X-CSRF-TOKEN", state.get("csrfToken").asText()).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=renamed&password="+password)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, call("/api/account/username", "PUT", Map.of("currentPassword", password, "username", "finaladmin", "code", Totp.code(secret, step+1)), null, true).statusCode()); auth();
        state = mapper.readTree(other.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertFalse(state.get("totpRequired").asBoolean()); assertFalse(state.get("authenticated").asBoolean());
        assertEquals(401, other.send(HttpRequest.newBuilder(URI.create(origin+"/api/tokens/inactive")).header("X-CSRF-TOKEN", state.get("csrfToken").asText()).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // 활성 토큰 삭제는 거부하고 철회/만료 행만 지우며 감사와 티켓 거부는 유지한다.
        var sourceFolder = Files.createDirectories(TEMP.resolve("source")); Files.writeString(sourceFolder.resolve("note.txt"), "test");
        var source = json(call("/api/sources", "POST", Map.of("name", "test", "path", sourceFolder.toString()), null, true), 200).get("id").asText();
        var token = json(call("/api/tokens", "POST", Map.of("name", "deletion", "expiresAt", System.currentTimeMillis()+600000), null, true), 200);
        String id = token.get("id").asText(), value = token.get("value").asText();
        String url = downloads.issue(storage.identity(id), source+":note.txt", "").get("url").toString();
        assertEquals(403, call("/api/tokens/"+id+"/record", "DELETE", null, null, false).statusCode());
        assertEquals(409, call("/api/tokens/"+id+"/record", "DELETE", null, null, true).statusCode());
        assertEquals(200, call("/api/tokens/"+id, "DELETE", null, null, true).statusCode());
        assertEquals(403, call(url, "GET", null, null, false).statusCode());
        assertEquals(200, call("/api/tokens/"+id+"/record", "DELETE", null, null, true).statusCode());
        assertEquals(403, call(url, "GET", null, null, false).statusCode());
        assertEquals(401, call("/api/search?query=test", "GET", null, value, false).statusCode());
        assertNull(storage.identity(id)); assertTrue(storage.audit().stream().anyMatch(a -> a.get("ACTION").equals("token-delete " + id)));
        for (String name : List.of("expired", "revoked", "active")) {
            storage.addToken(name, name, SecurityConfiguration.hash(name), System.currentTimeMillis()+(name.equals("expired") ? -1000 : 600000), List.of());
        }
        storage.revoke("revoked");
        assertEquals(2, json(call("/api/tokens/inactive", "DELETE", null, null, true), 200).get("deleted").asInt());
        assertEquals(List.of("active"), storage.tokens().stream().map(Storage.Token::id).toList());
        assertTrue(storage.audit().stream().noneMatch(a -> a.toString().contains(value) || a.toString().contains(SecurityConfiguration.hash(value)) || a.toString().contains(password)));
    }
}

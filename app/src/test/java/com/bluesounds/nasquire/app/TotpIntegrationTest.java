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
    void resetAttempts() { attempts.reset("", "127.0.0.1", "admin"); attempts.reset(LoginAttempts.MANAGE, "127.0.0.1", "admin"); }
    int auditCount(String action, int status) { return db.queryForObject("SELECT COUNT(*) FROM audit WHERE action=? AND status=?", Integer.class, action, status); }
    String sessionId() { return ((CookieManager)client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    @Test void enrollmentChallengeRecoveryAndDisable() throws Exception {
        auth();
        json(call("/api/setup", "POST", Map.of("username", "admin", "password", "example-password-123"), null, true), 200);
        assertEquals(200, login());
        assertFalse(json(call("/api/totp", "GET", null, null, false), 200).get("enabled").asBoolean());
        assertEquals(403, call("/api/totp/enroll", "POST", null, null, false).statusCode());
        // 등록도 현재 비밀번호 재확인이 필요하다.
        assertEquals(401, call("/api/totp/enroll", "POST", null, null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/totp/enroll", "POST", Map.of("password", "wrong-password"), null, true).statusCode()); resetAttempts();
        var enrollment = json(call("/api/totp/enroll", "POST", Map.of("password", "example-password-123"), null, true), 200);
        String secret = enrollment.get("secret").asText();
        assertTrue(enrollment.get("uri").asText().contains("digits=6&period=30"));
        assertEquals(401, call("/api/totp/enable", "POST", Map.of("code", "bad"), null, true).statusCode()); resetAttempts();
        long step = System.currentTimeMillis()/30000;
        var codes = json(call("/api/totp/enable", "POST", Map.of("code", Totp.code(secret, step)), null, true), 200).get("recoveryCodes");
        assertEquals(10, codes.size()); assertEquals(10, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
        assertNotEquals(secret, db.queryForObject("SELECT totp_secret FROM administrator", String.class));
        assertTrue(db.queryForObject("SELECT totp_secret FROM administrator", String.class).startsWith("v2:"));
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
        int denied = call("/api/totp/enroll", "POST", Map.of("password", "example-password-123"), null, true).statusCode(); assertTrue(denied == 401 || denied == 403);
        assertEquals(200, call("/api/search?query=test", "GET", null, token, false).statusCode());
        var tools = json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"), token, false), 200);
        assertEquals(3, tools.at("/result/tools").size());
        assertEquals(401, call("/mcp", "POST", Map.of(), null, true).statusCode());
        assertEquals(403, call("/api/login/totp", "POST", Map.of("code", recovery), null, false).statusCode());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", "invalid"), null, true).statusCode());
        assertEquals(429, call("/api/login/totp", "POST", Map.of("code", recovery), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", Totp.code(secret, step)), null, true).statusCode()); resetAttempts();
        // 취소하면 대기 세션을 지우고 비밀번호 단계로 돌아간다.
        assertEquals(204, call("/api/login/totp", "DELETE", null, null, true).statusCode());
        assertFalse(json(call("/api/auth", "GET", null, null, false), 200).get("totpRequired").asBoolean());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", Totp.code(secret, step+1)), null, true).statusCode());
        assertEquals(202, login());
        String before = sessionId();
        assertEquals(200, call("/api/login/totp", "POST", Map.of("code", Totp.code(secret, step+1)), null, true).statusCode());
        assertNotEquals(before, sessionId()); auth();
        assertTrue(json(call("/api/auth", "GET", null, null, false), 200).get("authenticated").asBoolean());
        assertEquals(200, call("/api/sources", "GET", null, null, false).statusCode());
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "wrong", "code", Totp.code(secret, step+1)), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "example-password-123", "code", recovery), null, true).statusCode()); resetAttempts();
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "example-password-123", "code", "bad"), null, true).statusCode()); resetAttempts();
        resetAttempts(); int disableFailures = auditCount("totp-disable", 401);
        // 등록/해제 실패는 로그인과 분리된 키로 제한되어 관리자 로그인까지 잠그지 않는다.
        assertEquals(401, call("/api/totp/disable", "POST", Map.of("password", "wrong", "code", "000000"), null, true).statusCode());
        assertEquals(1, auditCount("totp-disable", 401) - disableFailures);
        // 남은 실패는 시각을 지정해 누적해 15분 잠금 상태로 만든다(실제 대기 없이).
        attempts.reset(LoginAttempts.MANAGE, "127.0.0.1", "admin"); long base = System.currentTimeMillis() - 20000;
        for (long at : new long[]{base, base + 1000, base + 3000, base + 7000, base + 15000}) {
            var ticket = attempts.acquire(LoginAttempts.MANAGE, "127.0.0.1", "admin", at); assertNotNull(ticket); attempts.failed(ticket, at);
        }
        assertEquals(429, call("/api/totp/disable", "POST", Map.of("password", "wrong", "code", "000000"), null, true).statusCode());
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(202, login());
        resetAttempts();
        // 복구 코드는 앞뒤 공백·대문자·공백/하이픈을 정규화해 받는다.
        String upper = recovery.toUpperCase(Locale.ROOT), formatted = "  " + upper.substring(0, 10) + "-" + upper.substring(10, 20) + " " + upper.substring(20) + "\t";
        assertEquals(200, call("/api/login/totp", "POST", Map.of("code", formatted), null, true).statusCode()); auth();
        assertEquals(9, db.queryForObject("SELECT COUNT(*) FROM recovery_codes", Integer.class));
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode()); assertEquals(202, login());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", recovery), null, true).statusCode()); resetAttempts();
        // 동시에 틀린 코드를 보내도 실제 검증 시도는 상한(5) 이하이고, 대기 세션은 세션별 실패 상한에서 지워진다.
        int verifiedBefore = auditCount("login-totp", 401);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> { start.await(); return call("/api/login/totp", "POST", Map.of("code", "bad-code"), null, true).statusCode(); }));
            start.countDown();
            for (var future : futures) { int status = future.get(); assertTrue(status == 401 || status == 429, String.valueOf(status)); }
        } finally { pool.shutdownNow(); }
        int verified = auditCount("login-totp", 401) - verifiedBefore;
        assertTrue(verified >= 1 && verified <= LoginAttempts.LIMIT, String.valueOf(verified));
        resetAttempts();
        // 세션별 실패 상한: 시도 제한을 초기화해도 같은 대기 세션에서 5회 실패하면 대기 상태가 사라진다.
        for (int i = verified + 1; i < LoginAttempts.LIMIT; i++) { assertEquals(401, call("/api/login/totp", "POST", Map.of("code", "bad-code"), null, true).statusCode()); resetAttempts(); }
        assertFalse(json(call("/api/auth", "GET", null, null, false), 200).get("totpRequired").asBoolean());
        assertEquals(401, call("/api/login/totp", "POST", Map.of("code", codes.get(1).asText()), null, true).statusCode());
        assertEquals(202, login());
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

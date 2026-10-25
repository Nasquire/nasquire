package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import com.bluesounds.nasquire.core.IndexingService;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthIntegrationTest {
    static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-oauth-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    static final String ISSUER = "https://example.com", RESOURCE = ISSUER + "/mcp", REDIRECT = "https://client.example.com/callback";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString());
        r.add("nasquire.oauth.enabled", () -> "true"); r.add("nasquire.public-url", () -> ISSUER);
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "50");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
    @Autowired OAuthService oauth;
    @Autowired JdbcTemplate db;
    @Autowired LoginAttempts attempts;
    @Autowired TotpService totp;
    @Autowired EmbeddingSettings secrets;
    @Autowired DocumentService documents;
    @Autowired IndexingService indexing;
    @Autowired DownloadService downloads;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    final HttpClient anonymous = HttpClient.newHttpClient();
    String csrf;
    final String source = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
    String url(String path) { return "http://127.0.0.1:" + port + path; }
    HttpResponse<String> request(HttpClient c, String path, String method, Object body, boolean form, boolean csrfEnabled, String bearer) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url(path)));
        if (csrfEnabled) b.header("X-CSRF-TOKEN", csrf);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (path.equals("/mcp")) b.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25");
        String payload = body == null ? "" : form ? form((Map<?, ?>)body) : mapper.writeValueAsString(body);
        if (body != null) b.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
        return c.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String enc(Object s) { return URLEncoder.encode(s.toString(), java.nio.charset.StandardCharsets.UTF_8); }
    static String form(Map<?, ?> p) { return p.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(java.util.stream.Collectors.joining("&")); }
    JsonNode json(HttpResponse<String> r, int status) { assertEquals(status, r.statusCode()); return mapper.readTree(r.body()); }
    @BeforeAll void setup() throws Exception {
        storage.setup("admin", "example-password-123");
        csrf = json(request(client, "/api/auth", "GET", null, false, false, null), 200).get("csrfToken").asText();
        assertEquals(200, request(client, "/api/login", "POST", Map.of("username", "admin", "password", "example-password-123"), true, true, null).statusCode());
        csrf = json(request(client, "/api/auth", "GET", null, false, false, null), 200).get("csrfToken").asText();
        Files.createDirectories(TEMP.resolve("source")); Files.writeString(TEMP.resolve("source/notes.md"), "OAuth searchable ocean document");
        Files.createDirectories(TEMP.resolve("other")); Files.writeString(TEMP.resolve("other/private.md"), "confidential private ocean");
        createSource(source, "source"); createSource(other, "other");
        long deadline = System.currentTimeMillis() + 10000;
        while (documents.search(null, "ocean", null, 10).size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(2, documents.search(null, "ocean", null, 10).size());
    }
    void createSource(String id, String name) {
        storage.save(new Source(id, name, TEMP.resolve(name), List.of(), List.of(), "directory"));
        indexing.reindex(id);
    }

    @BeforeEach void reset() {
        db.update("DELETE FROM audit");
        ((Map<?, ?>)ReflectionTestUtils.getField(attempts, "auditWindows")).clear();
        db.update("DELETE FROM oauth_tokens"); db.update("DELETE FROM oauth_grants"); db.update("DELETE FROM oauth_clients");
        oauth.clock = Clock.systemUTC(); oauth.maxClients = OAuthService.MAX_CLIENTS;
        ((Map<?, ?>)ReflectionTestUtils.getField(attempts, "windows")).clear();
    }
    @AfterEach void resetClock() { oauth.clock = Clock.systemUTC(); oauth.maxClients = OAuthService.MAX_CLIENTS; }
    String register() throws Exception {
        return json(request(anonymous, "/oauth/register", "POST", Map.of("client_name", "Example client", "redirect_uris", List.of(REDIRECT)), false, false, null), 201).get("client_id").asText();
    }
    Map<String, String> authorization(String id, String verifier) {
        return new HashMap<>(Map.of("client_id", id, "redirect_uri", REDIRECT, "response_type", "code", "code_challenge", OAuthService.challenge(verifier), "code_challenge_method", "S256", "resource", RESOURCE, "scope", "mcp:read", "state", "original state & value"));
    }
    String approve(String id, String verifier, List<String> sources) throws Exception {
        String path = "/oauth/authorize?" + form(authorization(id, verifier));
        var page = request(client, path, "GET", null, false, false, null);
        assertEquals(200, page.statusCode()); assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("form-action 'self' https://client.example.com"));
        String nonce = hidden(page.body(), "nonce"), consentCsrf = hidden(page.body(), "_csrf");
        String body = "decision=approve&nonce=" + enc(nonce) + "&_csrf=" + enc(consentCsrf) + sources.stream().map(s -> "&sources=" + enc(s)).collect(java.util.stream.Collectors.joining());
        var req = HttpRequest.newBuilder(URI.create(url("/oauth/authorize"))).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var approved = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, approved.statusCode());
        var params = parse(URI.create(approved.headers().firstValue("Location").orElseThrow()).getRawQuery());
        assertEquals("original state & value", params.get("state")); assertEquals(ISSUER, params.get("iss")); return params.get("code");
    }
    static String hidden(String html, String name) {
        var m = java.util.regex.Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html); assertTrue(m.find()); return m.group(1);
    }
    static Map<String, String> parse(String query) {
        var p = new HashMap<String, String>(); for (String part : query.split("&")) { var kv = part.split("=", 2); p.put(URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8), URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8)); } return p;
    }
    Map<String, String> exchange(String id, String code, String verifier) { return new HashMap<>(Map.of("grant_type", "authorization_code", "client_id", id, "code", code, "code_verifier", verifier, "redirect_uri", REDIRECT, "resource", RESOURCE)); }
    HttpResponse<String> token(Map<String, String> p) throws Exception { return request(anonymous, "/oauth/token", "POST", p, true, false, null); }
    JsonNode issued(String id, String verifier, List<String> sources) throws Exception { return json(token(exchange(id, approve(id, verifier, sources), verifier)), 200); }
    HttpResponse<String> mcpResponse(String value, String method, Map<String, Object> params) throws Exception {
        return request(anonymous, "/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params), false, false, value);
    }
    JsonNode tool(String value, String name, Map<String, Object> args) throws Exception { return json(mcpResponse(value, "tools/call", Map.of("name", name, "arguments", args)), 200); }
    @Test void metadataRegistrationAndLimits() throws Exception {
        var meta = json(request(anonymous, "/.well-known/oauth-authorization-server", "GET", null, false, false, null), 200);
        assertEquals(List.of("S256"), mapper.convertValue(meta.get("code_challenge_methods_supported"), List.class));
        assertEquals(List.of("code"), mapper.convertValue(meta.get("response_types_supported"), List.class));
        assertEquals(List.of("none"), mapper.convertValue(meta.get("token_endpoint_auth_methods_supported"), List.class));
        for (String key : List.of("authorization_endpoint", "token_endpoint", "registration_endpoint", "revocation_endpoint")) assertTrue(meta.get(key).asText().startsWith(ISSUER + "/oauth/"));
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            var resource = json(request(anonymous, path, "GET", null, false, false, null), 200); assertEquals(RESOURCE, resource.get("resource").asText()); assertEquals(ISSUER, resource.at("/authorization_servers/0").asText());
        }
        var denied = mcpResponse(null, "tools/list", Map.of()); assertEquals(401, denied.statusCode()); assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow().contains("resource_metadata=\"https://example.com/"));
        for (String uri : List.of("http://remote.example.com/cb", "https://user:pass@example.com/cb", "https://example.com/cb#fragment", "file:///tmp/cb")) assertFalse(OAuthService.safeUri(uri));
        for (String uri : List.of("http://127.0.0.1:54321/cb", "http://[::1]:9999/cb", "http://localhost:1234/cb", REDIRECT)) assertTrue(OAuthService.safeUri(uri));
        assertEquals(400, request(anonymous, "/oauth/register", "POST", Map.of("redirect_uris", List.of("http://example.com/cb")), false, false, null).statusCode());
        assertThrows(OAuthService.Error.class, () -> oauth.register(Map.of("redirect_uris", Collections.nCopies(11, REDIRECT))));
        assertThrows(OAuthService.Error.class, () -> oauth.register(Map.of("redirect_uris", List.of(REDIRECT), "client_name", "x".repeat(201))));
        assertThrows(OAuthService.Error.class, () -> oauth.register(Map.of("redirect_uris", List.of(REDIRECT), "token_endpoint_auth_method", "client_secret_basic")));
        String id = register(); assertEquals(400, token(exchange(id, OAuthService.random(), OAuthService.random())).statusCode());
        oauth.maxClients = 3;
        for (int i = 0; i < oauth.maxClients - 1; i++) oauth.register(Map.of("redirect_uris", List.of(REDIRECT)));
        db.update("UPDATE oauth_clients SET created=?", oauth.now() - 86400001);
        assertNotNull(register()); assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM oauth_clients", Integer.class));
        oauth.maxClients = OAuthService.MAX_CLIENTS;
        for (int i = 0; i < 7; i++) register();
        assertEquals(429, request(anonymous, "/oauth/register", "POST", Map.of("redirect_uris", List.of(REDIRECT)), false, false, null).statusCode());
        ((Map<?, ?>)ReflectionTestUtils.getField(attempts, "windows")).clear();
        assertEquals(413, request(anonymous, "/oauth/register", "POST", Map.of("client_name", "x".repeat(25000)), false, false, null).statusCode());
        for (int i = 0; i < 60; i++) assertEquals(401, token(Map.of()).statusCode());
        assertEquals(429, token(Map.of()).statusCode());
    }
    @Test void officialSdkMetadataKeepsPublicReadOnlyRegistration() throws Exception {
        var input = new HashMap<String, Object>(Map.of("client_name", "Official SDK", "redirect_uris", List.of("http://127.0.0.1:8104/callback"),
                "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code"), "scope", "mcp:read", "application_type", "native",
                "client_uri", "https://client.example.com", "unknown_extension", Map.of("ignored", true)));
        var registered = json(request(anonymous, "/oauth/register", "POST", input, false, false, null), 201);
        assertEquals("none", registered.get("token_endpoint_auth_method").asText());
        assertEquals("mcp:read", registered.get("scope").asText());
        assertFalse(registered.has("application_type")); assertFalse(registered.has("client_uri"));
        assertFalse(registered.has("unknown_extension")); assertFalse(registered.has("client_secret"));
        assertFalse((Boolean)oauth.client(registered.get("client_id").asText()).get("APPROVED"));
        input.put("scope", "mcp:write"); assertThrows(OAuthService.Error.class, () -> oauth.register(input));
        input.put("scope", "mcp:read"); input.put("token_endpoint_auth_method", "client_secret_post");
        assertThrows(OAuthService.Error.class, () -> oauth.register(input));
    }
    @Test void registrationEvictsOnlyOldestUnapprovedWithoutGrant() throws Exception {
        oauth.maxClients = 3;
        String approved = register(), verifier = OAuthService.random();
        var active = issued(approved, verifier, List.of(source));
        String oldest = register(), newest = register();
        db.update("UPDATE oauth_clients SET created=? WHERE id=?", oauth.now() - 2000, approved);
        db.update("UPDATE oauth_clients SET created=? WHERE id=?", oauth.now() - 1000, oldest);
        String replacement = register();
        assertThrows(OAuthService.Error.class, () -> oauth.client(oldest));
        for (String id : List.of(approved, newest, replacement)) assertNotNull(oauth.client(id));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM oauth_clients", Integer.class));
        assertEquals(200, mcpResponse(active.get("access_token").asText(), "tools/list", Map.of()).statusCode());
        assertTrue(storage.audit().toString().contains("oauth-register-evict"));
        // grant를 가진 행은 미승인으로 표시되어도 퇴출하지 않는다.
        db.update("UPDATE oauth_clients SET approved=TRUE");
        db.update("UPDATE oauth_clients SET approved=FALSE WHERE id=?", approved);
        assertEquals(503, request(anonymous, "/oauth/register", "POST", Map.of("redirect_uris", List.of(REDIRECT)), false, false, null).statusCode());
        db.update("UPDATE oauth_clients SET approved=TRUE");
        assertEquals(503, request(anonymous, "/oauth/register", "POST", Map.of("redirect_uris", List.of(REDIRECT)), false, false, null).statusCode());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM oauth_clients", Integer.class));
    }
    @Test void emptyConsentRenewsPendingAndNonceWithoutGrant() throws Exception {
        String id = register(), verifier = OAuthService.random();
        var a = oauth.validate(authorization(id, verifier));
        assertThrows(OAuthService.Error.class, () -> oauth.approve(a, List.of()));
        String page = request(client, "/oauth/authorize?" + form(authorization(id, verifier)), "GET", null, false, false, null).body();
        String oldNonce = hidden(page, "nonce");
        var empty = request(client, "/oauth/authorize", "POST", Map.of("decision", "approve", "nonce", oldNonce), true, true, null);
        assertEquals(200, empty.statusCode()); assertTrue(empty.headers().firstValue("Location").isEmpty());
        assertTrue(empty.body().contains("소스를 하나 이상 선택"));
        assertEquals("no-store", empty.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM oauth_grants", Integer.class));
        assertEquals(false, db.queryForObject("SELECT approved FROM oauth_clients WHERE id=?", Boolean.class, id));
        String nonce = hidden(empty.body(), "nonce"); assertNotEquals(oldNonce, nonce);
        assertEquals(400, request(client, "/oauth/authorize", "POST", Map.of("decision", "approve", "nonce", oldNonce, "sources", source), true, true, null).statusCode());
        var approved = request(client, "/oauth/authorize", "POST", Map.of("decision", "approve", "nonce", nonce, "sources", source), true, true, null);
        assertEquals(302, approved.statusCode());
        var p = parse(URI.create(approved.headers().firstValue("Location").orElseThrow()).getRawQuery());
        assertEquals("original state & value", p.get("state"));
        assertEquals(200, token(exchange(id, p.get("code"), verifier)).statusCode());
    }
    @Test void consentWithoutServerSourcesHasNoApprovalButton() throws Exception {
        String id = register(), verifier = OAuthService.random();
        db.update("DELETE FROM sources");
        try {
            var page = request(client, "/oauth/authorize?" + form(authorization(id, verifier)), "GET", null, false, false, null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("소스를 먼저 등록"));
            assertFalse(page.body().contains("value=\"approve\""));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM oauth_grants", Integer.class));
        } finally { createSource(source, "source"); createSource(other, "other"); }
    }
    @Test void bearerChallengeOnlyForAuthenticationAndScopeFailures() throws Exception {
        var invalid = mcpResponse(OAuthService.random(), "tools/list", Map.of());
        assertEquals(401, invalid.statusCode());
        assertEquals("Bearer error=\"invalid_token\", resource_metadata=\"" + ISSUER + "/.well-known/oauth-protected-resource/mcp\"", invalid.headers().firstValue("WWW-Authenticate").orElseThrow());
        var origin = HttpRequest.newBuilder(URI.create(url("/mcp"))).header("Origin", "https://other.example.com").POST(HttpRequest.BodyPublishers.noBody()).build();
        var denied = anonymous.send(origin, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode()); assertTrue(denied.headers().firstValue("WWW-Authenticate").isEmpty());
    }
    @Test void pkceRedirectResourceSessionAndCsrf() throws Exception {
        String id = register(), verifier = OAuthService.random();
        var valid = authorization(id, verifier);
        for (String field : List.of("code_challenge", "code_challenge_method", "resource")) {
            var p = new HashMap<>(valid); p.remove(field); assertThrows(OAuthService.Error.class, () -> oauth.validate(p));
        }
        for (var entry : Map.of("code_challenge_method", "plain", "redirect_uri", REDIRECT + "/different", "resource", "https://other.example.com/mcp", "response_type", "token", "scope", "write").entrySet()) {
            var p = new HashMap<>(valid); p.put(entry.getKey(), entry.getValue()); assertThrows(OAuthService.Error.class, () -> oauth.validate(p));
        }
        var unauthenticated = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String validCsrf = json(request(unauthenticated, "/api/auth", "GET", null, false, false, null), 200).get("csrfToken").asText();
        var noAdmin = HttpRequest.newBuilder(URI.create(url("/oauth/authorize"))).header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", validCsrf)
                .POST(HttpRequest.BodyPublishers.ofString("decision=approve")).build();
        assertEquals(401, unauthenticated.send(noAdmin, HttpResponse.BodyHandlers.discarding()).statusCode());
        var loginPage = request(anonymous, "/oauth/authorize?" + form(valid), "GET", null, false, false, null); assertEquals(200, loginPage.statusCode()); assertFalse(loginPage.body().contains("name=\"decision\""));
        assertEquals(403, request(client, "/oauth/authorize", "POST", Map.of("decision", "approve"), true, false, null).statusCode());
        var anonCsrf = json(request(anonymous, "/api/auth", "GET", null, false, false, null), 200).get("csrfToken").asText();
        // 별도 쿠키 없는 클라이언트는 CSRF 토큰만 복사해도 관리자 승인을 할 수 없다.
        assertEquals(403, request(anonymous, "/oauth/authorize", "POST", Map.of("_csrf", anonCsrf, "decision", "approve"), true, false, null).statusCode());
        String code = approve(id, verifier, List.of(source));
        var wrongClient = exchange(register(), code, verifier); assertEquals(400, token(wrongClient).statusCode());
        for (var entry : Map.of("code_verifier", OAuthService.random(), "redirect_uri", REDIRECT + "/different", "resource", "https://other.example.com/mcp").entrySet()) {
            var p = exchange(id, code, verifier); p.put(entry.getKey(), entry.getValue()); assertEquals(400, token(p).statusCode());
        }
        var p = exchange(id, code, verifier); p.remove("code_verifier"); assertEquals(400, token(p).statusCode());
        var response = token(exchange(id, code, verifier)); var issued = json(response, 200); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String access = issued.get("access_token").asText(); assertEquals(43, access.length());
        assertEquals(200, mcpResponse(access, "tools/list", Map.of()).statusCode());
        assertEquals(400, token(exchange(id, code, verifier)).statusCode());
        assertEquals(401, mcpResponse(access, "tools/list", Map.of()).statusCode());
        String denialPage = request(client, "/oauth/authorize?" + form(valid), "GET", null, false, false, null).body();
        var denial = request(client, "/oauth/authorize", "POST", Map.of("nonce", hidden(denialPage, "nonce"), "decision", "deny"), true, true, null);
        assertEquals(302, denial.statusCode()); assertTrue(denial.headers().firstValue("Location").orElseThrow().contains("error=access_denied"));
    }
    @Test void pendingTotpCannotApproveAndCompletedLoginCan() throws Exception {
        String id = register(), verifier = OAuthService.random(), secret = Totp.secret();
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        try {
            totp.enable(secrets.encrypt(EmbeddingSettings.TOTP, secret), Totp.code(secret, System.currentTimeMillis() / 30000 - 1));
            String browserCsrf = json(request(browser, "/api/auth", "GET", null, false, false, null), 200).get("csrfToken").asText();
            var login = HttpRequest.newBuilder(URI.create(url("/api/login"))).header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", browserCsrf)
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", "admin", "password", "example-password-123")))).build();
            assertEquals(202, browser.send(login, HttpResponse.BodyHandlers.discarding()).statusCode());
            var state = json(request(browser, "/api/auth", "GET", null, false, false, null), 200);
            assertFalse(state.get("authenticated").asBoolean()); assertTrue(state.get("totpRequired").asBoolean());
            String path = "/oauth/authorize?" + form(authorization(id, verifier));
            assertFalse(request(browser, path, "GET", null, false, false, null).body().contains("name=\"decision\""));
            var post = HttpRequest.newBuilder(URI.create(url("/oauth/authorize"))).header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", state.get("csrfToken").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("decision=approve")).build();
            assertEquals(401, browser.send(post, HttpResponse.BodyHandlers.discarding()).statusCode());
            var verified = HttpRequest.newBuilder(URI.create(url("/api/login/totp"))).header("Content-Type", "application/json").header("X-CSRF-TOKEN", state.get("csrfToken").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("code", Totp.code(secret, System.currentTimeMillis() / 30000))))).build();
            assertEquals(200, browser.send(verified, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertTrue(request(browser, path, "GET", null, false, false, null).body().contains("name=\"decision\""));
        } finally { db.update("UPDATE administrator SET totp_secret=NULL,totp_step=-1"); db.update("DELETE FROM recovery_codes"); }
    }
    @Test void concurrentCodeAndRefreshReplayRevokeIssuedTokens() throws Exception {
        String id = register(), verifier = OAuthService.random();
        var p = exchange(id, approve(id, verifier, List.of(source)), verifier);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Map<String, Object>> call = () -> {
                try { return oauth.token(p); } catch (OAuthService.Error e) { return Map.of("error", e.error); }
            };
            var results = workers.invokeAll(List.of(call, call));
            int successes = 0;
            for (var result : results) {
                var r = result.get();
                if (r.containsKey("access_token")) { successes++; assertNull(oauth.authenticate(SecurityConfiguration.hash(r.get("access_token").toString()))); }
                else assertEquals("invalid_grant", r.get("error"));
            }
            assertEquals(1, successes);
            var initial = issued(id, verifier, List.of(source));
            var refreshRequest = Map.of("grant_type", "refresh_token", "client_id", id, "refresh_token", initial.get("refresh_token").asText(), "resource", RESOURCE);
            java.util.concurrent.Callable<Map<String, Object>> refresh = () -> {
                try { return oauth.token(refreshRequest); } catch (OAuthService.Error e) { return Map.of("error", e.error); }
            };
            results = workers.invokeAll(List.of(refresh, refresh)); successes = 0;
            for (var result : results) {
                var r = result.get();
                if (r.containsKey("access_token")) { successes++; assertNotNull(oauth.authenticate(SecurityConfiguration.hash(r.get("access_token").toString()))); }
                else fail("유예 안 동시 갱신 실패");
            }
            assertEquals(2, successes);
            assertEquals(results.get(0).get(), results.get(1).get());
        }
    }
    @Test void sourceIsolationReadOnlyAudienceAndApiCoexistence() throws Exception {
        String id = register(), verifier = OAuthService.random();
        var issued = issued(id, verifier, List.of(source)); String access = issued.get("access_token").asText();
        var success = mcpResponse(access, "tools/list", Map.of());
        assertTrue(success.headers().firstValue("WWW-Authenticate").isEmpty());
        var tools = json(success, 200); assertEquals(3, tools.at("/result/tools").size());
        var searched = tool(access, "search_documents", Map.of("query", "ocean"));
        assertFalse(searched.at("/result/isError").asBoolean());
        var hits = mapper.readTree(searched.at("/result/content/0/text").asText()); assertEquals(1, hits.size()); assertEquals(source, hits.get(0).get("source").asText());
        assertTrue(tool(access, "search_documents", Map.of("query", "ocean", "source", other)).at("/result/isError").asBoolean());
        assertTrue(tool(access, "get_document", Map.of("path", other + ":private.md")).at("/result/isError").asBoolean());
        assertTrue(tool(access, "download_file", Map.of("path", other + ":private.md")).at("/result/isError").asBoolean());
        assertTrue(tool(access, "write_file", Map.of("path", source + ":notes.md")).has("error"));
        var ticket = mapper.readTree(tool(access, "download_file", Map.of("path", source + ":notes.md")).at("/result/content/0/text").asText()).get("url").asText();
        var downloadPath = URI.create(ticket).getRawPath() + "?" + URI.create(ticket).getRawQuery();
        assertNotNull(storage.identity(oauth.authenticate(SecurityConfiguration.hash(access)).tokenId()));
        assertEquals(source + ":notes.md", downloads.verify(parse(URI.create(ticket).getRawQuery()).get("ticket")));
        assertEquals(200, request(anonymous, downloadPath, "GET", null, false, false, null).statusCode());
        for (String path : List.of("/api/search?query=ocean", "/api/sources", "/api/download?path=" + enc(source + ":notes.md"))) assertEquals(401, request(client, path, "GET", null, false, false, access).statusCode());
        String apiValue = OAuthService.random(), tokenId = UUID.randomUUID().toString(); storage.addToken(tokenId, "Example API", SecurityConfiguration.hash(apiValue), System.currentTimeMillis() + 600000, List.of(source));
        assertEquals(200, request(anonymous, "/api/search?query=ocean", "GET", null, false, false, apiValue).statusCode()); assertEquals(200, mcpResponse(apiValue, "tools/list", Map.of()).statusCode());
        assertEquals(401, request(client, "/mcp", "POST", Map.of(), false, true, null).statusCode());
        db.update("UPDATE oauth_grants SET audience=?", "https://other.example.com/mcp"); assertEquals(401, mcpResponse(access, "tools/list", Map.of()).statusCode());
        db.update("UPDATE oauth_grants SET audience=?", RESOURCE);
        db.update("DELETE FROM sources WHERE id=?", source);
        var denied = mcpResponse(access, "tools/list", Map.of()); assertEquals(403, denied.statusCode()); assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow().contains("insufficient_scope"));
        assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow().contains("scope=\"mcp:read\", resource_metadata=\"" + ISSUER));
        assertEquals(403, request(anonymous, downloadPath, "GET", null, false, false, null).statusCode());
        createSource(source, "source");
        db.update("UPDATE oauth_grants SET sources=''");
        assertEquals(403, mcpResponse(access, "tools/list", Map.of()).statusCode());
        assertTrue(documents.scope(oauth.authenticate(SecurityConfiguration.hash(access)), null).isEmpty());
    }
    @Test void rotationReplayRevocationExpirationAndSecretAbsence() throws Exception {
        String id = register(), verifier = OAuthService.random();
        assertEquals(400, request(anonymous, "/oauth/revoke", "POST", Map.of("client_id", id), true, false, null).statusCode());
        var initial = issued(id, verifier, List.of(source)); String refresh = initial.get("refresh_token").asText();
        var p = new HashMap<>(Map.of("grant_type", "refresh_token", "client_id", id, "refresh_token", refresh, "resource", RESOURCE));
        var wrongResource = new HashMap<>(p); wrongResource.put("resource", "https://other.example.com/mcp"); assertEquals(400, token(wrongResource).statusCode());
        var wrongClient = new HashMap<>(p); wrongClient.put("client_id", register()); assertEquals(400, token(wrongClient).statusCode());
        var rotated = json(token(p), 200); String access = rotated.get("access_token").asText();
        assertFalse(refresh.equals(rotated.get("refresh_token").asText())); assertEquals(200, mcpResponse(access, "tools/list", Map.of()).statusCode());
        assertEquals(rotated, json(token(p), 200));
        oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(11));
        assertEquals(400, token(p).statusCode()); assertEquals(401, mcpResponse(access, "tools/list", Map.of()).statusCode());
        p.put("refresh_token", rotated.get("refresh_token").asText()); assertEquals(400, token(p).statusCode());
        oauth.clock = Clock.systemUTC();
        var again = issued(id, verifier, List.of(source)); access = again.get("access_token").asText();
        assertEquals(200, request(anonymous, "/oauth/revoke", "POST", Map.of("client_id", id, "token", again.get("refresh_token").asText()), true, false, null).statusCode()); assertEquals(401, mcpResponse(access, "tools/list", Map.of()).statusCode());
        var expiry = issued(id, verifier, List.of(source));
        oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofHours(2)); assertEquals(401, mcpResponse(expiry.get("access_token").asText(), "tools/list", Map.of()).statusCode());
        oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(31));
        assertEquals(400, token(Map.of("grant_type", "refresh_token", "client_id", id, "refresh_token", expiry.get("refresh_token").asText(), "resource", RESOURCE)).statusCode());
        oauth.clock = Clock.systemUTC(); String code = approve(id, verifier, List.of(source)); oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61)); assertEquals(400, token(exchange(id, code, verifier)).statusCode());
        oauth.clock = Clock.systemUTC(); var active = issued(id, verifier, List.of(source));
        assertEquals(403, request(client, "/api/oauth/revoke", "POST", Map.of("kind", "client", "id", id), false, false, null).statusCode());
        assertEquals(200, request(client, "/api/oauth/revoke", "POST", Map.of("kind", "client", "id", id), false, true, null).statusCode()); assertEquals(401, mcpResponse(active.get("access_token").asText(), "tools/list", Map.of()).statusCode());
        String persisted = db.queryForList("SELECT * FROM oauth_tokens").toString() + db.queryForList("SELECT * FROM oauth_grants") + db.queryForList("SELECT * FROM audit");
        for (String secret : List.of(verifier, code, refresh, initial.get("access_token").asText(), active.get("access_token").asText())) assertFalse(persisted.contains(secret));
        String audit = storage.audit().toString(); for (String action : List.of("register", "approve", "issue", "refresh", "refresh-reuse", "revoke", "admin-revoke")) assertTrue(audit.contains("oauth-" + action));
    }

    @Test void saturatedTokenAndMcpBucketsKeepValidCredentialsWorking() throws Exception {
        String id = register(), verifier = OAuthService.random();
        String code = approve(id, verifier, List.of(source));
        for (int i = 0; i < 65; i++) token(Map.of());
        var limited = token(Map.of());
        assertEquals("temporarily_unavailable", json(limited, 429).get("error").asText());
        assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow());
        var initial = json(token(exchange(id, code, verifier)), 200);
        var refresh = Map.of("grant_type", "refresh_token", "client_id", id, "refresh_token", initial.get("refresh_token").asText(), "resource", RESOURCE);
        var rotated = json(token(refresh), 200);
        assertEquals(rotated, json(token(refresh), 200));
        for (int i = 0; i < 65; i++) mcpResponse("invalid", "tools/list", Map.of());
        assertEquals(429, mcpResponse("invalid", "tools/list", Map.of()).statusCode());
        assertEquals(200, mcpResponse(rotated.get("access_token").asText(), "tools/list", Map.of()).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit WHERE action='oauth-rate-limit-token'", Integer.class));
    }
    @Test void queryStringsClientErrorsAndSafeAuthorizationRedirects() throws Exception {
        String id = register(), verifier = OAuthService.random();
        for (String endpoint : List.of("token", "revoke")) {
            var response = request(anonymous, "/oauth/" + endpoint + "?secret=forbidden", "POST", Map.of("client_id", id), true, false, null);
            assertEquals("invalid_request", json(response, 400).get("error").asText());
            assertEquals(401, request(anonymous, "/oauth/" + endpoint, "POST", Map.of("client_id", "unknown"), true, false, null).statusCode());
        }
        for (var error : Map.of("scope", "invalid_scope", "resource", "invalid_target", "response_type", "unsupported_response_type").entrySet()) {
            var parameters = authorization(id, verifier); parameters.put(error.getKey(), "wrong"); parameters.remove("state");
            var response = request(anonymous, "/oauth/authorize?" + form(parameters), "GET", null, false, false, null);
            assertEquals(302, response.statusCode());
            var callback = parse(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
            assertEquals(error.getValue(), callback.get("error")); assertEquals(ISSUER, callback.get("iss")); assertFalse(callback.containsKey("state"));
            parameters.put("state", "preserved state");
            response = request(anonymous, "/oauth/authorize?" + form(parameters), "GET", null, false, false, null);
            assertEquals("preserved state", parse(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery()).get("state"));
            parameters.put("redirect_uri", "https://attacker.example.com/");
            assertEquals(400, request(anonymous, "/oauth/authorize?" + form(parameters), "GET", null, false, false, null).statusCode());
        }
    }
    @Test void unicodeNameConsentAddressAndClientRetention() throws Exception {
        for (String name : List.of("fake\u202eclient", "zero\u200bwidth", "mark\u2066"))
            assertThrows(OAuthService.Error.class, () -> oauth.register(Map.of("redirect_uris", List.of(REDIRECT), "client_name", name)));
        String id = register(), verifier = OAuthService.random();
        var page = request(client, "/oauth/authorize?" + form(authorization(id, verifier)), "GET", null, false, false, null);
        assertTrue(page.body().contains(REDIRECT)); assertTrue(page.body().contains("자가 등록된 확인되지 않은 클라이언트"));
        approve(id, verifier, List.of(source));
        oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(59)); oauth.view(); assertNotNull(oauth.client(id));
        oauth.clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(61)); oauth.view(); assertThrows(OAuthService.Error.class, () -> oauth.client(id));
        oauth.clock = Clock.systemUTC();
        for (int i = 0; i < 25; i++) oauth.register(Map.of("redirect_uris", List.of(REDIRECT)), "2001:db8::" + Integer.toHexString(i + 1));
        assertEquals(20, db.queryForObject("SELECT COUNT(*) FROM oauth_clients WHERE approved=FALSE", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit WHERE action='oauth-register-evict'", Integer.class));
    }
    @Test void recentAuthenticationAndAtomicPendingAreRequiredAtConsent() {
        var controller = new OAuthController(oauth, storage, new ClientAddresses(""));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var csrfToken = new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf");
        var admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        String id = oauth.register(Map.of("redirect_uris", List.of(REDIRECT))).get("client_id").toString();
        authorization(id, OAuthService.random()).forEach(request::addParameter);
        request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis() - 600001);
        assertTrue(controller.authorize(request, response, admin, csrfToken).contains("id=\"login\""));
        request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis());
        controller.authorize(request, response, admin, csrfToken);
        var pending = (OAuthController.Pending)request.getSession().getAttribute("oauth.pending");
        assertNotNull(pending); assertNull(request.getSession().getAttribute("oauth.nonce"));
        controller.authorize(request, response, admin, csrfToken);
        var replacement = (OAuthController.Pending)request.getSession().getAttribute("oauth.pending");
        assertNotEquals(pending.nonce(), replacement.nonce());
        request.setParameter("nonce", pending.nonce()); request.setParameter("decision", "approve"); request.setParameter("sources", source);
        assertEquals(400, assertThrows(OAuthService.Error.class, () -> controller.consent(request, response, admin, csrfToken)).status);
        request.setParameter("nonce", replacement.nonce());
        request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis() - 600001);
        assertEquals(401, assertThrows(OAuthService.Error.class, () -> controller.consent(request, response, admin, csrfToken)).status);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM oauth_grants", Integer.class));
        request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis());
        controller.consent(request, response, admin, csrfToken);
        assertEquals(302, response.getStatus()); assertNull(request.getSession().getAttribute("oauth.pending"));
        assertEquals(400, assertThrows(OAuthService.Error.class, () -> controller.consent(request, response, admin, csrfToken)).status);
    }

}

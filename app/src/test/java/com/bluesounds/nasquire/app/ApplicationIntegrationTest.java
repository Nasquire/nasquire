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
class ApplicationIntegrationTest {
    private static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-integration-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString());
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "50");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
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
    @Test void setupSessionTokensScopesAndMcpEndToEnd() throws Exception {
        var state = json(call("/api/auth", "GET", null, null, false), 200); csrf = state.get("csrfToken").asText();
        assertFalse(state.get("configured").asBoolean());
        assertEquals(403, call("/api/setup", "POST", Map.of("username", "admin", "password", "example-password-123"), null, false).statusCode());
        assertEquals(200, call("/api/setup", "POST", Map.of("username", "admin", "password", "example-password-123"), null, true).statusCode());
        assertEquals(409, call("/api/setup", "POST", Map.of("username", "admin", "password", "example-password-123"), null, true).statusCode());
        var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login")).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=example-password-123")).build();
        assertEquals(200, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        state = json(call("/api/auth", "GET", null, null, false), 200); csrf = state.get("csrfToken").asText(); assertTrue(state.get("authenticated").asBoolean());
        Path source = Files.createDirectory(TEMP.resolve("source")); Files.writeString(source.resolve("notes.md"), "한국어 문서 검색 ocean astronomy");
        assertEquals(400, call("/api/sources", "POST", Map.of("name", "bad", "path", TEMP.toString()), null, true).statusCode());
        var created = json(call("/api/sources", "POST", Map.of("name", "자료", "path", source.toString(), "includes", List.of("**/*.md")), null, true), 200);
        String id = created.get("id").asText();
        Path other = Files.createDirectory(TEMP.resolve("other")); Files.writeString(other.resolve("private.txt"), "confidential ocean");
        String otherId = json(call("/api/sources", "POST", Map.of("name", "Other", "path", other.toString()), null, true), 200).get("id").asText();
        JsonNode hits = null;
        for (int i = 0; i < 100; i++) { hits = json(call("/api/search?query=ocean", "GET", null, null, true), 200); if (hits.size() == 2) break; Thread.sleep(100); }
        assertNotNull(hits); assertEquals(2, hits.size());
        assertEquals(1, json(call("/api/search?query=" + URLEncoder.encode("한국어", java.nio.charset.StandardCharsets.UTF_8), "GET", null, null, true), 200).size());
        var issued = json(call("/api/tokens", "POST", Map.of("name", "test", "expiresAt", System.currentTimeMillis() + 60000, "sources", List.of(id)), null, true), 200);
        String token = issued.get("value").asText(), tokenId = issued.get("id").asText();
        assertFalse(call("/api/tokens", "GET", null, null, true).body().contains(token));
        assertFalse(call("/api/tokens", "GET", null, null, true).body().contains("token_hash"));
        assertEquals(1, json(call("/api/search?query=ocean", "GET", null, token, false), 200).size());
        assertEquals(403, call("/api/search?query=ocean&source=" + otherId, "GET", null, token, false).statusCode());
        assertEquals(403, call("/api/tokens", "GET", null, token, false).statusCode());
        assertEquals(403, call("/api/sources", "POST", Map.of(), token, false).statusCode());
        assertEquals(401, call("/api/search?query=ocean", "GET", null, "bad-token", false).statusCode());
        assertEquals(401, call("/mcp", "POST", Map.of(), null, true).statusCode());
        var crossOrigin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Origin", "https://untrusted.example.com").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
        var initialize = json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))), token, false), 200);
        assertTrue(initialize.has("result"), initialize.toString());
        var tools = json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"), token, false), 200);
        assertEquals(2, tools.at("/result/tools").size());
        var searched = mcp(token, "search_documents", Map.of("query", "한국어", "limit", 10));
        assertFalse(searched.at("/result/isError").asBoolean(), searched.toString());
        assertTrue(searched.toString().contains("notes.md"), searched.toString());
        var read = mcp(token, "get_document", Map.of("path", id + ":notes.md", "offset", 0, "length", 3));
        assertTrue(read.toString().contains("한국어"));
        var denied = mcp(token, "get_document", Map.of("path", otherId + ":private.txt"));
        assertTrue(denied.at("/result/isError").asBoolean()); assertFalse(denied.toString().contains("confidential"));
        assertTrue(mcp(token, "get_document", Map.of("path", id + ":notes.md", "length", 100001)).at("/result/isError").asBoolean());
        storage.addToken("expired", "expired", SecurityConfiguration.hash("expired-test-value"), System.currentTimeMillis() - 1, List.of(id));
        assertEquals(401, call("/api/search?query=ocean", "GET", null, "expired-test-value", false).statusCode());
        assertEquals(200, call("/api/tokens/" + tokenId, "DELETE", null, null, true).statusCode());
        assertEquals(401, call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/list"), token, false).statusCode());
        assertTrue(call("/api/audit", "GET", null, null, true).body().contains("mcp search_documents"));
        assertEquals(200, call("/api/sources/" + id, "DELETE", null, null, true).statusCode());
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode());
        assertEquals(401, call("/api/search?query=ocean", "GET", null, null, false).statusCode());
    }
    JsonNode mcp(String token, String tool, Map<String, Object> arguments) throws Exception {
        return json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments)), token, false), 200);
    }
}

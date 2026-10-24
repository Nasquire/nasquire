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
    @Autowired DocumentService documents;
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
        assertEquals(3, tools.at("/result/tools").size());
        assertTrue(tools.toString().contains("hybrid"));
        assertTrue(mcp(token, "search_documents", Map.of("query", "ocean", "mode", "vector")).at("/result/isError").asBoolean());
        assertTrue(mcp(token, "search_documents", Map.of("query", "ocean", "mode", "invalid")).at("/result/isError").asBoolean());
        var searched = mcp(token, "search_documents", Map.of("query", "한국어", "limit", 10, "mode", "keyword"));
        assertFalse(searched.at("/result/isError").asBoolean(), searched.toString());
        assertTrue(searched.toString().contains("notes.md"), searched.toString());
        var read = mcp(token, "get_document", Map.of("path", id + ":notes.md", "offset", 0, "length", 3));
        assertTrue(read.toString().contains("한국어"));
        var denied = mcp(token, "get_document", Map.of("path", otherId + ":private.txt"));
        assertTrue(denied.at("/result/isError").asBoolean()); assertFalse(denied.toString().contains("confidential"));
        assertTrue(mcp(token, "get_document", Map.of("path", id + ":notes.md", "length", 100001)).at("/result/isError").asBoolean());
        verifyDownloads(token, id, otherId, source, other);
        verifyRemoteEmbedding(token, id);
        storage.addToken("expired", "expired", SecurityConfiguration.hash("expired-test-value"), System.currentTimeMillis() - 1, List.of(id));
        assertEquals(401, call("/api/search?query=ocean", "GET", null, "expired-test-value", false).statusCode());
        assertEquals(200, call("/api/tokens/" + tokenId, "DELETE", null, null, true).statusCode());
        assertEquals(401, call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/list"), token, false).statusCode());
        assertTrue(call("/api/audit", "GET", null, null, true).body().contains("mcp search_documents"));
        assertEquals(200, call("/api/sources/" + id, "DELETE", null, null, true).statusCode());
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode());
        assertEquals(401, call("/api/search?query=ocean", "GET", null, null, false).statusCode());
    }
    void verifyDownloads(String token, String id, String otherId, Path source, Path other) throws Exception {
        String path = id + ":notes.md";
        String query = "/api/download?path=" + URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
        var issued = json(call(query, "GET", null, token, false), 200);
        String url = issued.get("url").asText();
        var anonymous = HttpClient.newHttpClient();
        var downloaded = anonymous.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, downloaded.statusCode()); assertEquals(Files.readString(source.resolve("notes.md")), downloaded.body());
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElseThrow().contains("filename*=UTF-8''notes.md"));
        assertEquals("no-store", downloaded.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(401, anonymous.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + query)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, anonymous.send(HttpRequest.newBuilder(URI.create(url + "x")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(mcp(token, "download_file", Map.of("path", otherId + ":private.txt")).at("/result/isError").asBoolean());
        assertFalse(mcp(token, "download_file", Map.of("path", path)).at("/result/isError").asBoolean());
        for (String denied : List.of(otherId + ":private.txt", id + ":../other/private.txt", id + ":/etc/passwd"))
            assertEquals(403, call("/api/download?path=" + URLEncoder.encode(denied, java.nio.charset.StandardCharsets.UTF_8), "GET", null, token, false).statusCode());
        // 발급 후 원본을 소스 밖 링크로 교체해도 수신 단계에서 거부한다.
        Files.move(source.resolve("notes.md"), source.resolve("saved.md"));
        Files.createSymbolicLink(source.resolve("notes.md"), other.resolve("private.txt"));
        try {
            assertEquals(403, anonymous.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, call(query, "GET", null, token, false).statusCode());
        } finally { Files.delete(source.resolve("notes.md")); Files.move(source.resolve("saved.md"), source.resolve("notes.md")); }
        Path unicode = source.resolve("한글 \"이름.md"); Files.writeString(unicode, "원본");
        var name = json(call("/api/download?path=" + URLEncoder.encode(id + ":" + unicode.getFileName(), java.nio.charset.StandardCharsets.UTF_8), "GET", null, token, false), 200);
        var named = anonymous.send(HttpRequest.newBuilder(URI.create(name.get("url").asText())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, named.statusCode()); assertTrue(named.headers().firstValue("Content-Disposition").orElseThrow().contains("%22"));
        Files.delete(unicode);
        var time = new java.util.concurrent.atomic.AtomicLong(1800000000);
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return java.time.Instant.ofEpochSecond(time.get()); }
        };
        var service = new DownloadService(storage, documents, "https://nas.example.com/base", 300, clock);
        var identity = new Storage.Identity("test-client", List.of(id));
        String link = service.issue(identity, path, "http://ignored.example.com").get("url").toString();
        assertTrue(link.startsWith("https://nas.example.com/base/download?ticket="));
        String ticket = link.substring(link.indexOf("ticket=") + 7);
        assertEquals(path, service.verify(ticket));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.verify(ticket + "x"));
        time.addAndGet(300);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.verify(ticket));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.issue(identity, otherId + ":private.txt", "https://nas.example.com"));
        String audit = call("/api/audit", "GET", null, null, true).body();
        assertTrue(audit.contains("download-receive")); assertFalse(audit.contains(ticket)); assertFalse(audit.contains(token));
        assertFalse(audit.contains("ticket="));
    }
    void verifyRemoteEmbedding(String token, String source) throws Exception {
        var fake = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/embeddings", exchange -> {
            assertEquals("Bearer embedding-test-value", exchange.getRequestHeaders().getFirst("Authorization"));
            var input = mapper.readTree(exchange.getRequestBody().readAllBytes()).get("input");
            var data = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < input.size(); i++) data.add(Map.of("index", i, "embedding", List.of(1, 0)));
            byte[] response = mapper.writeValueAsBytes(Map.of("data", data));
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); fake.start();
        try {
            var settings = Map.of("provider", "remote", "baseUrl", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1",
                    "model", "fake-v1", "dimensions", "2", "apiKey", "embedding-test-value", "batchSize", "2", "timeoutSeconds", "5");
            assertEquals(403, call("/api/embeddings", "PUT", settings, null, false).statusCode());
            assertEquals(403, call("/api/embeddings", "GET", null, token, false).statusCode());
            assertEquals(200, call("/api/embeddings", "PUT", settings, null, true).statusCode());
            var view = json(call("/api/embeddings", "GET", null, null, true), 200);
            assertFalse(view.has("apiKey")); assertTrue(view.get("hasKey").asBoolean());
            assertTrue(view.at("/status/reindexRequired").asBoolean());
            assertFalse(Files.readString(TEMP.resolve("data/embedding.properties")).contains("embedding-test-value"));
            assertTrue(json(call("/api/embeddings/test", "POST", null, null, true), 200).get("ok").asBoolean());
            assertEquals(409, call("/api/search?query=ocean&mode=hybrid", "GET", null, token, false).statusCode());
            assertEquals(1, json(call("/api/search?query=ocean&mode=keyword", "GET", null, token, false), 200).size());
            call("/api/embeddings/reindex", "POST", null, null, true);
            for (int i = 0; i < 100; i++) {
                view = json(call("/api/embeddings", "GET", null, null, true), 200);
                if (!view.at("/status/reindexRequired").asBoolean()) break;
                Thread.sleep(100);
            }
            assertFalse(view.at("/status/reindexRequired").asBoolean());
            assertEquals(1, json(call("/api/search?query=ocean&mode=hybrid", "GET", null, token, false), 200).size());
            assertEquals(1, json(call("/api/search?query=ocean&mode=vector", "GET", null, token, false), 200).size());
            assertFalse(mcp(token, "search_documents", Map.of("query", "ocean", "mode", "hybrid", "source", source)).at("/result/isError").asBoolean());
            try (var reopened = new com.bluesounds.nasquire.core.SearchIndex(TEMP.resolve("reopened-index"))) {
                var restored = new EmbeddingSettings(reopened, TEMP.resolve("data"), new org.springframework.mock.env.MockEnvironment());
                assertTrue((Boolean)restored.test().get("ok"));
            }
            assertEquals(200, call("/api/embeddings", "PUT", Map.of("model", "fake-v2"), null, true).statusCode());
            assertTrue(json(call("/api/embeddings", "GET", null, null, true), 200).at("/status/reindexRequired").asBoolean());
            assertEquals(200, call("/api/embeddings", "PUT", Map.of("provider", "off"), null, true).statusCode());
            assertEquals(1, json(call("/api/search?query=ocean", "GET", null, token, false), 200).size());
        } finally { fake.stop(0); }
    }
    JsonNode mcp(String token, String tool, Map<String, Object> arguments) throws Exception {
        return json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments)), token, false), 200);
    }
}

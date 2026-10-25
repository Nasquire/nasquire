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
        r.add("nasquire.upload-max-bytes", () -> "4096");
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "50");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
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
        assertEquals(403, call("/api/sources/" + id + "/status", "GET", null, token, false).statusCode());
        assertEquals(200, call("/api/sources/" + id + "/status", "GET", null, null, true).statusCode());
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
        verifyFull(token, id, source);
        verifyDownloads(token, id, otherId, source, other);
        verifySecurityReview(token, id, source);
        verifyUploadsShares(token, id, source, other);
        verifyRemoteEmbedding(token, id);
        verifyQdrant(token, id, otherId);
        storage.addToken("expired", "expired", SecurityConfiguration.hash("expired-test-value"), System.currentTimeMillis() - 1, List.of(id));
        assertEquals(401, call("/api/search?query=ocean", "GET", null, "expired-test-value", false).statusCode());
        assertEquals(200, call("/api/tokens/" + tokenId, "DELETE", null, null, true).statusCode());
        assertEquals(401, call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/list"), token, false).statusCode());
        assertTrue(call("/api/audit", "GET", null, null, true).body().contains("mcp search_documents"));
        assertEquals(200, call("/api/sources/" + id, "DELETE", null, null, true).statusCode());
        assertEquals(204, call("/api/logout", "POST", null, null, true).statusCode());
        assertEquals(401, call("/api/search?query=ocean", "GET", null, null, false).statusCode());
    }
    void verifyFull(String token, String id, Path source) throws Exception {
        var defaults = json(call("/api/full", "GET", null, null, true), 200);
        assertEquals("off", defaults.get("reranker").asText()); assertEquals("off", defaults.get("ocr").asText());
        assertEquals(403, call("/api/full", "PUT", Map.of("reranker", "off"), null, false).statusCode());
        assertEquals(403, call("/api/full", "PUT", Map.of("reranker", "off"), token, false).statusCode());
        assertEquals(403, call("/api/full", "GET", null, token, false).statusCode());
        var anonymous = HttpClient.newHttpClient();
        assertEquals(401, anonymous.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/full")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // 키 삭제도 관리자 세션+헤더 CSRF가 필요하다.
        assertEquals(403, call("/api/full/key", "DELETE", null, null, false).statusCode());
        assertEquals(403, call("/api/full/key", "DELETE", null, token, false).statusCode());
        assertEquals(200, call("/api/full/key", "DELETE", null, null, true).statusCode());
        if (!defaults.get("available").asBoolean()) {
            assertEquals(409, call("/api/full", "PUT", Map.of("reranker", "remote"), null, true).statusCode());
            assertEquals(409, call("/api/full", "PUT", Map.of("ocr", "tesseract"), null, true).statusCode());
            assertEquals(409, call("/api/full", "PUT", Map.of("reranker", "builtin"), null, true).statusCode());
            return;
        }
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/rerank", e -> {
            received.set(new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); calls.incrementAndGet();
            byte[] body = "{\"results\":[{\"index\":0,\"relevance_score\":0},{\"index\":1,\"relevance_score\":1}]}".getBytes();
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        }); server.start();
        Path extra = source.resolve("full.md");
        try {
            Files.writeString(extra, "ocean full allowed second");
            assertEquals(200, call("/api/full", "PUT", Map.of("reranker", "remote", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "model", "test-model", "apiKey", "full-test-secret", "topN", "2", "timeoutSeconds", "1"), null, true).statusCode());
            JsonNode original = null;
            for (int i = 0; i < 100; i++) {
                original = json(call("/api/search?query=ocean", "GET", null, token, false), 200);
                if (original.size() == 2) break; Thread.sleep(100);
            }
            assertEquals(2, original.size()); assertEquals(0, calls.get());
            var reranked = json(call("/api/search?query=ocean&rerank=true", "GET", null, token, false), 200);
            assertEquals(original.get(1), reranked.get(0)); assertEquals(original.get(0), reranked.get(1));
            assertFalse(received.get().contains("confidential")); assertTrue(received.get().contains("allowed"));
            var result = mcp(token, "search_documents", Map.of("query", "ocean", "rerank", true));
            assertFalse(result.at("/result/isError").asBoolean());
            assertEquals(reranked, mapper.readTree(result.at("/result/content/0/text").asText()));
            assertEquals(2, calls.get());
            // 로컬 리랭커: 모델이 없으면 네트워크 다운로드 없이 원래 순서를 유지한다.
            assertEquals(400, call("/api/full", "PUT", Map.of("reranker", "builtin", "localDownload", "maybe"), null, true).statusCode());
            assertEquals(200, call("/api/full", "PUT", Map.of("reranker", "builtin"), null, true).statusCode());
            JsonNode local = null;
            for (int i = 0; i < 100; i++) {
                local = json(call("/api/search?query=ocean&rerank=true", "GET", null, token, false), 200);
                if (local.size() == 2) break; Thread.sleep(100);
            }
            assertEquals(new HashSet<>(original.findValuesAsString("path")), new HashSet<>(local.findValuesAsString("path")));
            assertFalse(Files.exists(TEMP.resolve("data/models/mmarco-mMiniLMv2-L12-H384-v1/model-qint8.onnx")));
            assertEquals(200, call("/api/full", "PUT", Map.of("reranker", "remote"), null, true).statusCode());
            assertFalse(call("/api/full", "GET", null, null, true).body().contains("full-test-secret"));
            String stored = Files.readString(TEMP.resolve("data/full.properties"));
            assertFalse(stored.contains("full-test-secret")); assertTrue(stored.contains("v2"));
            // 재시작과 같은 로딩 경로, 용도별 AAD·키 누락 시 재생성 차단을 검증한다.
            try (var reloadIndex = new com.bluesounds.nasquire.core.SearchIndex(TEMP.resolve("reload-index"))) {
            var secretService = new EmbeddingSettings(reloadIndex, TEMP.resolve("data"), new org.springframework.mock.env.MockEnvironment());
            try (var reloaded = new FullSettings(TEMP.resolve("data"), secretService)) { assertEquals(true, reloaded.view().get("hasKey")); }
            var properties = new Properties(); try (var input = Files.newInputStream(TEMP.resolve("data/full.properties"))) { properties.load(input); }
            assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> secretService.decrypt("embedding", properties.getProperty("encryptedKey")));
            Path key = TEMP.resolve("data/embedding.key"), savedKey = TEMP.resolve("saved-full-key");
            Files.move(key, savedKey);
            try {
                assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> secretService.encrypt("embedding", "replacement"));
                assertFalse(Files.exists(key));
            } finally { Files.move(savedKey, key); }
            }
        } finally {
            assertEquals(200, call("/api/full", "PUT", Map.of("reranker", "off", "ocr", "off"), null, true).statusCode());
            Files.deleteIfExists(extra); server.stop(0);
            // 다음 테스트는 원래 한 파일만 있는 안정 상태를 전제로 한다.
            for (int i = 0; i < 100; i++) {
                if (json(call("/api/search?query=ocean", "GET", null, token, false), 200).size() == 1) break;
                Thread.sleep(100);
            }
        }
    }
    void verifyDownloads(String token, String id, String otherId, Path source, Path other) throws Exception {
        String path = id + ":notes.md";
        String query = "/api/download?path=" + URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
        var issued = json(call(query, "GET", null, token, false), 200);
        String relativeUrl = issued.get("url").asText();
        assertTrue(relativeUrl.startsWith("/download?ticket="));
        assertTrue(issued.get("notice").asText().contains("nasquire.public-url"));
        String url = "http://127.0.0.1:" + port + relativeUrl;
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
        var named = anonymous.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + name.get("url").asText())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, named.statusCode()); assertTrue(named.headers().firstValue("Content-Disposition").orElseThrow().contains("%22"));
        Files.delete(unicode);
        var time = new java.util.concurrent.atomic.AtomicLong(1800000000);
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return java.time.Instant.ofEpochSecond(time.get()); }
        };
        var service = new DownloadService(storage, documents, "https://nas.example.com/base", 300, clock);
        var identity = storage.authenticate(SecurityConfiguration.hash(token));
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
    void verifySecurityReview(String token, String sourceId, Path original) throws Exception {
        var anonymous = HttpClient.newHttpClient();
        String boundary = "SecurityBoundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.md\"\r\n\r\n" + "x".repeat(10000) + "\r\n--" + boundary + "--\r\n";
        for (String path : List.of("/api/uploads/missing?path=test.md", "/api/login", "/api/setup", "/mcp", "/s/example")) {
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = anonymous.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
            assertTrue(status == 401 || status == 403);
            try (var files = Files.list(TEMP.resolve("data/multipart"))) { assertEquals(0, files.count()); }
        }
        var noCsrf = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/uploads/missing?path=test.md"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode());
        try (var files = Files.list(TEMP.resolve("data/multipart"))) { assertEquals(0, files.count()); }
        var tooLarge = HttpRequest.newBuilder(noCsrf.uri()).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString("x".repeat(1100000))).build();
        assertEquals(413, client.send(tooLarge, HttpResponse.BodyHandlers.ofString()).statusCode());
        String id = UUID.randomUUID().toString(), secret = "review-token";
        storage.addToken(id, "review", SecurityConfiguration.hash(secret), System.currentTimeMillis() + 60000, List.of(sourceId));
        var identity = storage.authenticate(SecurityConfiguration.hash(secret));
        String path = sourceId + ":notes.md";
        String url = downloads.issue(identity, path, "https://attacker.example.com").get("url").toString();
        assertTrue(url.startsWith("/download?ticket="));
        String ticket = url.substring(url.indexOf('=') + 1);
        assertFalse(ticket.contains(sourceId)); assertFalse(ticket.contains("notes"));
        assertEquals(path, downloads.verify(ticket));
        db.update("UPDATE tokens SET sources=? WHERE id=?", "unavailable-source", id);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> downloads.verify(ticket));
        db.update("UPDATE tokens SET sources=? WHERE id=?", sourceId, id);
        assertEquals(path, downloads.verify(ticket));
        db.update("UPDATE tokens SET expires_at=0 WHERE id=?", id);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> downloads.verify(ticket));
        db.update("UPDATE tokens SET expires_at=? WHERE id=?", System.currentTimeMillis() + 60000, id);
        storage.revoke(id);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> downloads.verify(ticket));
        assertThrows(IllegalArgumentException.class, () -> new DownloadService(storage, documents, "", 3601, java.time.Clock.systemUTC()));
        var restarted = new DownloadService(storage, documents, "", 300, java.time.Clock.systemUTC());
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> restarted.verify(ticket));

        Path root = Files.createDirectory(TEMP.resolve("security-upload"));
        String uploadId = UUID.randomUUID().toString();
        storage.save(new com.bluesounds.nasquire.core.Source(uploadId, "Security", root, null, null, "upload"));
        uploads.folder(uploadId, "folder");
        uploads.upload(uploadId, "folder/file.md", new java.io.ByteArrayInputStream("one".getBytes()), false);
        var service = new ShareService(db, storage, downloads);
        String password = "review-password";
        var protectedShare = service.create(uploadId, "folder", null, password, null);
        String shareToken = protectedShare.get("url").toString().substring(3);
        assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(shareToken, "missing", null, "one"));
        assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(shareToken, "file.md", null, "one"));
        assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(shareToken, "", "bad", "one"));
        assertThrows(ShareService.Delayed.class, () -> service.authorize(shareToken, "", password, "one"));
        assertNotNull(service.authorize(shareToken, "", password, "two"));
        // 한 공유의 느린 비밀번호 검증이 다른 공유의 열람을 직렬화하지 않는다.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var encoder = new org.springframework.security.crypto.password.PasswordEncoder() {
            private final org.springframework.security.crypto.password.PasswordEncoder delegate = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
            @Override public String encode(CharSequence raw) { return delegate.encode(raw); }
            @Override public boolean matches(CharSequence raw, String encoded) {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("검증 대기 초과"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return delegate.matches(raw, encoded);
            }
        };
        var concurrent = new ShareService(db, storage, downloads, java.time.Clock.systemUTC(), encoder);
        var publicLink = service.create(uploadId, "folder/file.md", null, null, null);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = executor.submit(() -> concurrent.authorize(shareToken, "", password, "three"));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var fast = executor.submit(() -> concurrent.authorize(publicLink.get("url").toString().substring(3), "", null));
                assertNotNull(fast.get(2, java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertNotNull(slow.get(3, java.util.concurrent.TimeUnit.SECONDS));
        }
        var old = service.create(uploadId, "folder/file.md", null, null, null);
        String oldToken = old.get("url").toString().substring(3);
        Files.move(root.resolve("folder/file.md"), root.resolve("saved.md"));
        Files.writeString(root.resolve("folder/file.md"), "replacement");
        assertThrows(ShareService.Missing.class, () -> service.authorize(oldToken, "", null));
        for (String action : List.of("overwrite", "move", "trash")) {
            var link = service.create(uploadId, "folder/file.md", null, null, null);
            if (action.equals("overwrite")) uploads.upload(uploadId, "folder/file.md", new java.io.ByteArrayInputStream("two".getBytes()), true);
            if (action.equals("move")) uploads.move(uploadId, "folder", "moved", false);
            if (action.equals("trash")) uploads.trash(uploadId, "folder");
            assertThrows(ShareService.Missing.class, () -> service.authorize(link.get("url").toString().substring(3), "", null));
            if (action.equals("move")) uploads.move(uploadId, "moved", "folder", false);
        }
        String deep = String.join("/", Collections.nCopies(65, "a"));
        assertThrows(IllegalArgumentException.class, () -> uploads.folder(uploadId, deep));
        Path stale = Files.writeString(root.resolve(".nasquire-old.part"), "stale");
        Path fresh = Files.writeString(root.resolve(".nasquire-new.part"), "fresh");
        Path multipart = Files.writeString(TEMP.resolve("data/multipart/old.tmp"), "stale");
        for (Path entry : List.of(stale, multipart)) Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(0));
        Path untouched = Files.writeString(original.resolve(".nasquire-old.part"), "read-only");
        Files.setLastModifiedTime(untouched, java.nio.file.attribute.FileTime.fromMillis(0));
        uploads.cleanupStaging();
        assertFalse(Files.exists(stale)); assertFalse(Files.exists(multipart)); assertTrue(Files.exists(fresh)); assertTrue(Files.exists(untouched));
        Files.delete(untouched);
        db.update("INSERT INTO trash(id,source_id,original,deleted_at) VALUES (?,?,?,?)", "missing", uploadId, "missing", 0);
        // 실패 행을 표시하고 다음 실행에서 건너뛰어 다른 만료 항목을 처리한다.
        uploads.cleanup();
        assertTrue(db.queryForObject("SELECT cleanup_error FROM trash WHERE id='missing'", Boolean.class));
        assertTrue(db.queryForObject("SELECT cleanup_after FROM trash WHERE id='missing'", Long.class) > System.currentTimeMillis());
        uploads.upload(uploadId, "expired.md", new java.io.ByteArrayInputStream(new byte[1]), false);
        String expiredItem = uploads.trash(uploadId, "expired.md");
        db.update("UPDATE trash SET deleted_at=1 WHERE id=?", expiredItem);
        // 100개 실패 행이 한 배치를 채워도 다음 실행에서 정상 항목이 정리된다.
        for (int i = 0; i < 100; i++) db.update("INSERT INTO trash(id,source_id,original,deleted_at) VALUES (?,?,?,?)", "bad-" + i, uploadId, "missing", 0);
        uploads.cleanup(); uploads.cleanup();
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM trash WHERE id=?", Integer.class, expiredItem));
        storage.deleteSource(uploadId);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM trash WHERE source_id=?", Integer.class, uploadId));
        Path fifo = root.resolve("fifo.md");
        var process = new ProcessBuilder("mkfifo", fifo.toString()).start(); assertEquals(0, process.waitFor());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertThrows(java.io.IOException.class, () -> com.bluesounds.nasquire.core.SecureFiles.readRegular(fifo)));
        try (var channel = com.bluesounds.nasquire.core.SecureFiles.readRegular(root.resolve("saved.md"))) { assertTrue(channel.isOpen()); }
        // 실제 로그인 필터 실패 지연과 성공 초기화. 계정/IP 모두 키로 사용한다.
        var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login"))
                .header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=wrong-password")).build();
        assertEquals(401, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(429, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        Thread.sleep(1050);
        var correct = HttpRequest.newBuilder(login.uri()).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=example-password-123")).build();
        assertEquals(200, client.send(correct, HttpResponse.BodyHandlers.ofString()).statusCode());
        csrf = json(call("/api/auth", "GET", null, null, false), 200).get("csrfToken").asText();
        assertTrue(storage.audit().toString().contains("login-rate-limit"));
        assertFalse(storage.audit().toString().contains(password));
    }
    void verifyUploadsShares(String token, String readOnly, Path original, Path outside) throws Exception {
        Path root = Files.createDirectory(TEMP.resolve("uploads"));
        String id = json(call("/api/sources", "POST", Map.of("name", "Uploads", "path", root.toString(), "kind", "upload"), null, true), 200).get("id").asText();
        assertEquals(400, call("/api/sources", "POST", Map.of("name", "Overlap", "path", root.toString()), null, true).statusCode());
        assertEquals(400, call("/api/sources", "POST", Map.of("name", "Overlap upload", "path", original.toString(), "kind", "upload"), null, true).statusCode());
        assertEquals(403, call("/api/uploads/" + readOnly + "/folder", "POST", Map.of("path", "bad"), null, true).statusCode());
        assertEquals(403, call("/api/uploads/" + id + "/folder", "POST", Map.of("path", "bad"), null, false).statusCode());
        assertEquals(403, call("/api/uploads/" + id + "/folder", "POST", Map.of("path", "bad"), token, false).statusCode());
        assertEquals(200, call("/api/uploads/" + id + "/folder", "POST", Map.of("path", "folder"), null, true).statusCode());
        for (String bad : List.of("../escape.md", "/escape.md", "folder/../escape.md", "e\u0301.md", "CON.txt", ".nasquire-trash/x"))
            assertThrows(RuntimeException.class, () -> uploads.upload(id, bad, new java.io.ByteArrayInputStream(new byte[0]), false));
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertThrows(java.io.IOException.class, () -> uploads.upload(id, "link/escape.md", new java.io.ByteArrayInputStream(new byte[0]), false));
        assertThrows(java.io.IOException.class, () -> uploads.folder(id, "link/new-folder"));
        String boundary = "NasquireBoundary";
        String payload = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.md\"\r\nContent-Type: text/plain\r\n\r\nuploadgalaxy\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/uploads/" + id + "?path=folder/notes.md"))
            .header("X-CSRF-TOKEN", csrf).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("uploadgalaxy", Files.readString(root.resolve("folder/notes.md")));
        var oversized = HttpRequest.newBuilder(request.uri()).header("X-CSRF-TOKEN", csrf)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(payload.replace("uploadgalaxy", "x".repeat(4097)))).build();
        assertEquals(413, client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("uploadgalaxy", Files.readString(root.resolve("folder/notes.md")));
        uploads.upload(id, "folder/notes.md", new java.io.ByteArrayInputStream("uploadgalaxy".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
        Path staged = Files.writeString(root.resolve("staged.md"), "new");
        assertThrows(java.io.IOException.class, () -> com.bluesounds.nasquire.core.SecureFiles.moveNew(staged, root.resolve("folder/notes.md")));
        assertEquals("uploadgalaxy", Files.readString(root.resolve("folder/notes.md")));
        Files.delete(staged);
        assertThrows(IllegalStateException.class, () -> uploads.upload(id, "folder/notes.md", new java.io.ByteArrayInputStream(new byte[0]), false));
        var limited = new UploadService(storage, indexing, db, TEMP.resolve("data"), 3, 1);
        assertThrows(IllegalArgumentException.class, () -> limited.upload(id, "large.md", new java.io.ByteArrayInputStream(new byte[4]), false));
        assertFalse(Files.exists(root.resolve("large.md")));
        try (var files = Files.list(root)) { assertFalse(files.anyMatch(f -> f.getFileName().toString().endsWith(".part"))); }
        for (int i = 0; i < 100; i++) {
            var found = json(call("/api/search?query=uploadgalaxy&source=" + id, "GET", null, null, true), 200);
            if (!found.isEmpty()) break;
            Thread.sleep(100);
        }
        assertEquals(1, json(call("/api/search?query=uploadgalaxy&source=" + id, "GET", null, null, true), 200).size());
        uploads.move(id, "folder/notes.md", "folder/renamed.md", false);
        String item = uploads.trash(id, "folder/renamed.md");
        assertFalse(Files.exists(root.resolve("folder/renamed.md")));
        assertFalse(new com.bluesounds.nasquire.core.Source("x", "x", root, null, null).accepts(Path.of(".nasquire-trash/" + item)));
        uploads.restore(id, item); assertTrue(Files.exists(root.resolve("folder/renamed.md")));
        var shared = json(call("/api/shares", "POST", Map.of("source", id, "path", "folder", "maxDownloads", 1), null, true), 200);
        String url = shared.get("url").asText();
        var anonymous = HttpClient.newHttpClient();
        java.util.function.Function<String, HttpResponse<String>> get = suffix -> {
            try { return anonymous.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url + suffix)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
            catch (Exception e) { throw new RuntimeException(e); }
        };
        var listing = get.apply(""); assertEquals(200, listing.statusCode());
        assertTrue(listing.body().contains("renamed.md")); assertFalse(listing.body().contains(root.toString())); assertFalse(listing.body().contains(id));
        for (String header : List.of("Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "Cache-Control", "X-Frame-Options")) assertTrue(listing.headers().firstValue(header).isPresent());
        assertTrue(listing.headers().allValues("Set-Cookie").isEmpty());
        for (String bad : List.of("..", "../link/private.txt", "/folder/renamed.md", "e\u0301.md"))
            assertEquals(404, get.apply("?path=" + URLEncoder.encode(bad, java.nio.charset.StandardCharsets.UTF_8)).statusCode());
        Files.createSymbolicLink(root.resolve("folder/link.md"), outside.resolve("private.txt"));
        assertEquals(404, get.apply("?path=link.md&download=true").statusCode());
        assertEquals("uploadgalaxy", get.apply("?path=renamed.md&download=true").body());
        assertEquals(404, get.apply("").statusCode());
        var revoked = json(call("/api/shares", "POST", Map.of("source", id, "path", "folder/renamed.md"), null, true), 200);
        assertEquals(403, call("/api/shares", "POST", Map.of(), token, false).statusCode());
        assertEquals(403, call("/api/shares", "POST", Map.of(), null, false).statusCode());
        call("/api/shares/" + revoked.get("id").asText(), "DELETE", null, null, true);
        assertEquals(404, call(revoked.get("url").asText(), "GET", null, null, false).statusCode());
        var time = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(time.get()); }
        };
        var service = new ShareService(db, storage, downloads, clock);
        String password = "share-test-password";
        var protectedLink = service.create(id, "folder/renamed.md", time.get() + 3600000, password, null);
        String value = protectedLink.get("url").toString().substring(3);
        assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(value, "", null));
        for (int i = 0; i < 5; i++) {
            assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(value, "", "wrong-password"));
            assertThrows(ShareService.Delayed.class, () -> service.authorize(value, "", password));
            time.addAndGet(i == 4 ? 900000 : 1000L << i);
        }
        // 잠금이 끝난 뒤 실패해도 횟수를 1로 되돌려 1초 대기로 줄이지 않고 다시 15분 잠근다.
        assertThrows(ShareService.PasswordRequired.class, () -> service.authorize(value, "", "wrong-password"));
        time.addAndGet(60000);
        assertThrows(ShareService.Delayed.class, () -> service.authorize(value, "", password));
        time.addAndGet(840000);
        assertNotNull(service.authorize(value, "", password));
        var passwordLink = service.create(id, "folder/renamed.md", null, password, null);
        String publicPasswordUrl = "http://127.0.0.1:" + port + passwordLink.get("url");
        // 관리 세션을 붙여도 공유 비밀번호 검사를 우회할 수 없다.
        var challenge = client.send(HttpRequest.newBuilder(URI.create(publicPasswordUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, challenge.statusCode()); assertTrue(challenge.headers().firstValue("WWW-Authenticate").isPresent());
        var withPassword = anonymous.send(HttpRequest.newBuilder(URI.create(publicPasswordUrl))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, withPassword.statusCode()); assertEquals("uploadgalaxy", withPassword.body());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM shares WHERE token_hash=?", Integer.class, SecurityConfiguration.hash(value)));
        time.addAndGet(3600000); assertThrows(ShareService.Missing.class, () -> service.authorize(value, "", password));
        var deleted = service.create(id, "folder/renamed.md", null, null, null);
        String deletedToken = deleted.get("url").toString().substring(3);
        String removed = uploads.trash(id, "folder/renamed.md");
        assertThrows(ShareService.Missing.class, () -> service.authorize(deletedToken, "", null));
        db.update("UPDATE trash SET deleted_at=0 WHERE id=?", removed); uploads.cleanup();
        assertTrue(uploads.trashList(id).isEmpty());
        var current = storage.source(id);
        storage.save(new com.bluesounds.nasquire.core.Source(id, current.name(), current.root(), null, List.of("folder"), "upload"));
        // 뒤의 검색·임베딩 검증 소스를 잠시 제외하면 주기 색인기가 문서를 삭제할 수 있다. 정책 검사는 전용 소스로 격리한다.
        Path exclusionRoot = Files.createDirectory(TEMP.resolve("share-exclusion"));
        Files.writeString(exclusionRoot.resolve("notes.md"), "공유 제외 정책 검사");
        String exclusionId = UUID.randomUUID().toString();
        storage.save(new com.bluesounds.nasquire.core.Source(exclusionId, "Share exclusion", exclusionRoot, null, null));
        var excluded = json(call("/api/shares", "POST", Map.of("source", exclusionId, "path", "notes.md"), null, true), 200);
        storage.save(new com.bluesounds.nasquire.core.Source(exclusionId, "Share exclusion", exclusionRoot, null, List.of("notes.md")));
        // 제외 상태를 반드시 색인기에 적용해도 원래 검색 소스는 그대로 남아야 한다.
        indexing.scan(storage.sources());
        assertEquals(1, json(call("/api/search?query=ocean&source=" + readOnly, "GET", null, token, false), 200).size());
        assertEquals(404, call(excluded.get("url").asText(), "GET", null, null, false).statusCode());
        storage.deleteSource(exclusionId);
        var limiter = new ShareSecurity.PublicFilter(2, storage);
        assertTrue(limiter.allow("test", 0)); assertTrue(limiter.allow("test", 0)); assertFalse(limiter.allow("test", 0));
        assertTrue(limiter.allow("test", 60000));
        for (int i = 0; i < 4095; i++) assertTrue(limiter.allow("ip-" + i, 60000));
        assertTrue(limiter.allow("overflow", 60000)); limiter.expire(120000); assertTrue(limiter.allow("overflow", 120000));
        String audit = call("/api/audit", "GET", null, null, true).body();
        assertFalse(audit.contains(value)); assertFalse(audit.contains(password)); assertFalse(audit.contains(url));
        assertFalse(call("/api/shares", "GET", null, null, true).body().contains(value));
        storage.deleteSource(id);
        assertEquals(404, call(passwordLink.get("url").toString(), "GET", null, null, false).statusCode());
        boolean rateLimited = false;
        for (int i = 0; i < 65; i++) {
            if (call(url, "GET", null, null, false).statusCode() == 429) { rateLimited = true; break; }
        }
        assertTrue(rateLimited);
    }
    @Autowired com.bluesounds.nasquire.core.IndexingService indexing;

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
    /** 가짜 Qdrant가 서버 필터를 무시해도 토큰 범위 밖 조각이 REST/MCP로 나가지 않아야 한다. */
    void verifyQdrant(String token, String source, String otherSource) throws Exception {
        var fake = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/embeddings", exchange -> {
            var input = mapper.readTree(exchange.getRequestBody().readAllBytes()).get("input");
            var data = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < input.size(); i++) data.add(Map.of("index", i, "embedding", input.get(i).asText().contains("ocean") ? List.of(1, 0.1) : List.of(0.1, 1)));
            byte[] response = mapper.writeValueAsBytes(Map.of("data", data));
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); fake.start();
        try (var qdrant = new com.bluesounds.nasquire.core.FakeQdrant("qdrant-test-value")) {
            var settings = new HashMap<String, String>(Map.of("provider", "remote", "baseUrl", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1",
                    "model", "fake-q", "dimensions", "2", "batchSize", "2", "timeoutSeconds", "5", "vectorStore", "qdrant",
                    "qdrantUrl", qdrant.url(), "qdrantCollection", "nasquire_test", "qdrantApiKey", "qdrant-test-value"));
            settings.put("qdrantTimeoutSeconds", "5");
            assertEquals(403, call("/api/embeddings", "PUT", settings, null, false).statusCode());
            assertEquals(403, call("/api/embeddings", "PUT", settings, token, false).statusCode());
            assertEquals(200, call("/api/embeddings", "PUT", settings, null, true).statusCode());
            var view = json(call("/api/embeddings", "GET", null, null, true), 200);
            assertFalse(view.toString().contains("qdrant-test-value")); assertFalse(view.has("qdrantApiKey"));
            assertTrue(view.get("hasQdrantKey").asBoolean()); assertEquals("qdrant", view.at("/status/vectorStore").asText());
            String stored = Files.readString(TEMP.resolve("data/embedding.properties"));
            assertFalse(stored.contains("qdrant-test-value")); assertTrue(stored.contains("encryptedQdrantKey=v2\\:"), stored);
            assertTrue(view.at("/status/reindexRequired").asBoolean());
            assertEquals(409, call("/api/search?query=ocean&mode=vector", "GET", null, token, false).statusCode());
            call("/api/embeddings/reindex", "POST", null, null, true);
            for (int i = 0; i < 100; i++) {
                view = json(call("/api/embeddings", "GET", null, null, true), 200);
                if (!view.at("/status/reindexRequired").asBoolean()) break;
                Thread.sleep(100);
            }
            assertFalse(view.at("/status/reindexRequired").asBoolean());
            assertEquals(Set.of(source, otherSource), qdrant.payloadValues("nasquire_test", "source"));
            assertTrue(qdrant.requests.stream().allMatch(r -> "qdrant-test-value".equals(r.apiKey())));
            assertTrue(qdrant.requests.stream().noneMatch(r -> r.body().contains("confidential")), "본문은 Qdrant로 보내지 않음");
            var hits = json(call("/api/search?query=ocean&mode=vector", "GET", null, token, false), 200);
            assertEquals(1, hits.size()); assertEquals(source, hits.get(0).get("source").asText());
            String query = qdrant.requests("/points/query").getLast().body();
            assertTrue(query.contains("\"any\":[\"" + source + "\"]"), query); assertFalse(query.contains(otherSource));
            // 손상/악의적 서버가 필터를 무시해도 범위 밖 결과는 나오지 않는다.
            qdrant.ignoreFilter = true;
            for (String mode : List.of("vector", "hybrid")) {
                var scoped = call("/api/search?query=ocean&mode=" + mode, "GET", null, token, false);
                assertEquals(200, scoped.statusCode()); assertFalse(scoped.body().contains("confidential")); assertFalse(scoped.body().contains(otherSource));
                var tool = mcp(token, "search_documents", Map.of("query", "ocean", "mode", mode));
                assertFalse(tool.at("/result/isError").asBoolean(), tool.toString());
                assertFalse(tool.toString().contains("confidential")); assertFalse(tool.toString().contains(otherSource));
            }
            assertEquals(403, call("/api/search?query=ocean&mode=vector&source=" + otherSource, "GET", null, token, false).statusCode());
            assertEquals(2, json(call("/api/search?query=ocean&mode=vector", "GET", null, null, true), 200).size());
            qdrant.ignoreFilter = false;
            try (var reopened = new com.bluesounds.nasquire.core.SearchIndex(TEMP.resolve("reopened-qdrant-index"))) {
                var restored = new EmbeddingSettings(reopened, TEMP.resolve("data"), new org.springframework.mock.env.MockEnvironment());
                assertTrue((Boolean)restored.view().get("hasQdrantKey")); assertFalse((Boolean)restored.view().get("keyError"));
            }
            // 소스 삭제 시 Qdrant 점도 지운다.
            assertEquals(200, call("/api/sources/" + otherSource, "DELETE", null, null, true).statusCode());
            for (int i = 0; i < 100 && qdrant.payloadValues("nasquire_test", "source").contains(otherSource); i++) Thread.sleep(100);
            assertEquals(Set.of(source), qdrant.payloadValues("nasquire_test", "source"));
            qdrant.fail = true;
            var failed = call("/api/search?query=ocean&mode=vector", "GET", null, token, false);
            assertNotEquals(200, failed.statusCode()); assertFalse(failed.body().contains("secret-qdrant-body"));
            qdrant.fail = false;
            assertEquals(200, call("/api/embeddings", "PUT", Map.of("provider", "off", "vectorStore", "lucene"), null, true).statusCode());
            assertEquals(1, json(call("/api/search?query=ocean", "GET", null, token, false), 200).size());
            // 저장된 Qdrant 키 명시적 삭제: 관리자 세션+CSRF만, 메모리·암호문 모두 제거.
            assertTrue(json(call("/api/embeddings", "GET", null, null, true), 200).get("hasQdrantKey").asBoolean());
            assertEquals(403, call("/api/embeddings/qdrant-key", "DELETE", null, null, false).statusCode());
            assertEquals(403, call("/api/embeddings/qdrant-key", "DELETE", null, token, false).statusCode());
            assertEquals(200, call("/api/embeddings/qdrant-key", "DELETE", null, null, true).statusCode());
            assertFalse(json(call("/api/embeddings", "GET", null, null, true), 200).get("hasQdrantKey").asBoolean());
            assertFalse(Files.readString(TEMP.resolve("data/embedding.properties")).contains("encryptedQdrantKey"));
            // 비루프백 평문 주소는 명시 허용 없이 저장할 수 없다.
            assertEquals(400, call("/api/embeddings", "PUT", Map.of("vectorStore", "qdrant", "qdrantUrl", "http://example.com:6333"), null, true).statusCode());
        } finally { fake.stop(0); }
    }
    JsonNode mcp(String token, String tool, Map<String, Object> arguments) throws Exception {
        return json(call("/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments)), token, false), 200);
    }
}

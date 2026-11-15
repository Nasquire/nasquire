package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 원격 연결 공통화: 이전 단일 서버 설정 이관, 모델·차원 불일치 거부, 서버별 키 유지·삭제. */
class RemoteSettingsTest {
    @TempDir Path temp;
    private HttpServer server;
    private final AtomicInteger dimensions = new AtomicInteger(2);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", e -> {
            e.getRequestBody().readAllBytes();
            var values = new StringJoiner(",", "[", "]"); for (int i = 0; i < dimensions.get(); i++) values.add(i == 0 ? "1" : "0");
            byte[] body = ("{\"data\":[{\"index\":0,\"embedding\":" + values + "}]}").getBytes();
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/v1/models", e -> {
            byte[] body = "{\"data\":[{\"id\":\"m\"},{\"id\":\"m2\"},{\"id\":\"rerank-m\"}]}".getBytes();
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/v1/rerank", e -> {
            e.getRequestBody().readAllBytes();
            byte[] body = "{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}".getBytes();
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    private static Properties stored(Path file) throws Exception { var p = new Properties(); try (var in = Files.newInputStream(file)) { p.load(in); } return p; }

    @Test void legacyEmbeddingSettingMigratesWithoutReindexOrKeyReentry() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            // 이전 버전 형식(baseUrl·encryptedKey)을 같은 키로 만든다.
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            String encrypted = crypto.encrypt(EmbeddingSettings.EMBEDDING, "legacy-key-value");
            Files.writeString(data.resolve("embedding.properties"), "provider=remote\nbaseUrl=" + url().replace(":", "\\:") + "\nmodel=m\ndimensions=2\nbatchSize=2\ntimeoutSeconds=5\nencryptedKey=" + encrypted.replace(":", "\\:") + "\n");
        }
        try (var index = new SearchIndex(temp.resolve("index2"))) {
            var settings = new EmbeddingSettings(index, data, new MockEnvironment());
            var view = settings.view();
            assertEquals(true, view.get("hasKey"));
            @SuppressWarnings("unchecked") var servers = (List<Map<String, Object>>)view.get("servers");
            assertEquals(1, servers.size()); assertEquals("default", servers.getFirst().get("name")); assertEquals(true, servers.getFirst().get("hasKey"));
            assertEquals(RemoteEmbeddingProvider.legacyModelId(url(), "m"), index.embeddingStatus().modelId(), "기존 색인 모델 ID 유지");
            assertFalse(view.toString().contains("legacy-key-value"));
            // 서버를 추가해도 모델이 같으면 색인 모델 ID는 그대로다. 저장하면 새 형식으로 바뀐다.
            settings.save(Map.of("servers", List.of(Map.of("name", "default", "baseUrl", url()), Map.of("name", "second", "baseUrl", url()))));
            assertEquals(RemoteEmbeddingProvider.legacyModelId(url(), "m"), index.embeddingStatus().modelId());
            var file = stored(data.resolve("embedding.properties"));
            assertFalse(file.containsKey("baseUrl")); assertFalse(file.containsKey("encryptedKey"));
            assertTrue(file.getProperty("server.0.encryptedKey").startsWith("v2:"), "같은 이름·주소는 키 유지");
            assertFalse(file.containsKey("server.1.encryptedKey"));
            assertFalse(Files.readString(data.resolve("embedding.properties")).contains("legacy-key-value"));
            settings.clearServerKey("default");
            assertEquals(false, settings.view().get("hasKey"));
            settings.save(Map.of("model", "m2"));
            assertEquals("remote:m2", index.embeddingStatus().modelId(), "모델이 바뀌면 주소와 무관한 ID");
        }
    }

    @Test void mismatchedDimensionsAreRejectedAndDownServersAllowed() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var settings = new EmbeddingSettings(index, data, new MockEnvironment());
            var input = new HashMap<String, Object>(Map.of("provider", "remote", "model", "m", "dimensions", "2", "batchSize", "1", "timeoutSeconds", "5"));
            input.put("servers", List.of(Map.of("name", "a", "baseUrl", url()), Map.of("name", "offline", "baseUrl", "http://127.0.0.1:9/v1", "timeoutSeconds", "1")));
            settings.save(input);
            assertEquals("remote", settings.view().get("provider"));
            var health = (List<?>)settings.health().get("servers");
            assertEquals("down", ((Map<?, ?>)health.get(1)).get("state"), "연결 불가 서버는 거부하지 않고 down");
            assertFalse(settings.health().toString().contains("127.0.0.1"));
            dimensions.set(3);
            var error = assertThrows(RemoteServers.Rejected.class, () -> settings.save(Map.of("dimensions", "2", "batchSize", "2")));
            assertTrue(error.getMessage().contains("a (dimensions:3)")); assertFalse(error.getMessage().contains("127.0.0.1"));
            assertEquals("1", settings.view().get("batchSize"), "거부된 설정은 게시하지 않음");
            var wrongModel = new HashMap<String, Object>(Map.of("model", "unknown"));
            dimensions.set(2);
            assertTrue(assertThrows(RemoteServers.Rejected.class, () -> settings.save(wrongModel)).getMessage().contains("a (model)"));
        }
    }

    @Test void legacyFullRerankerMigratesToSearchSettingsInLite() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            String encrypted = crypto.encrypt(EmbeddingSettings.RERANKER, "reranker-key-value");
            Files.writeString(data.resolve("full.properties"), "reranker=remote\nbaseUrl=" + url().replace(":", "\\:") + "\nmodel=rerank-m\ntopN=5\ntimeoutSeconds=3\nocr=off\nocrMaxPages=7\nencryptedKey=" + encrypted.replace(":", "\\:") + "\n");
            try (var rerankers = new RerankerSettings(data, crypto)) {
                assertEquals("remote", rerankers.view().get("reranker")); assertEquals(true, rerankers.view().get("hasKey"));
                assertEquals("5", rerankers.view().get("topN"));
                var hits = List.of(new SearchIndex.Hit("s", "S", "s:a", 1, 0, "alpha", 0, 2), new SearchIndex.Hit("s", "S", "s:b", 1, 0, "beta", 0, 1));
                var result = rerankers.rerank("q", hits, true);
                assertTrue(result.applied()); assertEquals("default", result.server()); assertEquals(hits.get(1), result.hits().getFirst());
                assertSame(hits, rerankers.rerank("q", hits, false).hits(), "요청에서 선택하지 않으면 원래 순서");
                assertEquals("ok", rerankers.test("default").get("result"));
            }
            assertEquals(stored(data.resolve("full.properties")).getProperty("encryptedKey"), stored(data.resolve("reranker.properties")).getProperty("server.0.encryptedKey"), "암호문 그대로 이관");
            var full = new FullSettings(data);
            assertFalse(full.view().containsKey("reranker")); assertFalse(full.view().containsKey("ocrMaxPages"), "OCR 선택은 일반 설정으로");
            try (var ocr = new OcrSettings(data, crypto, full)) { assertEquals("7", ocr.view().get("maxPages")); assertEquals("off", ocr.view().get("ocr")); }
            full.save(Map.of("ocrTimeoutSeconds", "20"));
            var cleaned = stored(data.resolve("full.properties"));
            assertFalse(cleaned.containsKey("encryptedKey"), "이관 뒤 이전 리랭커 항목 정리");
            assertFalse(cleaned.containsKey("ocrMaxPages"), "이관 뒤 이전 OCR 항목 정리"); assertEquals("20", cleaned.getProperty("ocrTimeoutSeconds"));
            try (var reloaded = new RerankerSettings(data, crypto)) { assertEquals(true, reloaded.view().get("hasKey")); }
        }
    }
}

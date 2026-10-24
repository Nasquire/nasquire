package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 보안 교차 검토 반영: 평문 HTTP 거부(M5), 주소 변경 시 키 폐기(L2), OCR 변경 시에만 재색인(L3), 키 삭제(L5). */
class ReviewSettingsTest {
    @TempDir Path temp;
    private static boolean full() { return ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent(); }
    private Map<String, String> remote(String url) {
        return new HashMap<>(Map.of("provider", "remote", "baseUrl", url, "model", "m", "dimensions", "2", "batchSize", "2", "timeoutSeconds", "5"));
    }
    private static Properties stored(Path file) throws Exception {
        var p = new Properties(); try (var in = Files.newInputStream(file)) { p.load(in); } return p;
    }

    @Test void embeddingAndQdrantRejectNonLoopbackPlainHttpUnlessAllowed() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var settings = new EmbeddingSettings(index, data, new MockEnvironment());
            var error = assertThrows(IllegalArgumentException.class, () -> settings.save(remote("http://example.com/v1")));
            assertTrue(error.getMessage().contains("HTTPS"));
            var qdrant = remote("https://example.com/v1"); qdrant.put("vectorStore", "qdrant"); qdrant.put("qdrantUrl", "http://example.com:6333");
            assertThrows(IllegalArgumentException.class, () -> settings.save(qdrant));
            assertEquals("off", settings.view().get("provider"), "거부된 설정은 게시하지 않음");
            settings.save(remote("http://127.0.0.1:9/v1"));
            assertEquals(false, settings.view().get("insecureHttp"));
            var allowed = remote("http://example.com/v1"); allowed.put("allowInsecureHttp", "true");
            settings.save(allowed);
            assertEquals(true, settings.view().get("insecureHttp"), "명시 허용 시 화면 경고용 상태");
            assertThrows(IllegalArgumentException.class, () -> settings.save(Map.of("allowInsecureHttp", "yes")));
        }
    }

    @Test void legacyPlainHttpSettingStartsWithVectorsDisabled() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.writeString(data.resolve("embedding.properties"), "provider=remote\nbaseUrl=http\\://example.com/v1\nmodel=m\ndimensions=2\nvectorStore=qdrant\nqdrantUrl=http\\://127.0.0.1\\:6333\n");
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var settings = new EmbeddingSettings(index, data, new MockEnvironment());
            assertFalse(((String)settings.view().get("disabledReason")).isEmpty());
            assertEquals("off", index.embeddingStatus().modelId(), "시작은 하고 벡터 기능만 끔");
            assertThrows(IllegalStateException.class, () -> index.search("x", Set.of("s"), 5, "vector"));
            index.search("x", Set.of("s"), 5, "keyword");
            settings.clearKey(true); // 비활성 상태에서도 키 삭제는 가능하다.
            assertFalse(((String)settings.view().get("disabledReason")).isEmpty());
            settings.save(remote("https://example.com/v1"));
            assertEquals("", settings.view().get("disabledReason"));
        }
    }

    @Test void changingUrlDiscardsStoredKeysAndKeysCanBeDeleted() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var settings = new EmbeddingSettings(index, data, new MockEnvironment());
            var input = remote("https://a.example.com/v1"); input.put("apiKey", "first-key-value");
            input.put("vectorStore", "qdrant"); input.put("qdrantUrl", "https://q.example.com"); input.put("qdrantApiKey", "qdrant-key-value");
            settings.save(input);
            assertEquals(true, settings.view().get("hasKey")); assertEquals(true, settings.view().get("hasQdrantKey"));
            settings.save(Map.of("model", "other"));
            assertEquals(true, settings.view().get("hasKey"), "주소가 같으면 유지");
            settings.save(Map.of("baseUrl", "https://b.example.com/v1"));
            assertEquals(false, settings.view().get("hasKey"), "주소가 바뀌면 이전 키를 새 주소로 보내지 않음");
            assertFalse(stored(data.resolve("embedding.properties")).containsKey("encryptedKey"));
            settings.save(Map.of("qdrantUrl", "https://q2.example.com", "qdrantApiKey", "new-qdrant-key"));
            assertEquals(true, settings.view().get("hasQdrantKey"), "새 키를 함께 입력하면 사용");
            settings.clearKey(true);
            assertEquals(false, settings.view().get("hasQdrantKey"));
            assertFalse(stored(data.resolve("embedding.properties")).containsKey("encryptedQdrantKey"));
            assertFalse(Files.readString(data.resolve("embedding.properties")).contains("new-qdrant-key"));
            // 재시작 경로에서도 삭제된 상태를 유지한다.
            try (var reopened = new SearchIndex(temp.resolve("reopened"))) {
                assertEquals(false, new EmbeddingSettings(reopened, data, new MockEnvironment()).view().get("hasQdrantKey"));
            }

            var fullSettings = new FullSettings(data, settings);
            assertFalse(fullSettings.save(Map.of("baseUrl", "https://r.example.com/v1", "apiKey", "reranker-key-value")), "OCR 무관 변경은 재색인 없음");
            assertEquals(true, fullSettings.view().get("hasKey"));
            assertFalse(fullSettings.save(Map.of("baseUrl", "https://r2.example.com/v1")));
            assertEquals(false, fullSettings.view().get("hasKey"));
            fullSettings.save(Map.of("apiKey", "reranker-key-value"));
            fullSettings.clearKey();
            assertEquals(false, fullSettings.view().get("hasKey"));
            assertFalse(stored(data.resolve("full.properties")).containsKey("encryptedKey"));
            assertTrue(fullSettings.save(Map.of("ocrMaxPages", "5")), "OCR 필드 변경은 재색인");
            assertFalse(fullSettings.save(Map.of("ocrMaxPages", "5")), "같은 값은 재색인 없음");
            // 원격 리랭커 평문 주소는 공급자를 만들기 전에 거부한다.
            assertThrows(IllegalArgumentException.class, () -> fullSettings.save(Map.of("reranker", "remote", "baseUrl", "http://example.com/v1")));
            if (full()) {
                fullSettings.save(Map.of("reranker", "remote", "baseUrl", "http://example.com/v1", "allowInsecureHttp", "true"));
                assertEquals(true, fullSettings.view().get("insecureHttp"));
                fullSettings.save(Map.of("reranker", "off"));
            }
            fullSettings.close();
        }
    }

    @Test void legacyPlainHttpRerankerStartsDisabled() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.writeString(data.resolve("full.properties"), "reranker=remote\nbaseUrl=http\\://example.com/v1\n");
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var full = new FullSettings(data, new EmbeddingSettings(index, data, new MockEnvironment()));
            assertFalse(((String)full.view().get("disabledReason")).isEmpty());
            var hits = List.of(new SearchIndex.Hit("s", "S", "s:a", 1, 0, "a", 0, 1), new SearchIndex.Hit("s", "S", "s:b", 1, 0, "b", 0, 0.5f));
            assertEquals(hits, full.rerank("q", hits, true), "비활성 리랭커는 원래 순서");
            full.close();
        }
    }
}

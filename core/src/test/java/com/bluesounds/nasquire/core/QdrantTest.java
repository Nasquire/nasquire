package com.bluesounds.nasquire.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QdrantTest {
    @TempDir Path temp;
    private static Providers.EmbeddingProvider provider(String id, int dimensions) {
        return new Providers.EmbeddingProvider() {
            public String modelId() { return id; }
            public int dimensions() { return dimensions; }
            public float[] embed(String text) { var v = new float[dimensions]; v[text.contains("sea") ? 0 : 1] = 1; v[dimensions - 1] += 0.01f; return v; }
        };
    }
    private record Fixture(SearchIndex index, Source a, Source b) { }
    private Fixture indexed(FakeQdrant qdrant, Path folder) throws Exception {
        Path root = Files.createDirectories(temp.resolve("roots"));
        var a = new Source("a", "A", root, List.of(), List.of()); var b = new Source("b", "B", root, List.of(), List.of());
        var index = new SearchIndex(folder);
        index.configureVectors(provider("model", 2), new QdrantVectorStore(qdrant.url(), "docs", "test-only-key", 5));
        index.requestRebuild(); assertTrue(index.beginRebuild());
        index.replace(a, "sea.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea in scope")));
        index.replace(b, "secret.txt", 1, List.of(new SearchIndex.Chunk(4, 0, "sea outside scope confidential"), new SearchIndex.Chunk(5, 1200, "sea second confidential")));
        index.finishRebuild(true); index.refresh();
        return new Fixture(index, a, b);
    }

    @Test void vectorSearchUsesServerFilterAndNeverReturnsOtherSources() throws Exception {
        try (var qdrant = new FakeQdrant("test-only-key")) {
            var f = indexed(qdrant, temp.resolve("index"));
            try (var index = f.index()) {
                assertEquals(3, qdrant.points("docs"));
                assertEquals(Set.of("a", "b"), qdrant.payloadValues("docs", "source"));
                assertTrue(qdrant.requests.stream().allMatch(r -> "test-only-key".equals(r.apiKey())));
                // 본문은 Qdrant로 보내지 않는다.
                assertTrue(qdrant.requests.stream().noneMatch(r -> r.body().contains("confidential")));
                var hits = index.search("sea", Set.of("a"), 10, "vector");
                assertEquals(List.of("a:sea.txt"), hits.stream().map(SearchIndex.Hit::path).toList());
                var query = qdrant.requests("/points/query").getLast().body();
                assertTrue(query.contains("\"filter\":{\"must\":[{\"key\":\"source\",\"match\":{\"any\":[\"a\"]}},{\"key\":\"epoch\",\"match\":{\"value\":"), query);
                // 서버가 필터를 무시해도 앱이 범위 밖 조각을 버린다.
                qdrant.ignoreFilter = true;
                assertEquals(List.of("a:sea.txt"), index.search("sea", Set.of("a"), 10, "vector").stream().map(SearchIndex.Hit::path).toList());
                assertTrue(index.search("sea", Set.of("a"), 10, "hybrid").stream().allMatch(h -> h.source().equals("a")));
                assertTrue(index.search("sea", Set.of(), 10, "vector").isEmpty());
                int queries = qdrant.requests("/points/query").size();
                assertTrue(index.search("sea", Set.of(), 10, "hybrid").isEmpty());
                assertEquals(queries, qdrant.requests("/points/query").size(), "빈 범위는 외부 요청 없음");
                qdrant.ignoreFilter = false;
                assertEquals(2, index.search("sea", Set.of("a", "b"), 10, "vector").stream().map(SearchIndex.Hit::path).distinct().count());
            }
        }
    }
    @Test void deleteReindexAndStoreChangeSynchronizePoints() throws Exception {
        try (var qdrant = new FakeQdrant("test-only-key")) {
            Path folder = temp.resolve("index");
            var f = indexed(qdrant, folder);
            try (var index = f.index()) {
                index.delete("b:secret.txt"); index.refresh();
                assertEquals(Set.of("a:sea.txt"), qdrant.payloadValues("docs", "key"));
                index.replace(f.b(), "other.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea other")));
                index.replace(f.b(), "other.txt", 2, List.of(new SearchIndex.Chunk(1, 0, "sea changed")));
                assertEquals(2, qdrant.points("docs"), "같은 key의 이전 점 교체");
                index.deleteSource("b"); index.refresh();
                assertEquals(Set.of("a"), qdrant.payloadValues("docs", "source"));
                // 실패 응답 본문·키를 예외에 남기지 않는다.
                qdrant.fail = true;
                var error = assertThrows(java.io.IOException.class, () -> index.search("sea", Set.of("a"), 5, "vector"));
                assertFalse(error.toString().contains("secret-qdrant-body")); assertFalse(error.toString().contains("test-only-key"));
                assertThrows(java.io.IOException.class, () -> index.replace(f.a(), "new.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea new"))));
                index.refresh();
                assertTrue(index.search("new", Set.of("a"), 5, "keyword").isEmpty(), "외부 저장 실패 시 Lucene 미게시");
                qdrant.fail = false;
                // 저장소를 Lucene으로 바꾸면 재색인 전까지 벡터 검색을 막는다.
                index.configureVectors(provider("model", 2), null);
                assertTrue(index.embeddingStatus().reindexRequired());
                assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("a"), 5, "vector"));
            }
            try (var index = new SearchIndex(folder)) {
                index.configureVectors(provider("model", 2), new QdrantVectorStore(qdrant.url(), "docs", "test-only-key", 5));
                assertFalse(index.embeddingStatus().reindexRequired());
                assertEquals("qdrant", index.embeddingStatus().indexedVectorStore());
                index.configureVectors(provider("model", 2), new QdrantVectorStore(qdrant.url(), "other", "test-only-key", 5));
                assertTrue(index.embeddingStatus().reindexRequired(), "컬렉션 변경");
                assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("a"), 5, "vector"));
            }
            try (var index = new SearchIndex(folder)) {
                index.configureVectors(provider("model-3d", 3), new QdrantVectorStore(qdrant.url(), "docs", "test-only-key", 5));
                assertTrue(index.embeddingStatus().reindexRequired(), "차원 변경");
                index.requestRebuild(); assertTrue(index.beginRebuild());
                assertEquals(0, qdrant.points("docs"), "재색인 시작 시 컬렉션 재생성");
                index.replace(f.a(), "sea.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea again")));
                index.finishRebuild(true); index.refresh();
                assertEquals(1, index.search("sea", Set.of("a"), 5, "vector").size());
            }
        }
    }
    @Test void wrongKeyAndInvalidSettingsAreRejected() throws Exception {
        try (var qdrant = new FakeQdrant("right-key"); var store = new QdrantVectorStore(qdrant.url(), "docs", "wrong-key", 2)) {
            var error = assertThrows(java.io.IOException.class, () -> store.reset(2));
            assertFalse(error.toString().contains("wrong-key"));
        }
        assertThrows(IllegalArgumentException.class, () -> new QdrantVectorStore("ftp://example.com", "docs", "", 2));
        assertThrows(IllegalArgumentException.class, () -> new QdrantVectorStore("https://user:pw@example.com", "docs", "", 2));
        assertThrows(IllegalArgumentException.class, () -> new QdrantVectorStore("https://example.com", "../x", "", 2));
        assertThrows(IllegalArgumentException.class, () -> new QdrantVectorStore("https://example.com", "docs", "bad\nkey", 2));
    }
}

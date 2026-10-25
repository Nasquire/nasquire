package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 보안 교차 검토 반영 회귀 테스트(M1·M3·M5). */
class ReviewFixesTest {
    @TempDir Path temp;
    private static Providers.EmbeddingProvider provider() {
        return new Providers.EmbeddingProvider() {
            public String modelId() { return "model"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { var v = new float[2]; v[text.contains("sea") ? 0 : 1] = 1; v[1] += 0.01f; return v; }
        };
    }

    @Test void scheduledScanKeepsRunningAfterThrowable() throws Exception {
        try (var index = new SearchIndex(temp.resolve("index"));
             var indexing = new IndexingService(index, 1 << 20, 1000, 0, List.of(new PlainTextExtractor()))) {
            var calls = new AtomicInteger(); var observed = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            indexing.start(() -> {
                int n = calls.incrementAndGet();
                if (n == 1) throw new OutOfMemoryError("test");
                if (n == 2) observed.set(indexing.status("_system").state() + "/" + indexing.status("_system").message());
                // 이후 호출은 색인 입출력 전에 끝내 종료 시 중단 신호가 Lucene 커밋과 겹치지 않게 한다.
                if (n >= 3) { done.countDown(); throw new IllegalStateException("test"); }
                return List.of();
            }, 20);
            assertTrue(done.await(10, TimeUnit.SECONDS), "Error 뒤에도 다음 주기 실행");
            assertEquals("error/검사 실패: 메모리 부족", observed.get());
        }
    }

    @Test void outOfMemoryInOneFileOnlyFailsThatFile() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        Files.writeString(root.resolve("good.txt"), "good"); Files.writeString(root.resolve("bad.txt"), "bad");
        TextExtractor extractor = new TextExtractor() {
            public boolean supports(Path path) { return path.toString().endsWith(".txt"); }
            public void extract(Path path, PageConsumer consumer, int max) throws IOException {
                if (path.getFileName().toString().equals("bad.txt")) throw new OutOfMemoryError("test");
                consumer.accept(1, "good text");
            }
            // 색인기는 내용을 고정 fd 경로로 넘기므로 이름 기반 판단은 원래 이름을 받는 메서드에서 한다.
            @Override public void extractLocated(Path name, Path content, LocatedConsumer consumer, int max) throws IOException {
                if (name.getFileName().toString().equals("bad.txt")) throw new OutOfMemoryError("test");
                extractLocated(content, consumer, max);
            }
        };
        try (var index = new SearchIndex(temp.resolve("index"));
             var indexing = new IndexingService(index, 1 << 20, 1000, 0, List.of(extractor))) {
            indexing.scan(List.of(new Source("s", "S", root, List.of(), List.of())));
            assertEquals(1, indexing.status("s").indexed()); assertEquals(1, indexing.status("s").errors());
            assertEquals(1, index.search("good", Set.of("s"), 5).size());
        }
    }

    @Test void recentFailuresAreBoundedRelativeAndDoNotExposeExceptionMessages() throws Exception {
        Path root = Files.createDirectories(temp.resolve("failures"));
        for (int i = 0; i < 7; i++) Files.writeString(root.resolve("bad-" + i + ".txt"), "input");
        TextExtractor extractor = new TextExtractor() {
            public boolean supports(Path path) { return true; }
            public void extract(Path path, PageConsumer consumer, int max) throws IOException {
                throw new IOException("private-path-and-document-content");
            }
        };
        try (var index = new SearchIndex(temp.resolve("failure-index"));
             var indexing = new IndexingService(index, 1 << 20, 1000, 0, List.of(extractor))) {
            indexing.scan(List.of(new Source("s", "S", root, List.of(), List.of())));
            var status = indexing.status("s");
            assertEquals(7, status.errors()); assertEquals(5, status.recentFailures().size());
            for (var failure : status.recentFailures()) {
                assertTrue(failure.path().matches("bad-[0-6]\\.txt"));
                assertEquals("IOException", failure.reason());
            }
            assertThrows(UnsupportedOperationException.class, () -> status.recentFailures().clear());
        }
        var failure = new ExtractionFailure("render", 3, false, "java.lang.OutOfMemoryError");
        assertTrue(failure.getMessage().contains("exit=3, timeout=false"));
        assertFalse(new ExtractionFailure("/private", 1, false, "secret content").getMessage().contains("secret"));
    }

    @Test void slowQdrantDoesNotHoldIndexLock() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        var a = new Source("a", "A", root, List.of(), List.of());
        try (var qdrant = new FakeQdrant(null); var index = new SearchIndex(temp.resolve("index"))) {
            index.configureVectors(provider(), new QdrantVectorStore(qdrant.url(), "docs", "", 10));
            index.requestRebuild(); assertTrue(index.beginRebuild());
            index.replace(a, "sea.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea in scope")));
            index.finishRebuild(true); index.refresh();
            qdrant.delayMillis = 1500;
            var pool = Executors.newFixedThreadPool(2);
            try {
                var vector = pool.submit(() -> index.search("sea", Set.of("a"), 5, "vector"));
                var write = pool.submit(() -> { index.replace(a, "slow.txt", 2, List.of(new SearchIndex.Chunk(1, 0, "sea slow"))); return null; });
                Thread.sleep(300);
                long start = System.nanoTime();
                assertEquals(1, index.search("sea", Set.of("a"), 5, "keyword").size());
                assertFalse(index.embeddingStatus().reindexRequired());
                assertTrue(System.nanoTime() - start < 500_000_000L, "느린 Qdrant 중 keyword 검색·상태 조회가 막히지 않음");
                assertFalse(vector.isDone()); assertFalse(write.isDone());
                assertEquals(1, vector.get(10, TimeUnit.SECONDS).size());
                write.get(10, TimeUnit.SECONDS); index.refresh();
                assertEquals(2, index.search("sea", Set.of("a"), 5, "keyword").size());
                // 원격 호출 중 설정이 바뀌면 이전 세대 결과를 쓰지 않는다.
                var stale = pool.submit(() -> index.search("sea", Set.of("a"), 5, "vector"));
                Thread.sleep(300);
                index.configureVectors(provider(), new QdrantVectorStore(qdrant.url(), "other", "", 10));
                var error = assertThrows(ExecutionException.class, () -> stale.get(10, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, error.getCause());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void qdrantCircuitBreakerAndOperationDeadline() throws Exception {
        long previous = QdrantVectorStore.openMillis;
        QdrantVectorStore.openMillis = 300;
        try (var qdrant = new FakeQdrant(null); var store = new QdrantVectorStore(qdrant.url(), "docs", "", 1)) {
            qdrant.fail = true;
            for (int i = 0; i < QdrantVectorStore.FAILURES; i++) assertThrows(IOException.class, () -> store.search(new float[] {1, 0}, Set.of("a"), 5, 0));
            int before = qdrant.requests.size();
            var open = assertThrows(IOException.class, () -> store.search(new float[] {1, 0}, Set.of("a"), 5, 0));
            assertTrue(open.getMessage().contains("차단")); assertThrows(IOException.class, () -> store.deleteKey("a:x"));
            assertEquals(before, qdrant.requests.size(), "차단 중에는 원격 요청 없음");
            Thread.sleep(400); qdrant.fail = false;
            store.reset(2);
            assertTrue(store.search(new float[] {1, 0}, Set.of("a"), 5, 0).isEmpty(), "차단 시간 뒤 복구");
            // 응답이 늦어도 작업 전체 상한(설정 1초)에서 끝난다.
            qdrant.delayMillis = 3000;
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> store.search(new float[] {1, 0}, Set.of("a"), 5, 0));
            assertTrue(System.nanoTime() - start < 2_500_000_000L);
        } finally { QdrantVectorStore.openMillis = previous; }
    }

    @Test void plainHttpOnlyForLoopbackUnlessAllowed() {
        for (String url : List.of("https://example.com", "http://127.0.0.1:6333", "http://localhost/v1", "http://[::1]:8080", "http://127.1.2.3"))
            assertFalse(RemoteUrl.insecure(url), url);
        for (String url : List.of("http://example.com", "http://10.0.0.5:6333", "http://127.0.0.1.example.com", "HTTP://example.com"))
            assertTrue(RemoteUrl.insecure(url), url);
        assertThrows(IllegalArgumentException.class, () -> RemoteUrl.require("http://example.com/v1", false));
        RemoteUrl.require("http://example.com/v1", true);
    }
}

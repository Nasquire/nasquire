package com.bluesounds.nasquire.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 재검토 반영: SearchIndex 종료 수명(R2), 삭제와 원격 쓰기 경쟁·세대 필터(R3). */
class RereviewFixesTest {
    @TempDir Path temp;

    /** embedBatch에서 release까지 멈추는 공급자. 사용 중 close를 기록한다. */
    static final class BlockingProvider implements Providers.EmbeddingProvider {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger inUse = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean(), closedInUse = new AtomicBoolean();
        volatile boolean block;
        public String modelId() { return "model"; }
        public int dimensions() { return 2; }
        public float[] embed(String text) { return new float[] {1, 0.01f}; }
        public List<float[]> embedBatch(List<String> texts) {
            inUse.incrementAndGet();
            try {
                if (block) {
                    entered.countDown();
                    try { release.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("중단"); }
                }
                return texts.stream().map(this::embed).toList();
            } finally { inUse.decrementAndGet(); }
        }
        public void close() { if (inUse.get() > 0) closedInUse.set(true); closed.set(true); }
    }

    @Test void closeWaitsForRemoteCallBeforeClosingProviders() throws Exception {
        var provider = new BlockingProvider();
        var index = new SearchIndex(temp.resolve("index"));
        var source = new Source("a", "A", temp, List.of(), List.of());
        index.configureEmbedding(provider);
        index.requestRebuild(); assertTrue(index.beginRebuild());
        provider.block = true;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var write = pool.submit(() -> { index.replace(source, "x.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea"))); return null; });
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            var closing = pool.submit(() -> { index.close(); return null; });
            Thread.sleep(300);
            assertFalse(closing.isDone(), "원격 호출이 끝날 때까지 닫지 않음");
            assertFalse(provider.closed.get());
            // 종료가 시작되면 새 작업은 명확한 예외로 거부한다.
            var refused = assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("a"), 5, "keyword"));
            assertTrue(refused.getMessage().contains("종료"));
            assertThrows(IllegalStateException.class, () -> index.replace(source, "y.txt", 1, List.of()));
            provider.release.countDown();
            var error = assertThrows(ExecutionException.class, () -> write.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause(), "종료 중 결과는 게시하지 않음");
            closing.get(10, TimeUnit.SECONDS);
            assertTrue(provider.closed.get()); assertFalse(provider.closedInUse.get(), "사용 중 닫기 없음");
            assertThrows(IllegalStateException.class, () -> index.document("a:x.txt", Set.of("a")));
            assertThrows(IllegalStateException.class, index::refresh);
            index.close(); // 두 번 닫아도 안전
        } finally { provider.release.countDown(); pool.shutdownNow(); }
    }

    @Test void closeTimeoutCancelsRemoteCallThenCloses() throws Exception {
        var provider = new BlockingProvider();
        var index = new SearchIndex(temp.resolve("index"));
        index.closeWaitMillis = 200;
        index.configureEmbedding(provider);
        index.requestRebuild(); assertTrue(index.beginRebuild());
        provider.block = true;
        var pool = Executors.newSingleThreadExecutor();
        try {
            var write = pool.submit(() -> { index.replace(new Source("a", "A", temp, List.of(), List.of()), "x.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea"))); return null; });
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            index.close();
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "제한 시간 뒤 원격 호출 취소");
            assertThrows(ExecutionException.class, () -> write.get(5, TimeUnit.SECONDS));
            assertTrue(provider.closed.get()); assertFalse(provider.closedInUse.get(), "취소된 호출이 끝난 뒤 닫음");
        } finally { provider.release.countDown(); pool.shutdownNow(); }
    }

    @Test void deleteDuringEmbeddingWinsAndLeavesNoQdrantPoints() throws Exception {
        var provider = new BlockingProvider();
        var source = new Source("a", "A", temp, List.of(), List.of());
        try (var qdrant = new FakeQdrant(null); var index = new SearchIndex(temp.resolve("index"))) {
            index.configureVectors(provider, new QdrantVectorStore(qdrant.url(), "docs", "", 10));
            index.requestRebuild(); assertTrue(index.beginRebuild());
            index.replace(source, "keep.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea keep")));
            index.finishRebuild(true); index.refresh();
            var pool = Executors.newFixedThreadPool(2);
            try {
                for (boolean wholeSource : new boolean[] {false, true}) {
                    var p2 = new BlockingProvider(); p2.block = true;
                    index.configureVectors(p2, new QdrantVectorStore(qdrant.url(), "docs", "", 10));
                    assertFalse(index.embeddingStatus().reindexRequired());
                    var write = pool.submit(() -> { index.replace(source, "x.txt", 2, List.of(new SearchIndex.Chunk(1, 0, "sea x"), new SearchIndex.Chunk(1, 9, "sea y"))); return null; });
                    assertTrue(p2.entered.await(5, TimeUnit.SECONDS));
                    if (wholeSource) index.deleteSource("a"); else index.delete("a:x.txt");
                    p2.release.countDown();
                    var error = assertThrows(ExecutionException.class, () -> write.get(10, TimeUnit.SECONDS));
                    assertTrue(error.getCause().getMessage().contains("삭제"), error.getCause().getMessage());
                    index.refresh();
                    assertTrue(index.document("a:x.txt", Set.of("a")).isEmpty(), "삭제가 게시로 되돌아가지 않음");
                    assertFalse(qdrant.payloadValues("docs", "key").contains("a:x.txt"), "이미 쓴 Qdrant 점도 정리");
                    p2.block = false;
                }
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void previousEpochPointsAreIgnoredAndOldQdrantIndexNeedsReindex() throws Exception {
        try (var qdrant = new FakeQdrant(null); var store = new QdrantVectorStore(qdrant.url(), "docs", "", 5)) {
            store.reset(2);
            store.replace("a:x", List.of(new VectorStore.Point("a", "a:x", 0, new float[] {1, 0}, 3)), 2);
            assertEquals(1, store.search(new float[] {1, 0}, Set.of("a"), 5, 3).size());
            assertTrue(store.search(new float[] {1, 0}, Set.of("a"), 5, 4).isEmpty(), "현재 세대 점만 허용");
            qdrant.ignoreFilter = true;
            assertTrue(store.search(new float[] {1, 0}, Set.of("a"), 5, 4).isEmpty(), "서버가 필터를 무시해도 앱이 다시 거름");
        }
        // 세대 기록이 없는 기존 Qdrant 색인은 한 번 재색인해야 한다.
        Path folder = temp.resolve("old-index");
        try (var directory = org.apache.lucene.store.FSDirectory.open(folder);
             var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig())) {
            writer.setLiveCommitData(Map.of("embedding.model", "model", "embedding.dimensions", "2", "vector.store", "qdrant:http://127.0.0.1:1/docs").entrySet());
            writer.commit();
        }
        try (var index = new SearchIndex(folder)) { assertTrue(index.embeddingStatus().reindexRequired()); }
    }

    @Test void staleOcrScratchIsCleanedWithoutFollowingLinks() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data")), scratch = Files.createDirectories(OcrScratch.folder(data));
        Path outside = Files.writeString(Files.createDirectories(temp.resolve("outside")).resolve("keep.txt"), "keep");
        Path oldPage = Files.createDirectories(scratch.resolve("page-old")), oldJob = Files.createDirectories(scratch.resolve("job-old"));
        Files.writeString(oldPage.resolve("input.png"), "x"); Files.writeString(oldJob.resolve("out-1.png"), "x");
        Files.createSymbolicLink(oldJob.resolve("link"), outside);
        Path fresh = Files.createDirectories(scratch.resolve("job-new")), other = Files.createDirectories(scratch.resolve("other"));
        var old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25));
        for (Path p : List.of(oldPage, oldJob, other)) Files.setLastModifiedTime(p, old);
        OcrScratch.cleanStale(data);
        assertFalse(Files.exists(oldPage)); assertFalse(Files.exists(oldJob, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(fresh), "진행 중일 수 있는 새 작업 폴더 유지"); assertTrue(Files.exists(other), "다른 이름은 건드리지 않음");
        assertEquals("keep", Files.readString(outside), "링크 대상은 지우지 않음");
    }
}

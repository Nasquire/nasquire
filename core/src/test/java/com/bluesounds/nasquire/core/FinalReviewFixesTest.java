package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 마지막 보안 잔여: 색인기의 경로 교체 경쟁(S2)·FIFO 교체(S1), 중단을 무시하는 공급자 사용 중 닫기(S3). */
class FinalReviewFixesTest {
    @TempDir Path temp;

    /** 추출 도중 중간 디렉터리를 소스 밖 링크로 바꿔 받은 경로를 읽은 뒤 되돌린다(검사 뒤 교체·사후 검사 전 복구를 흉내 낸다). */
    static final class SwappingExtractor implements TextExtractor {
        final Path folder, outside;
        final AtomicInteger swaps = new AtomicInteger();
        SwappingExtractor(Path folder, Path outside) { this.folder = folder; this.outside = outside; }
        public boolean supports(Path path) { return path.toString().endsWith(".txt"); }
        public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException { new PlainTextExtractor().extract(path, consumer, maxCharacters); }
        @Override public void extractLocated(Path name, Path content, LocatedConsumer consumer, int maxCharacters) throws IOException {
            Path moved = folder.resolveSibling(folder.getFileName() + "-moved");
            Files.move(folder, moved);
            Files.createSymbolicLink(folder, outside);
            swaps.incrementAndGet();
            try { extractLocated(content, consumer, maxCharacters); }
            finally { Files.delete(folder); Files.move(moved, folder); }
        }
    }

    @Test void parentDirectorySwapDoesNotIndexOutsideContent() throws Exception {
        Assumptions.assumeTrue(SecureFiles.supported());
        Path root = Files.createDirectories(temp.resolve("source")), folder = Files.createDirectories(root.resolve("dir"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(folder.resolve("doc.txt"), "inside harbor");
        Files.writeString(outside.resolve("doc.txt"), "outside confidential");
        var extractor = new SwappingExtractor(folder, outside);
        var source = new Source("s", "S", root, List.of(), List.of());
        try (var index = new SearchIndex(temp.resolve("index")); var service = new IndexingService(index, 1 << 20, 10_000, 0, List.of(extractor))) {
            service.scan(List.of(source));
            assertEquals(1, extractor.swaps.get(), "추출 중 교체 발생");
            assertTrue(index.search("confidential", Set.of("s"), 10).isEmpty(), "소스 밖 내용 미색인");
            assertEquals(1, index.search("harbor", Set.of("s"), 10).size(), "고정한 원래 파일 내용만 색인");
        }
    }

    @Test void fifoAndLinkedParentsAreRejectedWithoutBlocking() throws Exception {
        Assumptions.assumeTrue(SecureFiles.supported());
        Path fifo = temp.resolve("pipe.txt");
        assertEquals(0, new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start().waitFor());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> assertThrows(IOException.class, () -> SecureFiles.open(fifo)));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("x.txt"), "secret");
        Files.createSymbolicLink(temp.resolve("linked"), outside);
        assertThrows(IOException.class, () -> SecureFiles.open(temp.resolve("linked/x.txt")), "중간 디렉터리 링크 거부");
        try (var pinned = SecureFiles.open(outside.resolve("x.txt"))) {
            assertEquals("secret", Files.readString(pinned.path()));
            // 고정 경로는 경로 교체와 무관하게 같은 inode를 연다.
            Files.move(outside.resolve("x.txt"), outside.resolve("y.txt"));
            Files.writeString(outside.resolve("x.txt"), "replaced");
            assertEquals("secret", Files.readString(pinned.path()));
            try (var again = SecureFiles.open(pinned.path())) { assertEquals(pinned.inode(), again.inode()); }
        }
    }

    /** 중단에 응하지 않고 release까지 멈추는 공급자. */
    static final class StubbornProvider implements Providers.EmbeddingProvider {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger inUse = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean(), closedInUse = new AtomicBoolean(), interrupted = new AtomicBoolean();
        volatile boolean block;
        public String modelId() { return "model"; }
        public int dimensions() { return 2; }
        public float[] embed(String text) { return new float[] {1, 0.01f}; }
        public synchronized List<float[]> embedBatch(List<String> texts) {
            inUse.incrementAndGet();
            try {
                if (block) {
                    entered.countDown();
                    while (true) {
                        try { if (release.await(30, TimeUnit.SECONDS)) break; }
                        catch (InterruptedException e) { interrupted.set(true); }
                    }
                }
                return texts.stream().map(this::embed).toList();
            } finally { inUse.decrementAndGet(); }
        }
        public synchronized void close() { if (inUse.get() > 0) closedInUse.set(true); closed.set(true); }
    }

    @Test void closeDefersProviderCloseWhileInterruptIgnoringCallRuns() throws Exception {
        var provider = new StubbornProvider();
        var index = new SearchIndex(temp.resolve("index"));
        index.closeWaitMillis = 100;
        index.configureEmbedding(provider);
        index.requestRebuild(); assertTrue(index.beginRebuild());
        provider.block = true;
        var pool = Executors.newSingleThreadExecutor();
        try {
            var write = pool.submit(() -> { index.replace(new Source("a", "A", temp, List.of(), List.of()), "x.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "sea"))); return null; });
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            index.close();
            assertTrue(System.nanoTime() - start < 5_000_000_000L, "종료는 두 번째 대기 뒤 반환");
            assertTrue(provider.interrupted.get(), "중단 신호 전달");
            assertFalse(provider.closed.get(), "사용 중인 공급자는 닫지 않음");
            assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("a"), 5, "keyword"));
            provider.release.countDown();
            var error = assertThrows(ExecutionException.class, () -> write.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause(), "종료 뒤 결과는 게시하지 않음");
            assertTrue(provider.closed.get(), "마지막 활성 작업 종료 때 닫음");
            assertFalse(provider.closedInUse.get(), "사용 중 닫기 없음");
        } finally { provider.release.countDown(); pool.shutdownNow(); }
    }
}

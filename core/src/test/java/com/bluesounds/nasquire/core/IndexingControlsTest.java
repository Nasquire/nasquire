package com.bluesounds.nasquire.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IndexingControlsTest {
    @TempDir Path temp;
    @Test void oversizedFilesRetryAfterLimitIncrease() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source"));
        for (int i = 0; i < 7; i++) Files.writeString(root.resolve(i + ".txt"), "searchable content");
        var source = new Source("s", "S", root, List.of(), List.of());
        try (var index = new SearchIndex(temp.resolve("index"));
             var service = new IndexingService(index, 1, 1000, 0, List.of(new PlainTextExtractor()))) {
            service.scan(List.of(source));
            assertEquals(7, service.status("s").oversized());
            assertEquals(0, service.status("s").errors());
            assertEquals(5, service.status("s").recentOversized().size());
            assertTrue(service.status("s").recentOversized().stream().noneMatch(x -> x.contains("/")));
            service.maxBytes(1024); service.scan(List.of(source));
            assertEquals(7, service.status("s").indexed());
            assertEquals(0, service.status("s").oversized());
            assertEquals(7, index.search("searchable", Set.of("s"), 10).size());
        }
    }
    @Test void manualRequestsCoalesceAndRescheduleImmediately() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var index = new SearchIndex(temp.resolve("index"));
             var service = new IndexingService(index, 1024, 1000, 0, List.of())) {
            service.start(() -> {
                int n = calls.incrementAndGet();
                if (n == 1) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                return List.of();
            }, 3600000);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) service.scanNow("s");
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (calls.get() < 2 && System.nanoTime() < until) Thread.sleep(10);
            Thread.sleep(100); assertEquals(2, calls.get());
            service.reschedule(60000);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (calls.get() < 3 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(3, calls.get());
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test void longIntervalStartupNewFileReindexAndRestart() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source"));
        Files.writeString(root.resolve("first.txt"), "ocean first");
        var source = new Source("s", "S", root, List.of(), List.of());
        for (int run = 0; run < 2; run++) {
            try (var index = new SearchIndex(temp.resolve("index"));
                 var service = new IndexingService(index, 1024, 1000, 2000, List.of(new PlainTextExtractor()))) {
                service.start(() -> List.of(source), 3600000);
                await(() -> service.status("s").indexed() >= 1);
                assertFalse(index.search("ocean", Set.of("s"), 10).isEmpty());
                if (run == 0) {
                    Files.writeString(root.resolve("new.txt"), "ocean added");
                    service.scanNow("s");
                    await(() -> service.status("s").indexed() == 2);
                    service.reindex("s");
                    await(() -> service.status("s").pending() == 2);
                    assertEquals(2, index.search("ocean", Set.of("s"), 10).size());
                    await(() -> service.status("s").indexed() == 2);
                    index.requestRebuild(); service.scanNow("s");
                    await(() -> index.embeddingStatus().rebuilding());
                    await(() -> !index.embeddingStatus().rebuilding() && service.status("s").indexed() == 2);
                    assertEquals(2, index.search("ocean", Set.of("s"), 10).size());
                    Files.delete(root.resolve("new.txt"));
                    service.reindex("s");
                    await(() -> service.status("s").indexed() == 1 && service.status("s").pending() == 0);
                    assertEquals(1, index.search("ocean", Set.of("s"), 10).size());
                }
            }
        }
    }
    @Test void changingFileDoesNotBlockOthersAndFollowUpsStopAtTen() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source"));
        Files.writeString(root.resolve("steady.txt"), "ocean steady");
        Path changing = Files.writeString(root.resolve("changing.txt"), "changing");
        var visits = new AtomicInteger();
        TextExtractor extractor = new TextExtractor() {
            public boolean supports(Path path) {
                if (path.equals(changing)) {
                    try { Files.writeString(path, "changing".repeat(visits.incrementAndGet())); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                return true;
            }
            public void extract(Path path, PageConsumer consumer, int max) throws java.io.IOException {
                consumer.accept(1, Files.readString(path));
            }
        };
        var source = new Source("s", "S", root, List.of(), List.of());
        try (var index = new SearchIndex(temp.resolve("index"));
             var service = new IndexingService(index, 10000, 1000, 30, List.of(extractor))) {
            service.start(() -> List.of(source), 3600000);
            await(() -> visits.get() == 11);
            Thread.sleep(200);
            assertEquals(11, visits.get());
            assertEquals(1, service.status("s").pending());
            assertEquals(1, index.search("ocean", Set.of("s"), 10).size());
            service.scanNow("s");
            await(() -> visits.get() == 22);
            Thread.sleep(200);
            assertEquals(22, visits.get());
        }
    }
    @Test void selectedRebuildCompletesAllSourcesAndCleansChangedScope() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Files.writeString(first.resolve("a.txt"), "ocean first");
        Files.writeString(second.resolve("b.txt"), "ocean second");
        var sources = new AtomicReference<>(List.of(
                new Source("a", "A", first, List.of(), List.of()),
                new Source("b", "B", second, List.of(), List.of())));
        try (var index = new SearchIndex(temp.resolve("index"));
             var service = new IndexingService(index, 1024, 1000, 100, List.of(new PlainTextExtractor()))) {
            service.start(sources::get, 3600000);
            await(() -> service.status("a").indexed() == 1 && service.status("b").indexed() == 1);
            index.requestRebuild(); service.scanNow("a");
            await(() -> index.embeddingStatus().rebuilding());
            await(() -> !index.embeddingStatus().rebuilding());
            assertEquals(2, index.search("ocean", Set.of("a", "b"), 10).size());
            sources.set(List.of(new Source("a", "A", first, List.of(), List.of("**/*.txt"))));
            service.reindex("a");
            await(() -> service.status("a").indexed() == 0 && service.status("b").state().equals("waiting"));
            assertTrue(index.search("ocean", Set.of("a", "b"), 10).isEmpty());
        }
    }
}

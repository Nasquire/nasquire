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
}

package com.bluesounds.nasquire.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleHeapReclaimerTest {
    @Test void coalescesAndLimitsReclamationAndCancelsOnClose() throws Exception {
        var worker = Executors.newSingleThreadScheduledExecutor();
        var calls = new AtomicInteger();
        var first = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var reclaimer = new IdleHeapReclaimer(worker, () -> {
            if (calls.incrementAndGet() == 1) first.countDown(); else second.countDown();
        }, 50, 500);
        try {
            // 같은 작업자가 바쁜 동안 여러 추출이 끝나도 한 번만 실행한다.
            var queued = new CountDownLatch(1);
            worker.submit(() -> {
                for (int i = 0; i < 100; i++) reclaimer.afterExtraction();
                queued.countDown();
            });
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            assertTrue(first.await(3, TimeUnit.SECONDS));
            reclaimer.afterExtraction();
            assertFalse(second.await(100, TimeUnit.MILLISECONDS));
            assertTrue(second.await(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            reclaimer.afterExtraction(); reclaimer.cancel();
            worker.shutdown();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally { worker.shutdownNow(); }
    }
}

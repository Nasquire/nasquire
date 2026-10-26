package com.bluesounds.nasquire.core;

import java.util.concurrent.*;

/** 실제 추출 뒤 단일 색인 작업자가 유휴일 때만 SerialGC의 힙 반환을 요청한다. */
final class IdleHeapReclaimer {
    private final ScheduledExecutorService worker;
    private final Runnable reclaim;
    private final long delayMillis;
    private final long intervalNanos;
    private long lastRun;
    private ScheduledFuture<?> pending;

    IdleHeapReclaimer(ScheduledExecutorService worker, Runnable reclaim, long delayMillis, long intervalMillis) {
        this.worker = worker; this.reclaim = reclaim; this.delayMillis = delayMillis;
        intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        lastRun = System.nanoTime() - intervalNanos;
    }

    synchronized void afterExtraction() {
        if (worker.isShutdown()) return;
        if (pending != null) pending.cancel(false);
        long delay = Math.max(TimeUnit.MILLISECONDS.toNanos(delayMillis), intervalNanos - (System.nanoTime() - lastRun));
        pending = worker.schedule(this::run, delay, TimeUnit.NANOSECONDS);
    }

    private synchronized void run() {
        pending = null;
        lastRun = System.nanoTime();
        reclaim.run();
    }

    synchronized void cancel() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }
}

package com.bluesounds.nasquire.core.remote;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 재검토: 요청 경과·마감은 단조 시계로 시도마다 다시 재고, 서킷 브레이커 시각과 분리한다. */
class RemotePoolDeadlineTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private final AtomicLong nanos = new AtomicLong(1_000 * SECOND), wall = new AtomicLong(5_000_000);
    private final List<String> tried = new ArrayList<>();
    private final List<Duration> limits = new ArrayList<>();

    private RemotePool pool() {
        return new RemotePool("ocr", List.of(new RemoteServer("a", "http://127.0.0.1:1", "", 30), new RemoteServer("b", "http://127.0.0.1:2", "", 30)),
                3, 30_000, wall::get, nanos::get);
    }

    @Test void firstServerUsingAllRemainingTimeStopsFailover() {
        try (var pool = pool()) {
            // 첫 서버가 남은 시간(10초)을 모두 쓰고 시간 초과: 시스템 시계가 뒤로 가도 다음 서버를 시도하지 않는다.
            var error = assertThrows(RemoteException.class, () -> pool.execute(Duration.ofSeconds(60), nanos.get() + 10 * SECOND, (http, server, limit) -> {
                tried.add(server.name()); limits.add(limit);
                nanos.addAndGet(10 * SECOND); wall.addAndGet(-3_600_000);
                throw new RemoteException(RemoteException.Kind.TIMEOUT);
            }));
            assertEquals(RemoteException.Kind.TIMEOUT, error.kind());
            assertEquals(List.of("a"), tried);
            assertEquals(Duration.ofSeconds(10), limits.getFirst(), "서버별 30초·전체 60초보다 짧은 남은 시간");
            // 이미 지난 마감은 보내지 않는다.
            tried.clear();
            assertEquals(RemoteException.Kind.TIMEOUT, assertThrows(RemoteException.class,
                    () -> pool.execute(Duration.ofSeconds(60), nanos.get() - 1, (http, server, limit) -> { tried.add(server.name()); return "x"; })).kind());
            assertTrue(tried.isEmpty());
        }
    }

    @Test void eachAttemptGetsTheRecomputedMinimum() {
        try (var pool = pool()) {
            // 첫 서버가 4초 쓰고 5xx → 둘째 서버는 마감까지 남은 6초(서버별 30초보다 짧음)만 받는다.
            var result = pool.execute(Duration.ofSeconds(60), nanos.get() + 10 * SECOND, (http, server, limit) -> {
                tried.add(server.name()); limits.add(limit);
                if (server.name().equals("a")) { nanos.addAndGet(4 * SECOND); throw new RemoteException(RemoteException.Kind.SERVER_ERROR, 503); }
                return "ok";
            });
            assertEquals("b", result.server()); assertTrue(result.failover());
            assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(6)), limits);
            // 마감 없는 기존 경로: 서버별 상한과 기능 전체 상한(단조 시계 경과)의 최솟값.
            limits.clear();
            pool.execute(Duration.ofSeconds(20), (http, server, limit) -> {
                limits.add(limit);
                if (limits.size() == 1) { nanos.addAndGet(15 * SECOND); throw new RemoteException(RemoteException.Kind.CONNECT); }
                return "ok";
            });
            assertEquals(List.of(Duration.ofSeconds(20), Duration.ofSeconds(5)), limits);
        }
    }
}

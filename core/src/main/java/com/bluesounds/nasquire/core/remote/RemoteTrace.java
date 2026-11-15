package com.bluesounds.nasquire.core.remote;

import java.util.*;

/**
 * 현재 스레드에서 처리한 원격 서버 기록. 호출 앱이 begin()으로 시작한 경우에만 기록하므로
 * 색인 작업자 같은 다른 스레드에는 남지 않는다. 응답 헤더 표시용이며 주소는 담지 않는다.
 */
public final class RemoteTrace {
    private RemoteTrace() { }
    public record Entry(String feature, String server, boolean failover) { }
    private static final ThreadLocal<Map<String, Entry>> CURRENT = new ThreadLocal<>();
    public static void begin() { CURRENT.set(new LinkedHashMap<>()); }
    /** 기록을 꺼내고 추적을 끝낸다. 기능 이름별 마지막 처리 서버. */
    public static Map<String, Entry> end() {
        var map = CURRENT.get(); CURRENT.remove();
        return map == null ? Map.of() : Collections.unmodifiableMap(map);
    }
    static void record(String feature, String server, boolean failover) {
        var map = CURRENT.get();
        if (map != null) map.merge(feature, new Entry(feature, server, failover),
                (old, next) -> new Entry(feature, next.server(), old.failover() || next.failover()));
    }
}

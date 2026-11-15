package com.bluesounds.nasquire.core.remote;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 같은 기능(같은 모델·차원)의 원격 서버 묶음. 요청마다 round_robin으로 시작 서버를 고르고
 * 연결 실패·시간 초과·5xx·429만 다음 서버로 넘긴다(서버당 1회, 전체 시간 상한). 4xx·잘못된 응답은 즉시 실패한다.
 * 연속 실패가 기준 횟수에 이르면 서버를 일정 시간 제외하고, 그 뒤 시험 요청 1건이 성공하면 복귀시킨다.
 * 다른 공급자·내장 모델로 대체하지 않는다.
 */
public final class RemotePool implements AutoCloseable {
    public static final int DEFAULT_FAILURE_THRESHOLD = 3, MAX_SERVERS = 8;
    public static final long DEFAULT_OPEN_MILLIS = 30_000;
    /** 처리한 서버 이름과 앞선 서버 실패로 다른 서버가 처리했는지 여부. */
    public record Result<T>(T value, String server, boolean failover) { }
    /**
     * 공개 상태. state는 unknown(아직 호출 없음)·up·down(최근 실패)·open(제외 중, 시험 요청 대기).
     * lastError는 {@link RemoteException.Kind} 소문자 이름이거나 빈 값.
     */
    public record ServerStatus(String name, String state, String lastError, int consecutiveFailures) { }
    @FunctionalInterface public interface Call<T> { T call(RemoteHttp http, RemoteServer server, Duration timeout); }
    private static final class Node {
        final RemoteServer server;
        int failures; long openUntil; boolean open, trial, contacted, ok; String lastError = "";
        Node(RemoteServer server) { this.server = server; }
    }
    private final String feature;
    private final List<Node> nodes;
    private final RemoteHttp http = new RemoteHttp();
    private final int threshold;
    private final long openMillis;
    private final LongSupplier clock, nanos;
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile String disabledReason = "";
    public RemotePool(String feature, List<RemoteServer> servers) {
        this(feature, servers, DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_MILLIS, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }
    /** clock은 서킷 브레이커 제외 시각용 밀리초 시계(기본은 단조 시계)다. 요청 경과·마감은 System.nanoTime으로 잰다. */
    public RemotePool(String feature, List<RemoteServer> servers, int failureThreshold, long openMillis, LongSupplier clock) {
        this(feature, servers, failureThreshold, openMillis, clock, System::nanoTime);
    }
    /** nanos는 요청 경과·마감용 단조 나노초 시계다(시험은 주입한다). 마감 시각도 같은 시계 기준이어야 한다. */
    RemotePool(String feature, List<RemoteServer> servers, int failureThreshold, long openMillis, LongSupplier clock, LongSupplier nanos) {
        if (servers == null || servers.isEmpty() || servers.size() > MAX_SERVERS) throw new IllegalArgumentException("원격 서버는 1~" + MAX_SERVERS + "개");
        if (servers.stream().map(RemoteServer::name).distinct().count() != servers.size()) throw new IllegalArgumentException("원격 서버 이름 중복");
        if (failureThreshold < 1 || openMillis < 0) throw new IllegalArgumentException("서킷 브레이커 설정 오류");
        this.feature = Objects.requireNonNull(feature); this.threshold = failureThreshold; this.openMillis = openMillis; this.clock = Objects.requireNonNull(clock); this.nanos = Objects.requireNonNull(nanos);
        nodes = servers.stream().map(Node::new).toList();
    }
    public String feature() { return feature; }
    public List<RemoteServer> servers() { return nodes.stream().map(n -> n.server).toList(); }
    /** 모델·차원 불일치처럼 설정 자체가 잘못된 경우 기능 전체를 끈다. 빈 값은 다시 켠다. */
    public void disable(String reason) { disabledReason = reason == null ? "" : reason; }
    public String disabledReason() { return disabledReason; }

    /** 기능 요청 1건. total은 페일오버를 포함한 전체 시간 상한이다. */
    public <T> Result<T> execute(Duration total, Call<T> call) { return run(total, null, call); }
    /**
     * 마감 시각(System.nanoTime 기준)이 있는 요청. 서버마다 시도 직전에 남은 시간을 다시 재고 기능 전체·서버별 시간 상한과 비교한
     * 최솟값을 그 시도의 시간 제한으로 쓴다. 남은 시간이 없으면 보내지 않고 TIMEOUT이다.
     */
    public <T> Result<T> execute(Duration total, long deadlineNanos, Call<T> call) { return run(total, deadlineNanos, call); }
    /** 요청 경과·마감은 단조 시계(nanos)로만 잰다. 시스템 시계 조정과 무관하며 서킷 브레이커 시각과도 분리한다. */
    private <T> Result<T> run(Duration total, Long deadlineNanos, Call<T> call) {
        if (!disabledReason.isEmpty()) throw new RemoteException(RemoteException.Kind.UNAVAILABLE);
        long begin = nanos.getAsLong(), end = begin + total.toNanos();
        if (deadlineNanos != null && deadlineNanos - end < 0) end = deadlineNanos;
        int start = Math.floorMod(cursor.getAndIncrement(), nodes.size()), attempts = 0;
        RemoteException last = null;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get((start + i) % nodes.size());
            long remaining = end - nanos.getAsLong();
            if (remaining <= 0) { last = new RemoteException(RemoteException.Kind.TIMEOUT); break; }
            if (!acquire(node, clock.getAsLong())) continue;
            attempts++;
            try {
                // 남은 시간은 밀리초 단위로 올림한다(기존 밀리초 계산과 같은 단위, 마이크로초 경과로 상한이 1초 깎여 보이지 않게).
                long limit = Math.min(node.server.timeoutSeconds() * 1000L, (remaining + 999_999) / 1_000_000);
                T value = attempt(node, Duration.ofMillis(limit), call);
                RemoteTrace.record(feature, node.server.name(), attempts > 1);
                return new Result<>(value, node.server.name(), attempts > 1);
            } catch (RemoteException e) {
                last = e;
                if (!e.retryable()) throw e;
            }
        }
        throw last != null ? last : new RemoteException(RemoteException.Kind.UNAVAILABLE);
    }
    /** 지정 서버 하나에만 보낸다(연결 시험·설정 확인). 서킷 상태와 관계없이 보내고 결과를 상태에 반영한다. */
    public <T> T probe(String name, Duration timeout, Call<T> call) {
        Node node = nodes.stream().filter(n -> n.server.name().equals(name)).findFirst().orElseThrow(() -> new NoSuchElementException("원격 서버 없음"));
        return attempt(node, timeout, call);
    }
    private <T> T attempt(Node node, Duration timeout, Call<T> call) {
        try {
            T value = call.call(http, node.server, timeout);
            succeeded(node, "");
            return value;
        } catch (RemoteException e) {
            // 4xx는 서버가 응답한 것이므로 연결 실패로 세지 않는다. 중단(UNAVAILABLE)도 서버 탓이 아니다.
            if (e.kind() == RemoteException.Kind.CLIENT_ERROR) succeeded(node, name(e));
            else if (e.kind() == RemoteException.Kind.UNAVAILABLE) release(node);
            else failed(node, e);
            throw e;
        } catch (RuntimeException e) {
            var invalid = new RemoteException(RemoteException.Kind.INVALID_RESPONSE);
            failed(node, invalid);
            throw invalid;
        }
    }
    private boolean acquire(Node node, long now) {
        synchronized (node) {
            if (!node.open) return true;
            if (now < node.openUntil || node.trial) return false;
            node.trial = true; // 제외 시간이 지나면 시험 요청 1건만 통과시킨다.
            return true;
        }
    }
    private static String name(RemoteException e) { return e.kind().name().toLowerCase(Locale.ROOT); }
    private void succeeded(Node node, String error) {
        synchronized (node) { node.failures = 0; node.open = false; node.openUntil = 0; node.trial = false; node.contacted = true; node.ok = true; node.lastError = error; }
    }
    private void release(Node node) { synchronized (node) { node.trial = false; } }
    private void failed(Node node, RemoteException e) {
        synchronized (node) {
            node.failures++; node.contacted = true; node.ok = false; node.lastError = name(e);
            if (node.trial || node.failures >= threshold) { node.open = true; node.openUntil = clock.getAsLong() + openMillis; }
            node.trial = false;
        }
    }
    public List<ServerStatus> status() {
        var result = new ArrayList<ServerStatus>();
        for (Node node : nodes) synchronized (node) {
            String state = node.open ? "open" : !node.contacted ? "unknown" : node.ok ? "up" : "down";
            result.add(new ServerStatus(node.server.name(), state, node.lastError, node.failures));
        }
        return result;
    }
    public void close() { http.close(); }
}

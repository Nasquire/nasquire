package com.bluesounds.nasquire.app;

import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 실패만 누적하고 요청 스레드를 재우지 않고 다음 허용 시각을 적용한다.
 * 확인과 진행 중 시도 예약을 한 번에 처리하므로 동시 요청도 키별 상한(진행 중 포함 5회)을 넘지 못한다.
 * 잠금 뒤에는 키별로 한 번에 하나만 시도할 수 있고, 실패 횟수는 되돌리지 않으며 허용 시각을 줄이지 않는다.
 */
@Component
class LoginAttempts {
    static final int LIMIT = 5;
    static final long LOCK_MILLIS = 900000;
    /** 비밀번호·로그인 코드 단계와 분리한 등록/해제 키 접두어. */
    static final String MANAGE = "totp-manage:";
    private static final class State { int count, inFlight; long retryAt; }
    /** 확인 시 예약한 키. 실패/성공 기록 또는 close로 정확히 한 번 해제한다. */
    final class Ticket implements AutoCloseable {
        private final List<String> keys;
        private boolean done;
        private Ticket(List<String> keys) { this.keys = keys; }
        @Override public void close() { synchronized (LoginAttempts.this) { release(this); } }
    }
    private final Map<String, State> states = new LinkedHashMap<>(16, .75f, true);
    private static List<String> keys(String prefix, String ip, String account) {
        return List.of(prefix + "ip:" + ip, prefix + "account:" + SecurityConfiguration.hash(account == null ? "" : account));
    }
    /** 허용되면 진행 중 시도로 예약한 표를 돌려주고, 아니면 null. */
    synchronized Ticket acquire(String prefix, String ip, String account, long now) {
        states.entrySet().removeIf(e -> e.getValue().inFlight == 0 && now - e.getValue().retryAt > LOCK_MILLIS);
        var keys = keys(prefix, ip, account);
        for (String key : keys) {
            var state = states.get(key);
            if (state == null) continue;
            if (state.retryAt > now) return null;
            if (state.count < LIMIT ? state.count + state.inFlight >= LIMIT : state.inFlight > 0) return null;
        }
        for (String key : keys) {
            var state = states.get(key);
            if (state == null) {
                if (states.size() >= 4096) evict();
                state = new State(); states.put(key, state);
            }
            state.inFlight++;
        }
        return new Ticket(keys);
    }
    private void evict() {
        for (var iterator = states.values().iterator(); iterator.hasNext(); ) if (iterator.next().inFlight == 0) { iterator.remove(); return; }
    }
    private void release(Ticket ticket) {
        if (ticket.done) return;
        ticket.done = true;
        for (String key : ticket.keys) { var state = states.get(key); if (state != null && state.inFlight > 0) state.inFlight--; }
    }
    synchronized void failed(Ticket ticket, long now) {
        if (ticket.done) return;
        release(ticket);
        for (String key : ticket.keys) {
            var state = states.computeIfAbsent(key, k -> new State());
            state.count = Math.min(state.count + 1, 1000);
            long wait = state.count >= LIMIT ? LOCK_MILLIS : 1000L << (state.count - 1);
            state.retryAt = Math.max(state.retryAt, now + wait);
        }
    }
    synchronized void succeeded(Ticket ticket) {
        if (ticket.done) return;
        release(ticket);
        for (String key : ticket.keys) clear(key);
    }
    private void clear(String key) {
        var state = states.get(key);
        if (state == null) return;
        if (state.inFlight == 0) states.remove(key); else { state.count = 0; state.retryAt = 0; }
    }
    private record Window(long start, int count) { }
    private final Map<String, Window> windows = new LinkedHashMap<>();
    /** IPv6 주소 회전은 /64 하나의 제한 키로 집계한다. DNS 조회는 하지 않는다. */
    static String network(String ip) {
        if (ip != null && ip.contains(":") && ip.matches("[0-9a-fA-F:]+")) try {
            byte[] bytes = java.net.InetAddress.getByName(ip).getAddress();
            if (bytes.length == 16) return HexFormat.of().formatHex(Arrays.copyOf(bytes, 8)) + "/64";
        } catch (java.net.UnknownHostException ignored) { }
        return ip;
    }
    /** 엔드포인트별 IP 창, 표가 가득 차면 가장 오래된 항목을 축출한다. */
    synchronized boolean allowOAuth(String endpoint, String ip, long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().start >= 60000);
        String key = endpoint + ":" + network(ip);
        var window = windows.get(key);
        int maximum = endpoint.equals("register") ? 10 : 60;
        if (window != null && window.count >= maximum) return false;
        if (window == null && windows.size() >= 4096) windows.remove(windows.keySet().iterator().next());
        windows.put(key, new Window(window == null ? now : window.start, window == null ? 1 : window.count + 1));
        return true;
    }
    private final Map<String, Long> auditWindows = new LinkedHashMap<>();
    synchronized boolean auditOAuth(String kind, String ip, long now) {
        auditWindows.entrySet().removeIf(e -> now - e.getValue() >= 60000);
        String key = kind + ":" + network(ip);
        if (auditWindows.containsKey(key)) return false;
        // 감사 표 포화 시 추가 기록도 억제하여 축출을 이용한 감사 홍수를 막는다.
        if (auditWindows.size() >= 4096) return false;
        auditWindows.put(key, now); return true;
    }
    /** 실패 기록을 지운다(테스트·운영 초기화). */
    synchronized void reset(String prefix, String ip, String account) {
        for (String key : keys(prefix, ip, account)) clear(key);
    }
}

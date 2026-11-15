package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.RemoteUrl;
import com.bluesounds.nasquire.core.remote.*;
import java.time.Duration;
import java.util.*;

/**
 * 기능별 원격 서버 목록의 저장·입력 병합·화면/상태 표시. 키는 기능별 AAD로 암호화해 server.N.encryptedKey에 두고 응답에는 넣지 않는다.
 * 같은 이름·같은 주소일 때만 빈 키 입력이 기존 키를 유지한다. 주소·이름이 바뀌면 새 키를 함께 입력하지 않는 한 키를 버린다.
 */
final class RemoteServers {
    private RemoteServers() { }
    static final String PREFIX = "server.";
    private static final Set<String> INPUT = Set.of("name", "baseUrl", "apiKey", "timeoutSeconds", "clearKey");
    /** 원격 서버 설정 확인 실패. 메시지는 서버 이름·불일치 항목만 담아 화면에 그대로 보여 준다. */
    static final class Rejected extends IllegalArgumentException { Rejected(String message) { super(message); } }

    /** 저장값에서 server.* 항목을 꺼내 지운다. 복호화할 수 없는 키는 빈 키로 두고 keyError를 호출한다. */
    static List<RemoteServer> load(Properties stored, String purpose, EmbeddingSettings crypto, Runnable keyError) throws Exception {
        var servers = new ArrayList<RemoteServer>();
        for (int i = 0; stored.containsKey(PREFIX + i + ".name"); i++) {
            String name = (String)stored.remove(PREFIX + i + ".name"), url = (String)stored.remove(PREFIX + i + ".baseUrl");
            String timeout = (String)stored.remove(PREFIX + i + ".timeoutSeconds"), encrypted = (String)stored.remove(PREFIX + i + ".encryptedKey");
            String key = "";
            if (encrypted != null) {
                try { key = crypto.decrypt(purpose, encrypted); }
                catch (EmbeddingSettings.KeyUnavailable e) { keyError.run(); }
            }
            servers.add(new RemoteServer(name, url, key, Integer.parseInt(timeout)));
        }
        stored.stringPropertyNames().stream().filter(k -> k.startsWith(PREFIX)).forEach(stored::remove);
        return List.copyOf(servers);
    }
    static void store(Properties out, List<RemoteServer> servers, String purpose, EmbeddingSettings crypto) throws Exception {
        for (int i = 0; i < servers.size(); i++) {
            var s = servers.get(i);
            out.setProperty(PREFIX + i + ".name", s.name()); out.setProperty(PREFIX + i + ".baseUrl", s.baseUrl());
            out.setProperty(PREFIX + i + ".timeoutSeconds", Integer.toString(s.timeoutSeconds()));
            if (!s.key().isEmpty()) out.setProperty(PREFIX + i + ".encryptedKey", crypto.encrypt(purpose, s.key()));
        }
    }
    /** 저장 파일에 암호문이 있는지(키 재생성 차단 판단용). */
    static boolean hasCiphertext(Properties stored) {
        return stored.stringPropertyNames().stream().anyMatch(k -> k.startsWith(PREFIX) && k.endsWith(".encryptedKey"));
    }
    /** servers 입력(목록 전체 교체)을 현재 목록과 병합한다. */
    static List<RemoteServer> merge(List<RemoteServer> current, Object input, int defaultTimeout, int maxTimeout) {
        if (!(input instanceof List<?> list) || list.size() > RemotePool.MAX_SERVERS) throw new IllegalArgumentException("원격 서버 목록 오류");
        var result = new ArrayList<RemoteServer>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !INPUT.containsAll(map.keySet())) throw new IllegalArgumentException("원격 서버 입력 오류");
            String name = text(map.get("name")), url = text(map.get("baseUrl")).trim(), key = text(map.get("apiKey"));
            String timeoutText = text(map.get("timeoutSeconds"));
            int timeout = timeoutText.isBlank() ? defaultTimeout : Integer.parseInt(timeoutText.trim());
            if (timeout > maxTimeout) throw new IllegalArgumentException("원격 서버 시간 제한 오류");
            if (key.isEmpty() && !"true".equals(text(map.get("clearKey"))))
                key = current.stream().filter(s -> s.name().equals(name) && s.baseUrl().equals(url)).map(RemoteServer::key).findFirst().orElse("");
            result.add(new RemoteServer(name, url, key, timeout));
        }
        if (result.stream().map(RemoteServer::name).distinct().count() != result.size()) throw new IllegalArgumentException("원격 서버 이름 중복");
        return List.copyOf(result);
    }
    /** 이전 단일 서버 입력(baseUrl·apiKey). 첫 서버만 바꾸고 나머지는 유지한다. */
    static List<RemoteServer> legacy(List<RemoteServer> current, String url, String key, int timeout) {
        var result = new ArrayList<>(current);
        String name = result.isEmpty() ? "default" : result.getFirst().name();
        String kept = !result.isEmpty() && result.getFirst().baseUrl().equals(url) ? result.getFirst().key() : "";
        var first = new RemoteServer(name, url, key == null || key.isEmpty() ? kept : key, timeout);
        if (result.isEmpty()) result.add(first); else result.set(0, first);
        return List.copyOf(result);
    }
    /** 이름 하나 또는 전체(null)의 키를 지운다. */
    static List<RemoteServer> clearKey(List<RemoteServer> current, String name) {
        if (name != null && current.stream().noneMatch(s -> s.name().equals(name))) throw new NoSuchElementException("원격 서버 없음");
        return current.stream().map(s -> name == null || s.name().equals(name) ? new RemoteServer(s.name(), s.baseUrl(), "", s.timeoutSeconds()) : s).toList();
    }
    static void requireTransport(List<RemoteServer> servers, boolean allowInsecureHttp) {
        for (var s : servers) RemoteUrl.require(s.baseUrl(), allowInsecureHttp);
    }
    static boolean insecure(List<RemoteServer> servers) { return servers.stream().anyMatch(s -> RemoteUrl.insecure(s.baseUrl())); }
    /** 관리 화면용. 주소는 관리자 설정 화면에만, 키는 존재 여부만 보인다. */
    static List<Map<String, Object>> view(List<RemoteServer> servers, RemotePool pool) {
        var states = new HashMap<String, RemotePool.ServerStatus>();
        if (pool != null) pool.status().forEach(s -> states.put(s.name(), s));
        var result = new ArrayList<Map<String, Object>>();
        for (var s : servers) {
            var state = states.get(s.name());
            var item = new LinkedHashMap<String, Object>();
            item.put("name", s.name()); item.put("baseUrl", s.baseUrl()); item.put("timeoutSeconds", s.timeoutSeconds());
            item.put("hasKey", !s.key().isEmpty()); item.put("insecureHttp", RemoteUrl.insecure(s.baseUrl()));
            item.put("state", state == null ? "off" : state.state()); item.put("lastError", state == null ? "" : state.lastError());
            result.add(item);
        }
        return result;
    }
    /** 상태 API용. 주소·키 없이 이름·상태·최근 오류 종류만. */
    static Map<String, Object> health(String mode, String disabledReason, RemotePool pool) {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode); result.put("disabled", disabledReason != null && !disabledReason.isEmpty());
        var servers = new ArrayList<Map<String, Object>>();
        if (pool != null) for (var s : pool.status())
            servers.add(Map.of("name", s.name(), "state", s.state(), "lastError", s.lastError(), "consecutiveFailures", s.consecutiveFailures()));
        result.put("servers", servers);
        return result;
    }
    /** 저장 전 서버별 모델·차원 확인. 불일치면 거부, 연결 불가는 down으로 허용한다. */
    static List<RemoteCheck> verify(List<RemoteServer> servers, java.util.function.BiFunction<String, Duration, RemoteCheck> check) {
        var checks = new ArrayList<RemoteCheck>();
        for (var s : servers) checks.add(check.apply(s.name(), Duration.ofSeconds(Math.min(s.timeoutSeconds(), 10))));
        return checks;
    }
    /** 시작 시 확인. 시작을 늦추지 않도록 별도 스레드에서 확인하고 불일치면 기능만 끈다(disabledReason). */
    static void verifyLater(RemotePool pool, List<RemoteServer> servers, java.util.function.BiFunction<String, Duration, RemoteCheck> check) {
        Thread.ofVirtual().name("nasquire-remote-verify").start(() -> {
            try {
                String reason = mismatch(verify(servers, check));
                if (!reason.isEmpty()) { pool.disable(reason); LOG.warn("원격 {} 기능을 비활성화했습니다: {}", pool.feature(), reason); }
            } catch (RuntimeException ignored) { /* 닫힌 묶음 등. 요청 때 다시 실패를 알린다. */ }
        });
    }
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RemoteServers.class);
    static String mismatch(List<RemoteCheck> checks) {
        var bad = checks.stream().filter(RemoteCheck::mismatch).map(c -> c.server() + " (" + c.detail() + ")").toList();
        return bad.isEmpty() ? "" : "원격 서버의 모델·차원이 설정과 다릅니다 / Remote server model or dimensions differ: " + String.join(", ", bad);
    }
    private static String text(Object value) {
        if (value == null) return "";
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException("원격 서버 입력 오류");
        String s = value.toString();
        if (s.length() > 4096) throw new IllegalArgumentException("원격 서버 입력 오류");
        return s;
    }
}

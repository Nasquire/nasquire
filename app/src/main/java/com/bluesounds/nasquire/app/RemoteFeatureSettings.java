package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.remote.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/**
 * 원격 서버 묶음을 쓰는 선택 기능(OCR·Docling)의 공통 설정 저장·검증 흐름. 리랭커({@link RerankerSettings})와 같은 규칙을 따른다.
 * 설정 파일은 데이터 폴더의 기능별 properties(0600), 서버 키는 embedding.key AES-GCM v2 + 기능별 AAD로 server.N.encryptedKey에 저장하며 응답에 넣지 않는다.
 * 키를 복호화할 수 없는 활성 원격 설정은 시작을 거부하고, 비루프백 평문 http는 allowInsecureHttp=true일 때만 허용한다(기존 설정은 기능만 끄고 이유 표시).
 * 저장 시 서버별 확인으로 엔진·버전 불일치는 거부하고 연결 불가는 down으로 허용하며, 시작 시에는 비동기로 확인해 불일치면 기능만 끈다.
 * @param <P> 기능 공급자. null은 off(또는 원격이 아닌 구성)이다.
 */
abstract class RemoteFeatureSettings<P extends AutoCloseable> implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RemoteFeatureSettings.class);
    protected final Path folder;
    private final Path file;
    private final EmbeddingSettings secrets;
    /** 기능 이름: 모드 설정 키·AAD·원격 묶음 이름으로 쓴다(ocr, docling). */
    protected final String feature;
    protected Properties settings;
    protected List<RemoteServer> servers = List.of();
    protected P provider;
    private boolean keyError;
    private String disabledReason;
    private long revision;

    RemoteFeatureSettings(Path dataFolder, EmbeddingSettings secrets, String feature) {
        this.folder = dataFolder; this.file = dataFolder.resolve(feature + ".properties"); this.secrets = secrets; this.feature = feature;
    }
    /** 하위 클래스 생성자 마지막에 호출한다. 이관 → 읽기 → 공급자 생성 → 비동기 확인 순서다. */
    protected final void load() throws Exception {
        settings = defaults();
        if (!Files.exists(file)) migrate();
        if (Files.exists(file)) {
            var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
            servers = RemoteServers.load(stored, feature, secrets, () -> keyError = true);
            if (keyError) LOG.error("{} 원격 키를 복호화할 수 없습니다. {}", feature, EmbeddingSettings.RECOVERY_HINT);
            for (String k : scalars()) if (stored.containsKey(k)) settings.setProperty(k, stored.getProperty(k));
        }
        if (keyError && "remote".equals(mode()))
            throw new EmbeddingSettings.KeyUnavailable(feature + " 키를 복호화할 수 없습니다. " + EmbeddingSettings.RECOVERY_HINT);
        // 업그레이드 호환: 비루프백 평문 주소는 시작을 막지 않고 이 기능만 끈 뒤 상태로 알린다. full 없는 로컬 구성은 시작을 거부한다(create가 던진다).
        try { checkTransport(settings, servers); provider = create(settings, servers); }
        catch (IllegalArgumentException e) { disabledReason = e.getMessage(); LOG.warn("{} 기능을 비활성화했습니다: {}", feature, e.getMessage()); }
        P initial = provider;
        var pool = pool(initial);
        if (pool != null) RemoteServers.verifyLater(pool, servers, (server, limit) -> verify(initial, server, limit));
    }

    /** 설정 파일이 없을 때 이전 저장값을 옮긴다. 기본은 없음. */
    protected void migrate() throws Exception { }
    protected abstract Properties defaults();
    /** 저장·응답하는 단일 값 키. 모드 키(feature)를 포함한다. */
    protected abstract Set<String> scalars();
    /** 서버 하나의 요청 시간 상한 최댓값. */
    protected abstract int maxServerTimeout();
    /** 새 서버의 기본 요청 시간 상한. */
    protected abstract int defaultServerTimeout(Properties next);
    /** 설정 검증 뒤 공급자를 만든다. 원격 호출·프로세스 실행은 하지 않는다. 잘못된 값은 IllegalArgumentException. */
    protected abstract P create(Properties p, List<RemoteServer> list);
    protected abstract RemotePool pool(P provider);
    protected abstract RemoteCheck verify(P provider, String server, Duration limit);

    protected final String mode() { return settings.getProperty(feature); }
    protected final boolean remote(Properties p) { return "remote".equals(p.getProperty(feature)); }
    private void checkTransport(Properties p, List<RemoteServer> list) {
        String allow = p.getProperty("allowInsecureHttp", "false");
        if (!Set.of("true", "false").contains(allow)) throw new IllegalArgumentException("allowInsecureHttp 설정 오류");
        if (remote(p)) RemoteServers.requireTransport(list, allow.equals("true"));
    }
    protected static int number(Properties p, String name, int min, int max) {
        int n;
        try { n = Integer.parseInt(p.getProperty(name, "").trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " 설정 오류"); }
        if (n < min || n > max) throw new IllegalArgumentException(name + " 범위 오류 (" + min + "~" + max + ")");
        return n;
    }
    protected final void write(Properties stored) throws Exception {
        Path temporary = Files.createTempFile(folder, feature + "-", ".properties");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire " + feature + " settings"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    final synchronized String disabledReason() {
        if (disabledReason != null) return disabledReason;
        var pool = pool(provider);
        return pool == null ? "" : pool.disabledReason();
    }
    /** 관리 화면용. 주소는 관리자 설정에만, 키는 존재 여부만. */
    public synchronized Map<String, Object> view() {
        var result = new LinkedHashMap<String, Object>();
        for (String k : scalars()) result.put(k, settings.getProperty(k));
        result.put("servers", RemoteServers.view(servers, pool(provider)));
        result.put("hasKey", servers.stream().anyMatch(s -> !s.key().isEmpty()));
        result.put("keyError", keyError);
        result.put("disabledReason", disabledReason());
        result.put("insecureHttp", remote(settings) && RemoteServers.insecure(servers));
        result.put("maxServerTimeoutSeconds", maxServerTimeout());
        return result;
    }
    public synchronized Map<String, Object> health() { return RemoteServers.health(mode(), disabledReason(), pool(provider)); }

    private record Prepared(Properties next, List<RemoteServer> servers, String blocked, long revision) { }
    /** 저장한다. 공급자를 바꿨으면(설정 값이 달라졌으면) true. */
    public boolean save(Map<String, ?> input) throws Exception {
        Prepared prepared;
        synchronized (this) { prepared = prepare(input); }
        P created = prepared.blocked == null ? create(prepared.next, prepared.servers) : null;
        try {
            // 원격 확인은 잠금 밖에서 한다. 엔진·버전이 다른 서버는 거부하고 연결할 수 없는 서버는 down으로 허용한다.
            if (pool(created) != null) {
                P target = created;
                String mismatch = RemoteServers.mismatch(RemoteServers.verify(prepared.servers, (server, limit) -> verify(target, server, limit)));
                if (!mismatch.isEmpty()) throw new RemoteServers.Rejected(mismatch);
            }
            P old; boolean changed;
            synchronized (this) {
                if (revision != prepared.revision) throw new IllegalStateException("다른 저장과 겹쳤습니다. 다시 시도하세요");
                var stored = new Properties();
                for (String k : scalars()) stored.setProperty(k, prepared.next.getProperty(k));
                RemoteServers.store(stored, prepared.servers, feature, secrets);
                write(stored);
                changed = !settings.equals(prepared.next) || !servers.equals(prepared.servers);
                old = provider; provider = created;
                settings = prepared.next; servers = prepared.servers; disabledReason = prepared.blocked; keyError = false; revision++;
                published(created);
            }
            if (old != null) old.close();
            return changed;
        } catch (Exception e) { if (created != null) created.close(); throw e; }
    }
    /** 새 공급자를 게시한 직후(잠금 안) 호출한다. */
    protected void published(P created) { }
    private Prepared prepare(Map<String, ?> input) {
        var next = new Properties(); next.putAll(settings);
        for (var e : input.entrySet()) {
            if (!(scalars().contains(e.getKey()) || Set.of("servers", "clearKey", "clearServerKey").contains(e.getKey())) || e.getValue() == null)
                throw new IllegalArgumentException(feature + " 설정 오류");
            if (scalars().contains(e.getKey())) next.setProperty(e.getKey(), scalar(e.getValue()));
        }
        List<RemoteServer> nextServers = servers;
        if (input.containsKey("servers")) nextServers = RemoteServers.merge(servers, input.get("servers"), defaultServerTimeout(next), maxServerTimeout());
        if ("true".equals(input.containsKey("clearKey") ? scalar(input.get("clearKey")) : "")) nextServers = RemoteServers.clearKey(nextServers, null);
        if (input.containsKey("clearServerKey")) nextServers = RemoteServers.clearKey(nextServers, scalar(input.get("clearServerKey")));
        String blocked = null;
        try { checkTransport(next, nextServers); }
        catch (IllegalArgumentException e) {
            // 평문 주소로 꺼진 상태에서도 키 삭제는 허용한다.
            if (disabledReason == null || !input.keySet().stream().allMatch(k -> k.startsWith("clear"))) throw e;
            blocked = e.getMessage();
        }
        return new Prepared(next, nextServers, blocked, revision);
    }
    private String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException(feature + " 설정 오류");
        String s = value.toString();
        if (s.length() > 4096) throw new IllegalArgumentException(feature + " 설정 오류");
        return s;
    }
    /** 저장된 키(전체 또는 서버 하나)를 메모리·암호문 모두에서 지운다. */
    public void clearKey(String server) throws Exception { save(server == null ? Map.of("clearKey", "true") : Map.of("clearServerKey", server)); }
    /** 저장된 서버 하나를 확인한다. 원격 호출은 잠금 밖에서 한다. */
    public Map<String, Object> test(String server) {
        P current; RemoteServer target;
        synchronized (this) {
            current = provider;
            if (pool(current) == null) throw new IllegalStateException("원격 " + feature + " 없음");
            target = servers.stream().filter(s -> s.name().equals(server)).findFirst().orElseThrow(() -> new NoSuchElementException("원격 서버 없음"));
        }
        var check = verify(current, target.name(), Duration.ofSeconds(Math.min(target.timeoutSeconds(), 30)));
        return Map.of("ok", "ok".equals(check.result()), "server", check.server(), "result", check.result(), "detail", check.detail());
    }
    public synchronized void close() throws Exception { if (provider != null) provider.close(); }
}

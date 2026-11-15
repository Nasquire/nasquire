package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.remote.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 리랭커 설정(검색 설정). 기본 off, remote는 lite·full 공통(서버 여러 개), builtin(로컬 ONNX)은 full 빌드에서만.
 * 키는 reranker.properties(0600)에 기존 embedding.key AES-GCM v2 + AAD reranker로 저장하며 응답에 넣지 않는다.
 * 이전 full.properties의 단일 서버 설정은 처음 시작할 때 서버 하나(default)로 이관한다(암호문을 그대로 옮겨 키 재입력 불필요).
 */
@Service
public final class RerankerSettings implements AutoCloseable {
    private final Path folder, file;
    private final EmbeddingSettings secrets;
    private Properties settings = defaults();
    private List<RemoteServer> servers = List.of();
    private Providers.RerankerProvider reranker;
    private RemoteRerankerProvider remote;
    private boolean keyError;
    /** 시작 시 비루프백 평문 주소·설정 오류로 리랭커를 끈 이유. */
    private String disabledReason;
    private long revision;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RerankerSettings.class);
    private static final Set<String> FIELDS = Set.of("reranker", "model", "topN", "timeoutSeconds", "localModelPath", "localTokenizerPath", "localDownload",
            "allowInsecureHttp", "servers", "baseUrl", "apiKey", "clearKey", "clearServerKey");
    private static final Set<String> SCALARS = Set.of("reranker", "model", "topN", "timeoutSeconds", "localModelPath", "localTokenizerPath", "localDownload", "allowInsecureHttp");
    /** 이전 full.properties의 리랭커 항목. 이관 뒤 full 설정 저장 때 지운다. */
    static final Set<String> LEGACY_FULL = Set.of("reranker", "baseUrl", "model", "apiKey", "encryptedKey", "topN", "timeoutSeconds",
            "localModelPath", "localTokenizerPath", "localDownload", "allowInsecureHttp");
    public RerankerSettings(Path dataFolder, EmbeddingSettings secrets) throws Exception {
        folder = dataFolder; file = folder.resolve("reranker.properties"); this.secrets = secrets;
        if (!Files.exists(file)) migrate();
        if (Files.exists(file)) {
            var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
            servers = RemoteServers.load(stored, EmbeddingSettings.RERANKER, secrets, () -> keyError = true);
            if (keyError) LOG.error("리랭커 원격 키를 복호화할 수 없습니다. {}", EmbeddingSettings.RECOVERY_HINT);
            for (String k : SCALARS) if (stored.containsKey(k)) settings.setProperty(k, stored.getProperty(k));
        }
        // 기존 정책: 키를 복호화할 수 없는 활성 원격 설정과 full 없는 builtin 설정은 시작을 거부한다(키 백업 복원·기능 off 뒤 전환).
        if (keyError && "remote".equals(settings.getProperty("reranker")))
            throw new EmbeddingSettings.KeyUnavailable("리랭커 키를 복호화할 수 없습니다. " + EmbeddingSettings.RECOVERY_HINT);
        // 업그레이드 호환: 비루프백 평문 주소는 시작을 막지 않고 원격 리랭커만 끈 뒤 상태로 알린다.
        try { checkTransport(settings, servers); reranker = create(settings, servers); }
        catch (IllegalArgumentException e) { disabledReason = e.getMessage(); LOG.warn("리랭커를 비활성화했습니다: {}", e.getMessage()); }
        if (reranker instanceof RemoteRerankerProvider created) { remote = created; RemoteServers.verifyLater(created.pool(), servers, created::verify); }
    }
    /** full.properties의 이전 리랭커 설정을 reranker.properties로 옮긴다. 키 암호문은 같은 AAD(reranker)이므로 복호화 없이 옮긴다. */
    private void migrate() throws Exception {
        Path full = folder.resolve("full.properties");
        if (!Files.exists(full)) return;
        var old = new Properties(); try (var in = Files.newInputStream(full)) { old.load(in); }
        if (!old.containsKey("reranker") && !old.containsKey("baseUrl")) return;
        var stored = new Properties();
        for (String k : SCALARS) if (old.containsKey(k)) stored.setProperty(k, old.getProperty(k));
        if (old.containsKey("baseUrl")) {
            int timeout = Integer.parseInt(old.getProperty("timeoutSeconds", "5"));
            stored.setProperty(RemoteServers.PREFIX + "0.name", "default");
            stored.setProperty(RemoteServers.PREFIX + "0.baseUrl", old.getProperty("baseUrl").trim());
            stored.setProperty(RemoteServers.PREFIX + "0.timeoutSeconds", Integer.toString(Math.max(1, Math.min(30, timeout))));
            if (old.containsKey("encryptedKey")) stored.setProperty(RemoteServers.PREFIX + "0.encryptedKey", old.getProperty("encryptedKey"));
        }
        write(stored);
        LOG.info("이전 full 리랭커 설정을 reranker.properties로 이관했습니다");
    }
    private void write(Properties stored) throws Exception {
        Path temporary = Files.createTempFile(folder, "reranker-", ".properties");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire reranker settings"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Properties defaults() {
        var p = new Properties(); p.setProperty("reranker", "off");
        p.setProperty("model", "reranker"); p.setProperty("topN", "20"); p.setProperty("timeoutSeconds", "5");
        // 빈 경로는 데이터 폴더의 models/<모델 이름>/ 기본 위치를 뜻한다.
        p.setProperty("localModelPath", ""); p.setProperty("localTokenizerPath", ""); p.setProperty("localDownload", "false");
        p.setProperty("allowInsecureHttp", "false");
        return p;
    }
    private static void checkTransport(Properties p, List<RemoteServer> list) {
        String allow = p.getProperty("allowInsecureHttp", "false");
        if (!Set.of("true", "false").contains(allow)) throw new IllegalArgumentException("allowInsecureHttp 설정 오류");
        if ("remote".equals(p.getProperty("reranker"))) RemoteServers.requireTransport(list, allow.equals("true"));
    }
    private static int number(Properties p, String name, int min, int max) {
        int n = Integer.parseInt(p.getProperty(name));
        if (n < min || n > max) throw new IllegalArgumentException("리랭커 제한 오류"); return n;
    }
    private Providers.RerankerProvider create(Properties p, List<RemoteServer> list) {
        int top = number(p, "topN", 1, 100), timeout = number(p, "timeoutSeconds", 1, 30);
        if (p.getProperty("model", "").isBlank() || p.getProperty("model").length() > 256) throw new IllegalArgumentException("리랭커 모델 오류");
        return switch (p.getProperty("reranker")) {
            case "off" -> null;
            case "remote" -> {
                if (list.isEmpty()) throw new IllegalArgumentException("원격 서버를 하나 이상 등록하세요");
                var pool = new RemotePool("reranker", list);
                try { yield new RemoteRerankerProvider(pool, p.getProperty("model"), top, timeout); }
                catch (RuntimeException e) { pool.close(); throw e; }
            }
            case "builtin" -> {
                String name = "mmarco-mMiniLMv2-L12-H384-v1";
                Path model = p.getProperty("localModelPath", "").isBlank() ? folder.resolve("models").resolve(name).resolve("model-qint8.onnx") : Path.of(p.getProperty("localModelPath"));
                Path tokenizer = p.getProperty("localTokenizerPath", "").isBlank() ? folder.resolve("models").resolve(name).resolve("tokenizer.json") : Path.of(p.getProperty("localTokenizerPath"));
                if (!Set.of("true", "false").contains(p.getProperty("localDownload"))) throw new IllegalArgumentException("다운로드 설정 오류");
                yield Providers.full().localReranker(model, tokenizer, Boolean.parseBoolean(p.getProperty("localDownload")), folder, top, timeout);
            }
            default -> throw new IllegalArgumentException("리랭커 공급자 오류");
        };
    }
    private String disabledReason() {
        if (disabledReason != null) return disabledReason;
        return remote == null ? "" : remote.pool().disabledReason();
    }
    public synchronized Map<String, Object> view() {
        var result = new LinkedHashMap<String, Object>();
        for (String k : SCALARS) result.put(k, settings.getProperty(k));
        result.put("servers", RemoteServers.view(servers, remote == null ? null : remote.pool()));
        result.put("hasKey", servers.stream().anyMatch(s -> !s.key().isEmpty()));
        result.put("keyError", keyError);
        result.put("disabledReason", disabledReason());
        result.put("insecureHttp", "remote".equals(settings.getProperty("reranker")) && RemoteServers.insecure(servers));
        result.put("builtinAvailable", ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent());
        return result;
    }
    public synchronized Map<String, Object> health() { return RemoteServers.health(settings.getProperty("reranker"), disabledReason(), remote == null ? null : remote.pool()); }
    private record Prepared(Properties next, List<RemoteServer> servers, String blocked, long revision) { }
    public void save(Map<String, ?> input) throws Exception {
        Prepared prepared;
        synchronized (this) { prepared = prepare(input); }
        var created = prepared.blocked == null ? create(prepared.next, prepared.servers) : null;
        try {
            // 원격 확인은 잠금 밖에서 한다. 모델이 다른 서버는 거부하고 연결할 수 없는 서버는 down으로 허용한다.
            if (created instanceof RemoteRerankerProvider r) {
                String mismatch = RemoteServers.mismatch(RemoteServers.verify(prepared.servers, r::verify));
                if (!mismatch.isEmpty()) throw new RemoteServers.Rejected(mismatch);
            }
            Providers.RerankerProvider old;
            synchronized (this) {
                if (revision != prepared.revision) throw new IllegalStateException("다른 저장과 겹쳤습니다. 다시 시도하세요");
                var stored = new Properties();
                for (String k : SCALARS) stored.setProperty(k, prepared.next.getProperty(k));
                RemoteServers.store(stored, prepared.servers, EmbeddingSettings.RERANKER, secrets);
                write(stored);
                old = reranker; reranker = created; remote = created instanceof RemoteRerankerProvider r ? r : null;
                settings = prepared.next; servers = prepared.servers; disabledReason = prepared.blocked; keyError = false; revision++;
            }
            if (old != null) old.close();
        } catch (Exception e) { if (created != null) created.close(); throw e; }
    }
    private Prepared prepare(Map<String, ?> input) {
        var next = new Properties(); next.putAll(settings);
        for (var e : input.entrySet()) {
            if (!FIELDS.contains(e.getKey()) || e.getValue() == null) throw new IllegalArgumentException("리랭커 설정 오류");
            if (SCALARS.contains(e.getKey())) next.setProperty(e.getKey(), scalar(e.getValue()));
        }
        int timeout = number(next, "timeoutSeconds", 1, 30);
        List<RemoteServer> nextServers = servers;
        if (input.containsKey("servers")) nextServers = RemoteServers.merge(servers, input.get("servers"), timeout, 30);
        else if (input.containsKey("baseUrl")) nextServers = RemoteServers.legacy(servers, scalar(input.get("baseUrl")).trim(), input.containsKey("apiKey") ? scalar(input.get("apiKey")) : "", timeout);
        else if (input.containsKey("apiKey") && !scalar(input.get("apiKey")).isEmpty() && !servers.isEmpty())
            nextServers = RemoteServers.legacy(servers, servers.getFirst().baseUrl(), scalar(input.get("apiKey")), servers.getFirst().timeoutSeconds());
        if (input.containsKey("clearKey") && "true".equals(scalar(input.get("clearKey")))) nextServers = RemoteServers.clearKey(nextServers, null);
        if (input.containsKey("clearServerKey")) nextServers = RemoteServers.clearKey(nextServers, scalar(input.get("clearServerKey")));
        String blocked = null;
        try { checkTransport(next, nextServers); }
        catch (IllegalArgumentException e) {
            if (disabledReason == null || !input.keySet().stream().allMatch(k -> k.startsWith("clear"))) throw e;
            blocked = e.getMessage();
        }
        return new Prepared(next, nextServers, blocked, revision);
    }
    private static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException("리랭커 설정 오류");
        String s = value.toString();
        if (s.length() > 4096) throw new IllegalArgumentException("리랭커 설정 오류");
        return s;
    }
    /** 저장된 리랭커 키(전체 또는 서버 하나)를 메모리·암호문 모두에서 지운다. */
    public void clearKey(String server) throws Exception { save(server == null ? Map.of("clearKey", "true") : Map.of("clearServerKey", server)); }
    /** 저장된 서버 하나에 모델 목록·시험 리랭크를 보낸다. 원격 호출은 잠금 밖에서 한다. */
    public Map<String, Object> test(String server) {
        RemoteRerankerProvider current; RemoteServer target;
        synchronized (this) {
            current = remote;
            if (current == null) throw new IllegalStateException("원격 리랭커 없음");
            target = servers.stream().filter(s -> s.name().equals(server)).findFirst().orElseThrow(() -> new NoSuchElementException("원격 서버 없음"));
        }
        var check = current.verify(target.name(), java.time.Duration.ofSeconds(Math.min(target.timeoutSeconds(), 10)));
        return Map.of("ok", "ok".equals(check.result()), "server", check.server(), "result", check.result(), "detail", check.detail());
    }
    /** enabled가 false거나 꺼져 있으면 입력 순서. 원격이 모두 실패·혼잡하면 입력 순서(applied=false). */
    public Providers.Reranked rerank(String query, List<SearchIndex.Hit> hits, boolean enabled) {
        Providers.RerankerProvider snapshot; synchronized (this) { snapshot = reranker; }
        return enabled && snapshot != null ? snapshot.reranked(query, hits) : Providers.Reranked.original(hits);
    }
    public synchronized void close() { if (reranker != null) reranker.close(); }
}

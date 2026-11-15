package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.remote.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 원격 인증키는 별도 로컬 키로 암호화하여 저장하며 응답에 반환하지 않는다. */
@Service
public final class EmbeddingSettings {
    private final SearchIndex index;
    private final Path folder, file;
    private final Properties settings = new Properties();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EmbeddingSettings.class);
    /** AES-GCM AAD 용도. 같은 키로 만든 다른 용도의 암호문을 서로 바꿔 쓸 수 없다. */
    static final String TOTP = "totp", EMBEDDING = "embedding", QDRANT = "qdrant", RERANKER = "reranker", OCR = "ocr", DOCLING = "docling";
    /** embedding.properties에 함께 저장하는 비밀값. 저장 때 파일 전체를 다시 쓴다. */
    private static final Set<String> SECRETS = Set.of("apiKey", "qdrantApiKey");
    /** 용도별 AAD를 적용한 형식. 접두어가 없는 기존 암호문은 AAD 없이 읽고 다음 저장 때 이 형식으로 바꾼다. */
    private static final String VERSION = "v2:";
    static final String RECOVERY_HINT = "embedding.key를 백업에서 복원하거나, 서버를 멈추고 --recover-admin --data=<데이터 폴더>로 TOTP를 해제한 뒤 임베딩 원격 키를 다시 저장하세요";
    /** 키 파일이 없거나 바뀌어 기존 암호문을 읽을 수 없는 상태. 새 키를 조용히 만들지 않는다. */
    static final class KeyUnavailable extends IllegalStateException { KeyUnavailable(String message) { super(message); } }
    private java.util.function.BooleanSupplier storedSecrets = () -> false;
    private boolean keyError;
    /** 시작 시 비루프백 평문 주소 등으로 벡터 기능을 끈 이유. 저장에 성공하면 지운다. */
    private String disabledReason;
    /** 원격 임베딩 서버 목록. 같은 모델·차원만 허용한다. */
    private List<RemoteServer> servers = List.of();
    /** 현재 원격 공급자의 서버 묶음(상태 표시용). */
    private RemotePool pool;
    private RemoteEmbeddingProvider remote;
    /** 저장 준비와 게시 사이에 다른 저장이 끼었는지 확인한다. */
    private long revision;
    /** 이전 단일 서버 색인의 모델 ID(remote:주소:모델). 모델을 바꾸기 전까지 재색인 없이 유지한다. 응답에 넣지 않는다. */
    private static final String INDEX_MODEL_ID = "indexModelId";
    private static final Set<String> FIELDS = Set.of("provider", "baseUrl", "model", "apiKey", "dimensions", "batchSize", "timeoutSeconds",
            "modelPath", "tokenizerPath", "download", "modelUrl", "tokenizerUrl", "modelSha256", "tokenizerSha256",
            "vectorStore", "qdrantUrl", "qdrantCollection", "qdrantApiKey", "qdrantTimeoutSeconds", "allowInsecureHttp", "clearKey", "clearQdrantKey", "clearServerKey", "servers");
    /** 이전 단일 서버 입력. 저장값이 아니라 servers의 첫 서버로 바꾼다. */
    private static final Set<String> LEGACY = Set.of("baseUrl", "apiKey", "clearKey", "clearServerKey");
    public EmbeddingSettings(SearchIndex index, Path dataFolder, Environment environment) throws Exception {
        this.index = index; folder = dataFolder; file = folder.resolve("embedding.properties");
        settings.setProperty("provider", "off");
        settings.setProperty("model", "embedding-small"); settings.setProperty("dimensions", "384");
        settings.setProperty("batchSize", "2"); settings.setProperty("timeoutSeconds", "30");
        settings.setProperty("modelPath", "/data/models/multilingual-e5-small/model-int8.onnx");
        settings.setProperty("tokenizerPath", "/data/models/multilingual-e5-small/tokenizer.json"); settings.setProperty("download", "false");
        String revision = "761b726dd34fb83930e26aab4e9ac3899aa1fa78";
        settings.setProperty("modelUrl", "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + revision + "/onnx/model_quantized.onnx");
        settings.setProperty("tokenizerUrl", "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + revision + "/tokenizer.json");
        settings.setProperty("modelSha256", "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193");
        settings.setProperty("tokenizerSha256", "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39");
        settings.setProperty("vectorStore", "lucene"); settings.setProperty("qdrantUrl", "http://127.0.0.1:6333");
        settings.setProperty("qdrantCollection", "nasquire"); settings.setProperty("qdrantTimeoutSeconds", "10");
        settings.setProperty("allowInsecureHttp", "false");
        // 환경변수의 baseUrl·apiKey는 이전 단일 서버 형식으로 받아 첫 서버로 이관한다.
        String legacyUrl = null, legacyKey = "";
        for (String field : FIELDS) {
            String value = environment.getProperty("nasquire.embedding." + field);
            if (value == null || field.equals("servers") || field.startsWith("clear")) continue;
            if (field.equals("baseUrl")) legacyUrl = value;
            else if (field.equals("apiKey")) legacyKey = value;
            else settings.setProperty(field, value);
        }
        if (Files.exists(file)) {
            var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
            String encrypted = (String)stored.remove("encryptedKey"), qdrant = (String)stored.remove("encryptedQdrantKey");
            servers = RemoteServers.load(stored, EMBEDDING, this, () -> keyError = true);
            if (keyError) LOG.error("임베딩 원격 키를 복호화할 수 없습니다. {}", RECOVERY_HINT);
            String storedUrl = (String)stored.remove("baseUrl");
            stored.remove("apiKey");
            settings.putAll(stored);
            if (servers.isEmpty() && storedUrl != null) {
                legacyUrl = storedUrl; legacyKey = "";
                if (encrypted != null) {
                    try { legacyKey = decrypt(EMBEDDING, encrypted); }
                    catch (KeyUnavailable e) { keyError = true; LOG.error("임베딩 원격 키를 복호화할 수 없습니다: {}", e.getMessage()); }
                }
            }
            if (qdrant != null) {
                // 임베딩 키와 같이 오류를 표시하고 관리자가 키를 다시 저장하게 한다. 키 없는 요청은 Qdrant가 거부한다.
                try { settings.setProperty("qdrantApiKey", decrypt(QDRANT, qdrant)); }
                catch (KeyUnavailable e) { keyError = true; LOG.error("Qdrant 키를 복호화할 수 없습니다: {}", e.getMessage()); }
            }
        }
        if (servers.isEmpty() && legacyUrl != null) {
            // 이관: 이전 단일 서버를 default 서버 하나로 바꾸고 기존 색인 모델 ID를 유지해 재색인 없이 시작한다. 다음 저장 때 새 형식으로 쓴다.
            try {
                servers = List.of(new RemoteServer("default", legacyUrl.trim(), legacyKey, number(settings, "timeoutSeconds", 1, 300)));
                if ("remote".equals(settings.getProperty("provider")) && !settings.containsKey(INDEX_MODEL_ID))
                    settings.setProperty(INDEX_MODEL_ID, RemoteEmbeddingProvider.legacyModelId(legacyUrl.trim(), settings.getProperty("model")));
            } catch (IllegalArgumentException e) { LOG.warn("이전 원격 임베딩 주소를 이관하지 못했습니다: 형식 오류"); }
        }
        // 업그레이드 호환: 비루프백 평문 주소가 남아 있으면 시작은 하되 벡터 기능만 끄고 상태로 알린다.
        try { checkTransport(settings, servers); }
        catch (IllegalArgumentException e) { disabledReason = e.getMessage(); LOG.warn("임베딩·Qdrant 기능을 비활성화했습니다: {}", e.getMessage()); }
        Providers.EmbeddingProvider provider = null;
        if (disabledReason == null) {
            try { provider = create(settings, servers); }
            catch (IllegalArgumentException e) { disabledReason = e.getMessage(); LOG.warn("임베딩 기능을 비활성화했습니다: 설정 오류"); }
        }
        if (disabledReason == null) index.configureVectors(provider, store(settings));
        else index.configureVectors(null, null);
        if (provider instanceof RemoteEmbeddingProvider created) {
            remote = created; pool = created.pool();
            RemoteServers.verifyLater(pool, servers, created::verify);
        }
    }
    private static int number(Properties p, String name, int min, int max) {
        int n = Integer.parseInt(p.getProperty(name));
        if (n < min || n > max) throw new IllegalArgumentException("임베딩 설정 범위 오류"); return n;
    }
    /** 인증정보를 보내는 원격 주소는 루프백이 아니면 HTTPS만 허용한다. 평문은 allowInsecureHttp=true일 때만. */
    private static void checkTransport(Properties p, List<RemoteServer> servers) {
        String allow = p.getProperty("allowInsecureHttp", "false");
        if (!Set.of("true", "false").contains(allow)) throw new IllegalArgumentException("allowInsecureHttp 설정 오류");
        if ("remote".equals(p.getProperty("provider"))) RemoteServers.requireTransport(servers, allow.equals("true"));
        if ("qdrant".equals(p.getProperty("vectorStore"))) RemoteUrl.require(p.getProperty("qdrantUrl"), allow.equals("true"));
    }
    private static boolean insecureInUse(Properties p, List<RemoteServer> servers) {
        return "remote".equals(p.getProperty("provider")) && RemoteServers.insecure(servers)
                || "qdrant".equals(p.getProperty("vectorStore")) && RemoteUrl.insecure(p.getProperty("qdrantUrl"));
    }
    private static VectorStore store(Properties p) {
        return switch (p.getProperty("vectorStore")) {
            case "lucene" -> null;
            case "qdrant" -> {
                int timeout = Integer.parseInt(p.getProperty("qdrantTimeoutSeconds"));
                yield new QdrantVectorStore(p.getProperty("qdrantUrl"), p.getProperty("qdrantCollection"), p.getProperty("qdrantApiKey", ""), timeout);
            }
            default -> throw new IllegalArgumentException("벡터 저장소 오류");
        };
    }
    private Providers.EmbeddingProvider create(Properties p, List<RemoteServer> list) {
        return switch (p.getProperty("provider")) {
            case "off" -> null;
            case "remote" -> {
                if (list.isEmpty()) throw new IllegalArgumentException("원격 서버를 하나 이상 등록하세요");
                int dimensions = number(p, "dimensions", 1, 1024), batch = number(p, "batchSize", 1, 16), timeout = number(p, "timeoutSeconds", 1, 300);
                var remotePool = new RemotePool("embedding", list);
                try { yield new RemoteEmbeddingProvider(remotePool, p.getProperty("model"), p.getProperty(INDEX_MODEL_ID), dimensions, batch, timeout); }
                catch (RuntimeException e) { remotePool.close(); throw e; }
            }
            case "builtin" -> Providers.full().embedding(p, folder);
            default -> throw new IllegalArgumentException("공급자 오류");
        };
    }
    public Map<String, Object> view() {
        synchronized (index) {
            var result = new LinkedHashMap<String, Object>();
            for (String field : settings.stringPropertyNames()) if (!SECRETS.contains(field) && !field.equals(INDEX_MODEL_ID)) result.put(field, settings.getProperty(field));
            result.put("builtinAvailable", ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent());
            result.put("servers", RemoteServers.view(servers, pool));
            result.put("hasKey", servers.stream().anyMatch(s -> !s.key().isEmpty())); result.put("hasQdrantKey", !settings.getProperty("qdrantApiKey", "").isEmpty()); result.put("keyError", keyError); result.put("status", index.embeddingStatus());
            result.put("disabledReason", disabledReason());
            result.put("insecureHttp", insecureInUse(settings, servers));
            return result;
        }
    }
    private String disabledReason() {
        if (disabledReason != null) return disabledReason;
        return pool == null ? "" : pool.disabledReason();
    }
    /** 상태 API용(주소·키 없음). */
    public Map<String, Object> health() {
        synchronized (index) { return RemoteServers.health(settings.getProperty("provider"), disabledReason(), pool); }
    }
    private record Prepared(Properties next, List<RemoteServer> servers, String blocked, long revision) { }
    public void save(Map<String, ?> input) throws Exception {
        Prepared prepared;
        synchronized (index) { prepared = prepare(input); }
        var next = prepared.next;
        var provider = prepared.blocked == null ? create(next, prepared.servers) : null;
        VectorStore store = null;
        try {
            // 원격 확인은 색인 잠금 밖에서 한다. 모델·차원이 다른 서버가 있으면 거부하고, 연결할 수 없는 서버는 down으로 허용한다.
            if (provider instanceof RemoteEmbeddingProvider created) {
                String mismatch = RemoteServers.mismatch(RemoteServers.verify(prepared.servers, created::verify));
                if (!mismatch.isEmpty()) throw new RemoteServers.Rejected(mismatch);
            }
            synchronized (index) {
                if (revision != prepared.revision) throw new IllegalStateException("다른 저장과 겹쳤습니다. 다시 시도하세요");
                if (prepared.blocked == null) store = store(next);
                var stored = new Properties(); stored.putAll(next); SECRETS.forEach(stored::remove);
                RemoteServers.store(stored, prepared.servers, EMBEDDING, this);
                if (!next.getProperty("qdrantApiKey", "").isEmpty()) stored.setProperty("encryptedQdrantKey", encrypt(QDRANT, next.getProperty("qdrantApiKey")));
                Path temporary = Files.createTempFile(folder, "embedding-", ".properties");
                try {
                    privateFile(temporary);
                    try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire embedding settings"); }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                index.configureVectors(provider, store); settings.clear(); settings.putAll(next); servers = prepared.servers;
                remote = provider instanceof RemoteEmbeddingProvider created ? created : null; pool = remote == null ? null : remote.pool();
                keyError = false; disabledReason = prepared.blocked; revision++;
            }
        } catch (Exception e) { if (provider != null) provider.close(); if (store != null) store.close(); throw e; }
    }
    private Prepared prepare(Map<String, ?> input) {
        var next = new Properties(); next.putAll(settings);
        List<RemoteServer> nextServers = servers;
        for (var e : input.entrySet()) {
            if (!FIELDS.contains(e.getKey()) || e.getValue() == null) throw new IllegalArgumentException("설정 오류");
            if (e.getKey().equals("servers") || LEGACY.contains(e.getKey())) continue;
            String value = scalar(e.getValue());
            // 빈 키는 기존 키 유지. clearKey 대신 off로 공급자를 끄거나 새 키를 입력한다.
            if (!SECRETS.contains(e.getKey()) || !value.isEmpty()) next.setProperty(e.getKey(), value);
        }
        // 모델이 바뀌면 이전 단일 서버 색인 모델 ID를 버리고 주소와 무관한 ID로 재색인한다.
        if (!Objects.equals(next.getProperty("model"), settings.getProperty("model"))) next.remove(INDEX_MODEL_ID);
        int timeout = number(next, "timeoutSeconds", 1, 300);
        if (input.containsKey("servers")) nextServers = RemoteServers.merge(servers, input.get("servers"), timeout, 300);
        else if (input.containsKey("baseUrl")) nextServers = RemoteServers.legacy(servers, scalar(input.get("baseUrl")).trim(), input.containsKey("apiKey") ? scalar(input.get("apiKey")) : "", timeout);
        else if (input.containsKey("apiKey") && !scalar(input.get("apiKey")).isEmpty() && !servers.isEmpty())
            nextServers = RemoteServers.legacy(servers, servers.getFirst().baseUrl(), scalar(input.get("apiKey")), servers.getFirst().timeoutSeconds());
        if (input.containsKey("clearKey") && "true".equals(scalar(input.get("clearKey")))) nextServers = RemoteServers.clearKey(nextServers, null);
        if (input.containsKey("clearServerKey")) nextServers = RemoteServers.clearKey(nextServers, scalar(input.get("clearServerKey")));
        // Qdrant 주소가 바뀌면 새 키를 함께 입력하지 않는 한 저장된 키를 새 주소로 보내지 않는다. clearQdrantKey=true는 명시적 삭제.
        if (!Objects.equals(next.getProperty("qdrantUrl"), settings.getProperty("qdrantUrl")) && scalar(input.containsKey("qdrantApiKey") ? input.get("qdrantApiKey") : "").isEmpty()
                || input.containsKey("clearQdrantKey") && "true".equals(scalar(input.get("clearQdrantKey")))) next.remove("qdrantApiKey");
        next.remove("clearQdrantKey");
        String blocked = null;
        try { checkTransport(next, nextServers); }
        catch (IllegalArgumentException e) {
            // 시작 때 꺼진 상태에서도 키 삭제는 허용한다. 기능은 계속 꺼 둔다.
            if (disabledReason == null || !input.keySet().stream().allMatch(k -> k.startsWith("clear"))) throw e;
            blocked = e.getMessage();
        }
        return new Prepared(next, nextServers, blocked, revision);
    }
    private static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException("설정 오류");
        String s = value.toString();
        if (s.length() > 4096) throw new IllegalArgumentException("설정 오류");
        return s;
    }
    /** 원격 호출이므로 색인 잠금을 잡지 않는다. 공급자 참조만 잠금 안에서 얻는다. */
    public Map<String, Object> test() { return Map.of("ok", true, "dimensions", index.testEmbedding().length); }
    /** 저장된 서버 하나에만 모델 목록·시험 임베딩을 보낸다. 결과는 서버 상태에도 반영한다. 원격 호출은 잠금 밖에서 한다. */
    public Map<String, Object> test(String server) {
        RemoteEmbeddingProvider current; RemoteServer target;
        synchronized (index) {
            current = remote;
            if (current == null) throw new IllegalStateException("원격 공급자 없음");
            target = servers.stream().filter(s -> s.name().equals(server)).findFirst().orElseThrow(() -> new NoSuchElementException("원격 서버 없음"));
        }
        var check = current.verify(target.name(), java.time.Duration.ofSeconds(Math.min(target.timeoutSeconds(), 10)));
        return Map.of("ok", "ok".equals(check.result()), "server", check.server(), "result", check.result(), "detail", check.detail());
    }
    /** 저장된 원격 임베딩 키 또는 Qdrant 키를 메모리·암호문 모두에서 지운다. */
    public void clearKey(boolean qdrant) throws Exception { save(Map.of(qdrant ? "clearQdrantKey" : "clearKey", "true")); }
    /** 원격 서버 하나의 키만 지운다. */
    public void clearServerKey(String server) throws Exception { save(Map.of("clearServerKey", server)); }
    public void reindex() { index.requestRebuild(); }
    private static void privateFile(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    /** DB 등 다른 저장소의 암호문 존재 여부. 있으면 키 파일이 없을 때 새 키를 만들지 않는다. */
    void storedSecrets(java.util.function.BooleanSupplier supplier) { storedSecrets = supplier; }
    private boolean embeddingCiphertext() throws Exception {
        if (!Files.exists(file)) return false;
        var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
        return stored.containsKey("encryptedKey") || stored.containsKey("encryptedQdrantKey") || RemoteServers.hasCiphertext(stored);
    }
    /** 이전 full.properties의 리랭커 키와 reranker·ocr·docling.properties의 서버 키. */
    private boolean fullCiphertext() throws Exception {
        Path full = folder.resolve("full.properties");
        if (Files.exists(full)) {
            var stored = new Properties(); try (var in = Files.newInputStream(full)) { stored.load(in); }
            if (stored.containsKey("encryptedKey")) return true;
        }
        // 리랭커·OCR·Docling 원격 서버 키.
        for (String name : List.of("reranker.properties", "ocr.properties", "docling.properties")) {
            Path path = folder.resolve(name);
            if (!Files.exists(path)) continue;
            var stored = new Properties(); try (var in = Files.newInputStream(path)) { stored.load(in); }
            if (RemoteServers.hasCiphertext(stored)) return true;
        }
        return false;
    }
    private boolean oidcCiphertext() throws Exception {
        Path path = folder.resolve("oidc.properties");
        if (!Files.exists(path)) return false;
        var stored = new Properties(); try (var in = Files.newInputStream(path)) { stored.load(in); }
        return stored.containsKey("encryptedSecret");
    }
    private byte[] key(boolean create, String purpose) throws Exception {
        Path path = folder.resolve("embedding.key");
        if (!Files.exists(path)) {
            // 임베딩 저장은 기존 임베딩 암호문을 통째로 바꾸므로 그 암호문만 있으면 새 키를 허용한다.
            if (!create || storedSecrets.getAsBoolean() || fullCiphertext() || oidcCiphertext() || (!purpose.equals(EMBEDDING) && !purpose.equals(QDRANT) && embeddingCiphertext()))
                throw new KeyUnavailable("암호화 키 파일(embedding.key)이 없지만 기존 암호문이 있습니다. " + RECOVERY_HINT);
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException e) { Files.createFile(path); }
            Files.write(path, bytes);
        }
        byte[] value = Files.readAllBytes(path); if (value.length != 32) throw new KeyUnavailable("암호화 키 길이 오류. " + RECOVERY_HINT); return value;
    }
    synchronized String encrypt(String purpose, String value) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true, purpose), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return VERSION + Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
    }
    static boolean legacy(String value) { return value != null && !value.startsWith(VERSION); }
    synchronized String decrypt(String purpose, String value) throws Exception {
        byte[] key = key(false, purpose);
        try {
            byte[] bytes = Base64.getDecoder().decode(legacy(value) ? value : value.substring(VERSION.length()));
            if (bytes.length < 28) throw new IllegalArgumentException("암호문 길이 오류");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            if (!legacy(value)) cipher.updateAAD(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (AEADBadTagException | IllegalArgumentException e) {
            throw new KeyUnavailable("암호화 키가 바뀌었거나 암호문이 손상되어 복호화할 수 없습니다. " + RECOVERY_HINT);
        }
    }
}

package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
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
    static final String TOTP = "totp", EMBEDDING = "embedding";
    /** 용도별 AAD를 적용한 형식. 접두어가 없는 기존 암호문은 AAD 없이 읽고 다음 저장 때 이 형식으로 바꾼다. */
    private static final String VERSION = "v2:";
    static final String RECOVERY_HINT = "embedding.key를 백업에서 복원하거나, 서버를 멈추고 --recover-admin --data=<데이터 폴더>로 TOTP를 해제한 뒤 임베딩 원격 키를 다시 저장하세요";
    /** 키 파일이 없거나 바뀌어 기존 암호문을 읽을 수 없는 상태. 새 키를 조용히 만들지 않는다. */
    static final class KeyUnavailable extends IllegalStateException { KeyUnavailable(String message) { super(message); } }
    private java.util.function.BooleanSupplier storedSecrets = () -> false;
    private boolean keyError;
    private static final Set<String> FIELDS = Set.of("provider", "baseUrl", "model", "apiKey", "dimensions", "batchSize", "timeoutSeconds",
            "modelPath", "tokenizerPath", "download", "modelUrl", "tokenizerUrl", "modelSha256", "tokenizerSha256");
    public EmbeddingSettings(SearchIndex index, Path dataFolder, Environment environment) throws Exception {
        this.index = index; folder = dataFolder; file = folder.resolve("embedding.properties");
        settings.setProperty("provider", "off"); settings.setProperty("baseUrl", "https://api.example.com/v1");
        settings.setProperty("model", "embedding-small"); settings.setProperty("dimensions", "384");
        settings.setProperty("batchSize", "2"); settings.setProperty("timeoutSeconds", "30");
        settings.setProperty("modelPath", "/data/models/multilingual-e5-small/model-int8.onnx");
        settings.setProperty("tokenizerPath", "/data/models/multilingual-e5-small/tokenizer.json"); settings.setProperty("download", "false");
        String revision = "761b726dd34fb83930e26aab4e9ac3899aa1fa78";
        settings.setProperty("modelUrl", "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + revision + "/onnx/model_quantized.onnx");
        settings.setProperty("tokenizerUrl", "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + revision + "/tokenizer.json");
        settings.setProperty("modelSha256", "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193");
        settings.setProperty("tokenizerSha256", "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39");
        for (String field : FIELDS) {
            String value = environment.getProperty("nasquire.embedding." + field);
            if (value != null) settings.setProperty(field, value);
        }
        if (Files.exists(file)) {
            var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
            String encrypted = (String)stored.remove("encryptedKey");
            settings.putAll(stored);
            if (encrypted != null) {
                try { settings.setProperty("apiKey", decrypt(EMBEDDING, encrypted)); }
                catch (KeyUnavailable e) { keyError = true; LOG.error("임베딩 원격 키를 복호화할 수 없습니다: {}", e.getMessage()); }
            }
        }
        index.configureEmbedding(create(settings));
    }
    private Providers.EmbeddingProvider create(Properties p) {
        return switch (p.getProperty("provider")) {
            case "off" -> null;
            case "remote" -> new RemoteEmbeddingProvider(p.getProperty("baseUrl"), p.getProperty("model"), p.getProperty("apiKey", ""),
                    Integer.parseInt(p.getProperty("dimensions")), Integer.parseInt(p.getProperty("batchSize")), Integer.parseInt(p.getProperty("timeoutSeconds")));
            case "builtin" -> new OnnxEmbeddingProvider(Path.of(p.getProperty("modelPath")), Path.of(p.getProperty("tokenizerPath")),
                    Boolean.parseBoolean(p.getProperty("download")) ? p.getProperty("modelUrl") : "",
                    Boolean.parseBoolean(p.getProperty("download")) ? p.getProperty("tokenizerUrl") : "", p.getProperty("modelSha256"), p.getProperty("tokenizerSha256"), folder);
            default -> throw new IllegalArgumentException("공급자 오류");
        };
    }
    public Map<String, Object> view() {
        synchronized (index) {
            var result = new LinkedHashMap<String, Object>();
            for (String field : settings.stringPropertyNames()) if (!field.equals("apiKey")) result.put(field, settings.getProperty(field));
            result.put("hasKey", !settings.getProperty("apiKey", "").isEmpty()); result.put("keyError", keyError); result.put("status", index.embeddingStatus());
            return result;
        }
    }
    public void save(Map<String, String> input) throws Exception {
        synchronized (index) {
            var next = new Properties(); next.putAll(settings);
            for (var e : input.entrySet()) {
                if (!FIELDS.contains(e.getKey()) || e.getValue() == null || e.getValue().length() > 4096) throw new IllegalArgumentException("설정 오류");
                // 빈 키는 기존 키 유지. clearKey 대신 off로 공급자를 끄거나 새 키를 입력한다.
                if (!e.getKey().equals("apiKey") || !e.getValue().isEmpty()) next.setProperty(e.getKey(), e.getValue());
            }
            var provider = create(next);
            try {
                var stored = new Properties(); stored.putAll(next); stored.remove("apiKey");
                if (!next.getProperty("apiKey", "").isEmpty()) stored.setProperty("encryptedKey", encrypt(EMBEDDING, next.getProperty("apiKey")));
                Path temporary = Files.createTempFile(folder, "embedding-", ".properties");
                try {
                    privateFile(temporary);
                    try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire embedding settings"); }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                index.configureEmbedding(provider); settings.clear(); settings.putAll(next); keyError = false;
            } catch (Exception e) { if (provider != null) provider.close(); throw e; }
        }
    }
    public Map<String, Object> test() {
        synchronized (index) { return Map.of("ok", true, "dimensions", index.testEmbedding().length); }
    }
    public void reindex() { index.requestRebuild(); }
    private static void privateFile(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    /** DB 등 다른 저장소의 암호문 존재 여부. 있으면 키 파일이 없을 때 새 키를 만들지 않는다. */
    void storedSecrets(java.util.function.BooleanSupplier supplier) { storedSecrets = supplier; }
    private boolean embeddingCiphertext() throws Exception {
        if (!Files.exists(file)) return false;
        var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
        return stored.containsKey("encryptedKey");
    }
    private byte[] key(boolean create, String purpose) throws Exception {
        Path path = folder.resolve("embedding.key");
        if (!Files.exists(path)) {
            // 임베딩 저장은 기존 임베딩 암호문을 통째로 바꾸므로 그 암호문만 있으면 새 키를 허용한다.
            if (!create || storedSecrets.getAsBoolean() || (!purpose.equals(EMBEDDING) && embeddingCiphertext()))
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

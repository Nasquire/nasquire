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
            if (encrypted != null) settings.setProperty("apiKey", decrypt(encrypted));
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
            result.put("hasKey", !settings.getProperty("apiKey", "").isEmpty()); result.put("status", index.embeddingStatus());
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
                if (!next.getProperty("apiKey", "").isEmpty()) stored.setProperty("encryptedKey", encrypt(next.getProperty("apiKey")));
                Path temporary = Files.createTempFile(folder, "embedding-", ".properties");
                try {
                    privateFile(temporary);
                    try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire embedding settings"); }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                index.configureEmbedding(provider); settings.clear(); settings.putAll(next);
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
    private byte[] key() throws Exception {
        Path path = folder.resolve("embedding.key");
        if (!Files.exists(path)) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException e) { Files.createFile(path); }
            Files.write(path, bytes);
        }
        byte[] value = Files.readAllBytes(path); if (value.length != 32) throw new IllegalStateException("암호화 키 오류"); return value;
    }
    synchronized String encrypt(String value) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
    }
    synchronized String decrypt(String value) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(value);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
        return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), java.nio.charset.StandardCharsets.UTF_8);
    }
}

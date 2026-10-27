package com.bluesounds.nasquire.app;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** OIDC 비밀은 공유 로컬 키와 독립 AAD로 암호화하며 공개 설정과 분리한다. */
@Service
class OidcSettings {
    private final Path file;
    private final EmbeddingSettings secrets;
    final String publicUrl;
    private Properties settings = new Properties();
    private long revision;
    private static final Set<String> FIELDS = Set.of("enabled", "issuer", "clientId", "scopes", "linkBy", "autoSignup", "groupClaim", "adminGroup", "buttonLabel");
    OidcSettings(Path dataFolder, EmbeddingSettings secrets, @Value("${nasquire.public-url:}") String publicUrl) throws Exception {
        this.secrets = secrets; this.publicUrl = publicUrl.replaceAll("/+$", ""); file = dataFolder.resolve("oidc.properties");
        settings.setProperty("enabled", "false"); settings.setProperty("issuer", ""); settings.setProperty("clientId", "");
        settings.setProperty("scopes", "openid email profile"); settings.setProperty("linkBy", "email"); settings.setProperty("autoSignup", "false");
        settings.setProperty("groupClaim", ""); settings.setProperty("adminGroup", ""); settings.setProperty("buttonLabel", "OpenID Connect");
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { settings.load(in); }
    }
    synchronized Properties snapshot() { var p = new Properties(); p.putAll(settings); return p; }
    synchronized long revision() { return revision; }
    String redirectUri() { return publicUrl + "/oidc/callback"; }
    static void url(String value) {
        if (!OAuthService.safeUri(value) || URI.create(value).getRawQuery() != null) throw new IllegalArgumentException("HTTPS 주소 필요 / HTTPS URL required");
    }
    private String reason(Properties p) {
        if (!"true".equals(p.getProperty("enabled"))) return "OIDC 꺼짐 / OIDC disabled";
        try {
            url(publicUrl); if (!URI.create(publicUrl).getRawPath().isEmpty()) throw new IllegalArgumentException();
            url(p.getProperty("issuer"));
            if (p.getProperty("clientId").isBlank() || !p.containsKey("encryptedSecret")) return "클라이언트 설정 필요 / Client configuration required";
            secret(p); return "";
        } catch (EmbeddingSettings.KeyUnavailable e) { return "암호화 키 복원 필요 / Restore embedding.key"; }
        catch (Exception e) { return "HTTPS 공개 기본 주소와 issuer 필요 / HTTPS public origin and issuer required"; }
    }
    synchronized Map<String,Object> view() {
        Map<String,Object> result = new LinkedHashMap<>(); FIELDS.forEach(k -> result.put(k, settings.getProperty(k)));
        result.put("hasSecret", settings.containsKey("encryptedSecret")); result.put("disabledReason", reason(settings)); result.put("redirectUri", redirectUri()); return result;
    }
    synchronized boolean enabled() { return reason(settings).isEmpty(); }
    String secret(Properties p) throws Exception { return p.containsKey("encryptedSecret") ? secrets.decrypt("oidc", p.getProperty("encryptedSecret")) : ""; }
    synchronized void save(Map<String,String> input) throws Exception {
        var next = snapshot();
        for (var entry : input.entrySet()) {
            if (!FIELDS.contains(entry.getKey()) && !Set.of("clientSecret", "clearSecret").contains(entry.getKey())) throw new IllegalArgumentException("설정 필드 오류");
            if (entry.getValue() == null || entry.getValue().length() > 2048 || entry.getValue().chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("설정 값 오류");
            if (FIELDS.contains(entry.getKey())) next.setProperty(entry.getKey(), entry.getValue().trim());
        }
        for (String k : List.of("enabled", "autoSignup")) if (!Set.of("true","false").contains(next.getProperty(k))) throw new IllegalArgumentException("설정 값 오류");
        if (!Set.of("email","sub").contains(next.getProperty("linkBy")) || !Arrays.asList(next.getProperty("scopes").split(" +")).contains("openid") || next.getProperty("buttonLabel").isBlank()) throw new IllegalArgumentException("설정 값 오류");
        if (!next.getProperty("issuer").equals(settings.getProperty("issuer"))) {
            next.remove("encryptedSecret"); if (input.getOrDefault("clientSecret", "").isEmpty()) next.setProperty("enabled", "false");
        }
        if ("true".equals(input.get("clearSecret"))) { next.remove("encryptedSecret"); next.setProperty("enabled", "false"); }
        if (!input.getOrDefault("clientSecret", "").isEmpty()) next.setProperty("encryptedSecret", secrets.encrypt("oidc", input.get("clientSecret")));
        if ("true".equals(next.getProperty("enabled")) && next.containsKey("encryptedSecret")) secret(next);
        if ("true".equals(next.getProperty("enabled")) && !reason(next).isEmpty()) throw new IllegalArgumentException(reason(next));
        Path temporary = Files.createTempFile(file.getParent(), "oidc-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var out = Files.newOutputStream(temporary)) { next.store(out, "Nasquire OIDC settings"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        settings = next; revision++;
    }
}

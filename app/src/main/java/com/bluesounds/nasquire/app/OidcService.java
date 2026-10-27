package com.bluesounds.nasquire.app;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** 모든 원격 응답은 5초/256 KiB, 리디렉트 금지. 저장된 관리자 지정 issuer만 사용한다. */
@Service
class OidcService {
    record Pending(String state, String nonce, String verifier, long created, long revision) { }
    private final OidcSettings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private JsonNode discovery;
    private long discoveredAt, revision = -1, keysAt, lastRefresh;
    private JWKSet keys;
    OidcService(OidcSettings settings, ObjectMapper json) { this.settings = settings; this.json = json; }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private JsonNode remote(HttpRequest request) throws Exception {
        // ofByteArray가 제한 전에 전체 본문을 모으지 않도록 구독 단계에서 크기를 제한한다.
        var future = client.sendAsync(request, info -> new LimitedBody());
        try {
            var response = future.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IllegalArgumentException("OIDC 원격 응답 오류");
            return json.readTree(response.body());
        } finally { future.cancel(true); }
    }
    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 262144) { subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("OIDC 응답 상한")); return; }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    private JsonNode get(String url) throws Exception { OidcSettings.url(url); return remote(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()); }
    synchronized JsonNode discover(boolean force) throws Exception {
        var p = settings.snapshot(); long now = System.currentTimeMillis();
        if (!force && discovery != null && revision == settings.revision() && now - discoveredAt < 300000) return discovery;
        String issuer = p.getProperty("issuer"); OidcSettings.url(issuer);
        JsonNode next = get(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        if (!issuer.equals(next.path("issuer").asText())) throw new IllegalArgumentException("OIDC issuer 불일치");
        for (String endpoint : List.of("authorization_endpoint", "token_endpoint", "jwks_uri")) OidcSettings.url(next.path(endpoint).asText());
        if (!contains(next.path("response_types_supported"), "code") || !contains(next.path("code_challenge_methods_supported"), "S256")) throw new IllegalArgumentException("OIDC code/S256 지원 필요");
        discovery = next; discoveredAt = now; revision = settings.revision(); keys = null; return next;
    }
    static boolean contains(JsonNode array, String value) { for (var item : array) if (value.equals(item.asText())) return true; return false; }
    Pending pending() { return new Pending(OAuthService.random(), OAuthService.random(), OAuthService.random(), System.currentTimeMillis(), settings.revision()); }
    String authorize(Pending pending) throws Exception {
        JsonNode d = discover(false); var p = settings.snapshot();
        String base = d.path("authorization_endpoint").asText();
        return base + "?response_type=code&client_id=" + encode(p.getProperty("clientId")) + "&redirect_uri=" + encode(settings.redirectUri()) + "&scope=" + encode(p.getProperty("scopes")) + "&state=" + encode(pending.state) + "&nonce=" + encode(pending.nonce) + "&code_challenge_method=S256&code_challenge=" + OAuthService.challenge(pending.verifier);
    }
    JWTClaimsSet exchange(String code, Pending pending) throws Exception {
        if (code == null || code.length() > 8192 || pending.revision != settings.revision()) throw new IllegalArgumentException("OIDC 시도 만료");
        var p = settings.snapshot(); JsonNode d = discover(false);
        String body = "grant_type=authorization_code&code=" + encode(code) + "&redirect_uri=" + encode(settings.redirectUri()) + "&code_verifier=" + encode(pending.verifier);
        var request = HttpRequest.newBuilder(URI.create(d.path("token_endpoint").asText())).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded");
        String secret = settings.secret(p);
        if (d.path("token_endpoint_auth_methods_supported").isMissingNode() || contains(d.path("token_endpoint_auth_methods_supported"), "client_secret_basic"))
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((encode(p.getProperty("clientId")) + ":" + encode(secret)).getBytes(StandardCharsets.UTF_8)));
        else if (contains(d.path("token_endpoint_auth_methods_supported"), "client_secret_post")) body += "&client_id=" + encode(p.getProperty("clientId")) + "&client_secret=" + encode(secret);
        else throw new IllegalArgumentException("OIDC 클라이언트 인증 방식 오류");
        String token = remote(request.POST(HttpRequest.BodyPublishers.ofString(body)).build()).path("id_token").asText();
        return validate(token, pending.nonce);
    }
    synchronized JWTClaimsSet validate(String raw, String nonce) throws Exception {
        if (raw.length() > 65536) throw new IllegalArgumentException("OIDC 토큰 상한");
        SignedJWT token = SignedJWT.parse(raw); var h = token.getHeader(); String alg = h.getAlgorithm().getName();
        if (!Set.of("RS256", "ES256").contains(alg) || h.getKeyID() == null || h.getCriticalParams() != null) throw new IllegalArgumentException("OIDC 서명 방식 오류");
        JsonNode d = discover(false); long now = System.currentTimeMillis();
        if (keys == null || now - keysAt > 300000) { keys = JWKSet.parse(get(d.path("jwks_uri").asText()).toString()); keysAt = now; }
        JWK key = keys.getKeyByKeyId(h.getKeyID());
        if (key == null && now - lastRefresh > 1000) { lastRefresh = now; keys = JWKSet.parse(get(d.path("jwks_uri").asText()).toString()); keysAt = now; key = keys.getKeyByKeyId(h.getKeyID()); }
        if (key == null || key.isPrivate() || key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse()) || key.getAlgorithm() != null && !h.getAlgorithm().equals(key.getAlgorithm())) throw new IllegalArgumentException("OIDC 서명 키 오류");
        boolean verified = alg.equals("RS256") && key instanceof RSAKey rsa && rsa.size() >= 2048 && token.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))
                || alg.equals("ES256") && key instanceof ECKey ec && Curve.P_256.equals(ec.getCurve()) && token.verify(new ECDSAVerifier(ec.toECPublicKey()));
        if (!verified) throw new IllegalArgumentException("OIDC 서명 오류");
        var claims = token.getJWTClaimsSet(); var p = settings.snapshot(); String id = p.getProperty("clientId");
        if (!p.getProperty("issuer").equals(claims.getIssuer()) || !claims.getAudience().contains(id) || claims.getAudience().size() > 1 && !id.equals(claims.getStringClaim("azp"))
                || claims.getClaim("azp") != null && !id.equals(claims.getStringClaim("azp")) || claims.getExpirationTime() == null || claims.getExpirationTime().getTime() < now - 30000
                || claims.getIssueTime() == null || claims.getIssueTime().getTime() > now + 30000 || claims.getIssueTime().getTime() < now - 600000
                || claims.getNotBeforeTime() != null && claims.getNotBeforeTime().getTime() > now + 30000 || !nonce.equals(claims.getStringClaim("nonce"))
                || claims.getSubject() == null || claims.getSubject().isBlank() || claims.getSubject().length() > 255) throw new IllegalArgumentException("OIDC 클레임 오류");
        return claims;
    }
}

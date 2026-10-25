package com.bluesounds.nasquire.app;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 단일 내장 서버의 인가 상태. 원자적 소비·회전과 재사용 철회는 같은 트랜잭션에서 처리한다. */
@Service
class OAuthService {
    static final String SCOPE = "mcp:read";
    static final long LIFETIME = 30L * 86400000;
    static final int MAX_CLIENTS = 1000;
    int maxClients = MAX_CLIENTS;
    private final JdbcTemplate db;
    private final Storage storage;
    private final TransactionTemplate tx;
    final String issuer, resource, disabledReason;
    Clock clock = Clock.systemUTC();
    static class Error extends RuntimeException {
        final String error; final int status;
        Error(String error, int status) { super(error); this.error = error; this.status = status; }
    }
    static Error invalid(String error) { return new Error(error, 400); }
    OAuthService(JdbcTemplate db, Storage storage, PlatformTransactionManager manager,
            @Value("${nasquire.oauth.enabled:false}") boolean enabled, @Value("${nasquire.public-url:}") String url) {
        this.db = db; this.storage = storage; this.tx = new TransactionTemplate(manager);
        issuer = url.replaceAll("/+$", ""); resource = issuer + "/mcp";
        boolean valid = false;
        try { URI u = URI.create(issuer); valid = safeUri(issuer) && resource.length() <= 2048 && u.getRawQuery() == null && (u.getRawPath() == null || u.getRawPath().isEmpty()); }
        catch (IllegalArgumentException ignored) { }
        disabledReason = !enabled ? "OAuth is disabled / OAuth 설정이 꺼져 있습니다" : !valid ? "HTTPS public URL required (origin only) / HTTPS 공개 기본 주소가 필요합니다" : "";
        db.execute("CREATE TABLE IF NOT EXISTS oauth_clients (id VARCHAR(64) PRIMARY KEY, name VARCHAR(200) NOT NULL, redirects VARCHAR(22000) NOT NULL, created BIGINT NOT NULL, approved BOOLEAN DEFAULT FALSE NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS oauth_grants (id VARCHAR(64) PRIMARY KEY, client_id VARCHAR(64) NOT NULL, redirect_uri VARCHAR(2048) NOT NULL, audience VARCHAR(2048) NOT NULL, sources VARCHAR(4000) NOT NULL, code_hash VARCHAR(64) UNIQUE NOT NULL, challenge VARCHAR(43) NOT NULL, code_expires BIGINT NOT NULL, consumed BOOLEAN DEFAULT FALSE NOT NULL, expires BIGINT NOT NULL, revoked BOOLEAN DEFAULT FALSE NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS oauth_tokens (id VARCHAR(64) PRIMARY KEY, token_hash VARCHAR(64) UNIQUE NOT NULL, grant_id VARCHAR(64) NOT NULL, kind VARCHAR(10) NOT NULL, expires BIGINT NOT NULL, used BOOLEAN DEFAULT FALSE NOT NULL)");
        db.execute("CREATE INDEX IF NOT EXISTS oauth_tokens_grant ON oauth_tokens(grant_id)");
        db.execute("CREATE INDEX IF NOT EXISTS oauth_grants_client ON oauth_grants(client_id)");
        db.execute("CREATE INDEX IF NOT EXISTS oauth_grants_expiry ON oauth_grants(expires)");
        storage.oauthIdentity(this::identity);
    }
    boolean enabled() { return disabledReason.isEmpty(); }
    void requireEnabled() { if (!enabled()) throw new Error("not_found", 404); }
    long now() { return clock.millis(); }
    static boolean safeUri(String value) {
        try {
            URI u = URI.create(value);
            return value.length() <= 2048 && u.isAbsolute() && u.getHost() != null && u.getRawUserInfo() == null && u.getFragment() == null
                    && value.chars().noneMatch(c -> c <= 32 || c == 127) && ("https".equals(u.getScheme()) || "http".equals(u.getScheme()) && Set.of("127.0.0.1", "[::1]", "localhost").contains(u.getHost()));
        } catch (IllegalArgumentException e) { return false; }
    }
    static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    synchronized Map<String, Object> register(Map<String, Object> input) {
        requireEnabled(); cleanup();
        // RFC 7591 §2: 이해하지 않는 선택 메타데이터는 저장/조회하지 않고 무시한다.
        // SDK의 application_type 등은 인증 방식·소스 권한·audience에 영향을 주지 않는다.
        if (input.containsKey("scope") && !SCOPE.equals(input.get("scope"))) throw invalid("invalid_client_metadata");
        Object rawName = input.getOrDefault("client_name", "MCP client");
        if (!(rawName instanceof String name) || name.isBlank() || name.length() > 200 || name.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("invalid_client_metadata");
        if (!"none".equals(input.getOrDefault("token_endpoint_auth_method", "none"))) throw invalid("invalid_client_metadata");
        if (input.containsKey("grant_types") && (!(input.get("grant_types") instanceof List<?> gs) || gs.isEmpty() || gs.size() > 2 || !Set.of("authorization_code", "refresh_token").containsAll(gs))) throw invalid("invalid_client_metadata");
        if (input.containsKey("response_types") && !List.of("code").equals(input.get("response_types"))) throw invalid("invalid_client_metadata");
        if (!(input.get("redirect_uris") instanceof List<?> redirects) || redirects.isEmpty() || redirects.size() > 10
                || redirects.stream().anyMatch(v -> !(v instanceof String s) || !safeUri(s))) throw invalid("invalid_redirect_uri");
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            if (db.queryForObject("SELECT COUNT(*) FROM oauth_clients", Integer.class) >= maxClients) {
                var candidates = db.queryForList("SELECT c.id FROM oauth_clients c WHERE c.approved=FALSE AND NOT EXISTS (SELECT 1 FROM oauth_grants g WHERE g.client_id=c.id) ORDER BY c.created,c.id LIMIT 1", String.class);
                if (candidates.isEmpty()) throw new Error("temporarily_unavailable", 503);
                db.update("DELETE FROM oauth_clients WHERE id=? AND approved=FALSE", candidates.getFirst());
                audit("register-evict", 200);
            }
            db.update("INSERT INTO oauth_clients(id,name,redirects,created) VALUES (?,?,?,?)", id, name, String.join("\n", redirects.stream().map(Object::toString).distinct().toList()), now());
            audit("register", 201);
        });
        return Map.of("client_id", id, "client_name", name, "redirect_uris", redirects, "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"), "response_types", List.of("code"), "client_id_issued_at", now() / 1000, "scope", SCOPE);
    }
    private void cleanup() {
        db.update("DELETE FROM oauth_tokens WHERE grant_id IN (SELECT id FROM oauth_grants WHERE expires<?)", now() - 86400000);
        db.update("DELETE FROM oauth_grants WHERE expires<?", now() - 86400000);
        db.update("DELETE FROM oauth_clients WHERE approved=FALSE AND created<?", now() - 86400000);
    }
    Map<String, Object> client(String id) {
        if (id == null || id.length() > 64) throw invalid("invalid_client");
        var rows = db.queryForList("SELECT * FROM oauth_clients WHERE id=? AND (approved=TRUE OR created>?)", id, now() - 86400000);
        if (rows.isEmpty()) throw invalid("invalid_client"); return rows.getFirst();
    }
    record Authorization(String client, String redirect, String state, String challenge, long created) { }
    Authorization validate(Map<String, String> p) {
        requireEnabled();
        var c = client(p.get("client_id"));
        String redirect = p.get("redirect_uri");
        if (redirect == null || !Arrays.asList(c.get("REDIRECTS").toString().split("\n")).contains(redirect)) throw invalid("invalid_request");
        if (!"code".equals(p.get("response_type"))) throw invalid("unsupported_response_type");
        if (!"S256".equals(p.get("code_challenge_method")) || p.get("code_challenge") == null || !p.get("code_challenge").matches("[A-Za-z0-9_-]{43}")) throw invalid("invalid_request");
        checkResource(p.get("resource"));
        if (!SCOPE.equals(p.getOrDefault("scope", SCOPE))) throw invalid("invalid_scope");
        String state = p.getOrDefault("state", "");
        if (state.length() > 1024 || state.chars().anyMatch(v -> v < 32 || v == 127)) throw invalid("invalid_request");
        return new Authorization(p.get("client_id"), redirect, state, p.get("code_challenge"), now());
    }
    void checkResource(String value) { if (!resource.equals(value)) throw invalid("invalid_target"); }
    synchronized String approve(Authorization a, List<String> sources) {
        requireEnabled(); client(a.client);
        if (now() - a.created > 600000 || sources.isEmpty() || sources.size() > 100) throw invalid("invalid_request");
        for (String source : sources) storage.source(source);
        if (db.queryForObject("SELECT COUNT(*) FROM oauth_grants WHERE client_id=? AND expires>?", Integer.class, a.client, now()) >= 100) throw invalid("temporarily_unavailable");
        String code = random(), family = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO oauth_grants(id,client_id,redirect_uri,audience,sources,code_hash,challenge,code_expires,expires) VALUES (?,?,?,?,?,?,?,?,?)", family, a.client, a.redirect, resource, String.join("\n", new LinkedHashSet<>(sources)), SecurityConfiguration.hash(code), a.challenge, now() + 60000, now() + LIFETIME);
            db.update("UPDATE oauth_clients SET approved=TRUE WHERE id=?", a.client);
            audit("approve", 200);
        });
        return code;
    }
    synchronized Map<String, Object> token(Map<String, String> p) {
        requireEnabled(); client(p.get("client_id")); checkResource(p.get("resource"));
        // 오류는 트랜잭션 밖에서 던져 재사용으로 인한 철회를 반드시 커밋한다.
        Object result = tx.execute(status -> exchange(p));
        if (result instanceof Error error) throw error;
        @SuppressWarnings("unchecked") var response = (Map<String, Object>)result; return response;
    }
    private Object exchange(Map<String, String> p) {
        String type = p.get("grant_type"), family;
        if ("authorization_code".equals(type)) {
            String code = p.get("code"), verifier = p.get("code_verifier");
            if (code == null || code.length() > 128 || verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) return invalid("invalid_grant");
            var rows = db.queryForList("SELECT * FROM oauth_grants WHERE code_hash=? AND client_id=?", SecurityConfiguration.hash(code), p.get("client_id"));
            if (rows.isEmpty()) return invalid("invalid_grant");
            var g = rows.getFirst(); family = g.get("ID").toString();
            if (!Objects.equals(g.get("REDIRECT_URI"), p.get("redirect_uri")) || !Objects.equals(g.get("AUDIENCE"), resource)
                    || !MessageDigest.isEqual(g.get("CHALLENGE").toString().getBytes(StandardCharsets.US_ASCII), challenge(verifier).getBytes(StandardCharsets.US_ASCII))) return invalid("invalid_grant");
            if (Boolean.TRUE.equals(g.get("CONSUMED"))) { revokeFamily(family); audit("code-reuse", 400); return invalid("invalid_grant"); }
            if (Boolean.TRUE.equals(g.get("REVOKED")) || ((Number)g.get("CODE_EXPIRES")).longValue() <= now()) return invalid("invalid_grant");
            db.update("UPDATE oauth_grants SET consumed=TRUE WHERE id=?", family);
            audit("issue", 200);
        } else if ("refresh_token".equals(type)) {
            String value = p.get("refresh_token");
            if (value == null || value.length() > 128) return invalid("invalid_grant");
            var rows = db.queryForList("SELECT t.id AS token_id,t.used,t.expires AS token_expires,g.* FROM oauth_tokens t JOIN oauth_grants g ON t.grant_id=g.id WHERE t.token_hash=? AND t.kind='refresh' AND g.client_id=?", SecurityConfiguration.hash(value), p.get("client_id"));
            if (rows.isEmpty()) return invalid("invalid_grant");
            var g = rows.getFirst(); family = g.get("ID").toString();
            if (!resource.equals(g.get("AUDIENCE"))) return invalid("invalid_grant");
            if (Boolean.TRUE.equals(g.get("USED"))) { revokeFamily(family); audit("refresh-reuse", 400); return invalid("invalid_grant"); }
            if (Boolean.TRUE.equals(g.get("REVOKED")) || ((Number)g.get("TOKEN_EXPIRES")).longValue() <= now() || ((Number)g.get("EXPIRES")).longValue() <= now()) return invalid("invalid_grant");
            if (p.containsKey("scope") && !SCOPE.equals(p.get("scope"))) return invalid("invalid_scope");
            db.update("UPDATE oauth_tokens SET used=TRUE WHERE id=?", g.get("TOKEN_ID"));
            audit("refresh", 200);
        } else return invalid("unsupported_grant_type");
        if (db.queryForObject("SELECT COUNT(*) FROM oauth_tokens WHERE grant_id=?", Integer.class, family) >= 4096) {
            revokeFamily(family); audit("rotation-limit", 400); return invalid("invalid_grant");
        }
        long deadline = db.queryForObject("SELECT expires FROM oauth_grants WHERE id=?", Long.class, family);
        String access = random(), refresh = random();
        long expires = Math.min(now() + 3600000, deadline);
        insertToken(access, family, "access", expires); insertToken(refresh, family, "refresh", deadline);
        return Map.of("access_token", access, "refresh_token", refresh, "token_type", "Bearer", "expires_in", (expires - now()) / 1000, "scope", SCOPE);
    }
    private void insertToken(String value, String family, String kind, long expires) {
        db.update("INSERT INTO oauth_tokens(id,token_hash,grant_id,kind,expires) VALUES (?,?,?,?,?)", "oauth:" + UUID.randomUUID(), SecurityConfiguration.hash(value), family, kind, expires);
    }
    synchronized Storage.Identity authenticate(String hash) {
        if (!enabled()) return null;
        var rows = db.queryForList("SELECT t.id,g.sources FROM oauth_tokens t JOIN oauth_grants g ON t.grant_id=g.id JOIN oauth_clients c ON c.id=g.client_id WHERE t.token_hash=? AND t.kind='access' AND t.expires>? AND g.expires>? AND g.revoked=FALSE AND g.audience=?", hash, now(), now(), resource);
        return rows.isEmpty() ? null : toIdentity(rows.getFirst());
    }
    synchronized Storage.Identity identity(String id) {
        if (!enabled()) return null;
        var hashes = db.queryForList("SELECT token_hash FROM oauth_tokens WHERE id=? AND kind='access'", String.class, id);
        return hashes.isEmpty() ? null : authenticate(hashes.getFirst());
    }
    private Storage.Identity toIdentity(Map<String, Object> row) {
        Set<String> existing = new HashSet<>(); storage.sources().forEach(s -> existing.add(s.id()));
        return new Storage.Identity(row.get("ID").toString(), row.get("SOURCES").toString().lines().filter(existing::contains).toList());
    }
    synchronized void revoke(String client, String token) {
        requireEnabled(); client(client);
        if (token == null || token.isBlank()) throw invalid("invalid_request");
        if (token.length() > 128) return;
        tx.executeWithoutResult(status -> {
            for (String family : db.queryForList("SELECT g.id FROM oauth_grants g JOIN oauth_tokens t ON t.grant_id=g.id WHERE t.token_hash=? AND g.client_id=?", String.class, SecurityConfiguration.hash(token), client)) revokeFamily(family);
            audit("revoke", 200);
        });
    }
    private void revokeFamily(String id) { db.update("UPDATE oauth_grants SET revoked=TRUE WHERE id=?", id); }
    synchronized void adminRevoke(String kind, String id) {
        tx.executeWithoutResult(status -> {
            if ("client".equals(kind)) {
                db.update("UPDATE oauth_grants SET revoked=TRUE WHERE client_id=?", id);
                db.update("DELETE FROM oauth_clients WHERE id=?", id);
            } else if ("grant".equals(kind)) revokeFamily(id);
            else throw invalid("invalid_request");
            audit("admin-revoke", 200);
        });
    }
    synchronized Map<String, Object> view() {
        cleanup();
        return Map.of("enabled", enabled(), "disabledReason", disabledReason,
                "clients", db.queryForList("SELECT id,name,redirects,created,approved FROM oauth_clients ORDER BY created DESC"),
                "grants", db.queryForList("SELECT id,client_id,sources,expires,revoked FROM oauth_grants ORDER BY expires DESC"));
    }
    void audit(String action, int status) { storage.audit("oauth", "oauth-" + action, status); }
}

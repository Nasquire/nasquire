package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;

@Repository
public class Storage {
    public record Token(String id, String name, long expiresAt, List<String> sources, boolean revoked, boolean readOnly) { }
    public record Identity(String tokenId, List<String> sources) { }
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public Storage(JdbcTemplate db) {
        this.db = db;
        db.execute("CREATE TABLE IF NOT EXISTS administrator (id INT PRIMARY KEY CHECK(id=1), username VARCHAR(100) NOT NULL, password_hash VARCHAR(100) NOT NULL)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(512)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS totp_step BIGINT DEFAULT -1 NOT NULL");
        db.execute("CREATE TABLE IF NOT EXISTS recovery_codes (code_hash VARCHAR(64) PRIMARY KEY)");
        db.execute("CREATE TABLE IF NOT EXISTS sources (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, root VARCHAR(4096) NOT NULL, includes CLOB NOT NULL, excludes CLOB NOT NULL)");
        db.execute("ALTER TABLE sources ADD COLUMN IF NOT EXISTS kind VARCHAR(20) DEFAULT 'directory' NOT NULL");
        db.execute("CREATE TABLE IF NOT EXISTS tokens (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, expires_at BIGINT NOT NULL, sources CLOB NOT NULL, revoked BOOLEAN DEFAULT FALSE)");
        db.execute("CREATE TABLE IF NOT EXISTS audit (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, occurred_at BIGINT NOT NULL, actor VARCHAR(100) NOT NULL, action VARCHAR(200) NOT NULL, status INT NOT NULL)");
    }
    public boolean configured() { return db.queryForObject("SELECT COUNT(*) FROM administrator", Integer.class) > 0; }
    public synchronized void setup(String username, String password) {
        if (configured()) throw new IllegalStateException("이미 설정되었습니다");
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,100}") || password == null
                || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("사용자명 3~100자, 비밀번호 12자 이상/UTF-8 72바이트 이하가 필요합니다");
        db.update("INSERT INTO administrator(id,username,password_hash) VALUES (1,?,?)", username, passwords.encode(password));
    }
    public org.springframework.security.core.userdetails.UserDetails user(String username) {
        var users = db.query("SELECT * FROM administrator WHERE username=?", (rs, row) ->
                org.springframework.security.core.userdetails.User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash")).roles("ADMIN").build(), username);
        if (users.isEmpty()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("인증 실패");
        return users.getFirst();
    }
    public List<Source> sources() {
        return db.query("SELECT * FROM sources ORDER BY name", (rs, row) -> new Source(rs.getString("id"), rs.getString("name"),
                Path.of(rs.getString("root")), lines(rs.getString("includes")), lines(rs.getString("excludes")), rs.getString("kind")));
    }
    public Source source(String id) { return sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("소스 없음")); }
    public void save(Source s) {
        db.update("MERGE INTO sources(id,name,root,includes,excludes,kind) KEY(id) VALUES (?,?,?,?,?,?)", s.id(), s.name(), s.root().toString(), String.join("\n", s.includes()), String.join("\n", s.excludes()), s.kind());
    }
    public void deleteSource(String id) {
        db.update("DELETE FROM trash WHERE source_id=?", id);
        db.update("UPDATE shares SET revoked=TRUE WHERE source_id=?", id);
        db.update("DELETE FROM sources WHERE id=?", id);
    }
    public void addToken(String id, String name, String hash, long expires, List<String> sources) {
        db.update("INSERT INTO tokens(id,name,token_hash,expires_at,sources) VALUES (?,?,?,?,?)", id, name, hash, expires, String.join("\n", sources));
    }
    public List<Token> tokens() {
        return db.query("SELECT id,name,expires_at,sources,revoked FROM tokens ORDER BY name", (rs, row) ->
                new Token(rs.getString("id"), rs.getString("name"), rs.getLong("expires_at"), lines(rs.getString("sources")), rs.getBoolean("revoked"), true));
    }
    public Identity authenticate(String hash) {
        var found = db.query("SELECT id,sources FROM tokens WHERE token_hash=? AND revoked=FALSE AND expires_at>?", (rs, row) ->
                new Identity(rs.getString("id"), lines(rs.getString("sources"))), hash, Instant.now().toEpochMilli());
        return found.isEmpty() ? null : found.getFirst();
    }
    // 다운로드 티켓 사용 때 OAuth 토큰도 현재 승인·만료·소스 범위를 다시 확인한다.
    private java.util.function.Function<String, Identity> oauthIdentity = id -> null;
    public void oauthIdentity(java.util.function.Function<String, Identity> resolver) { this.oauthIdentity = resolver; }
    public Identity identity(String id) {
        if (id.startsWith("oauth:")) return oauthIdentity.apply(id);
        var rows = db.query("SELECT id,sources FROM tokens WHERE id=? AND revoked=FALSE AND expires_at>?",
                (r, n) -> new Identity(r.getString("id"), lines(r.getString("sources"))), id, Instant.now().toEpochMilli());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void revokeShares(String source, Path path) {
        for (var row : db.queryForList("SELECT id,path FROM shares WHERE source_id=? AND revoked=FALSE", source)) {
            Path shared = Path.of(row.get("PATH").toString());
            if (shared.equals(path) || shared.startsWith(path)) {
                db.update("UPDATE shares SET revoked=TRUE WHERE id=?", row.get("ID"));
                audit("admin", "share-revoke-path", 200);
            }
        }
    }
    public void revoke(String id) { db.update("UPDATE tokens SET revoked=TRUE WHERE id=?", id); }
    public void audit(String actor, String action, int status) {
        db.update("INSERT INTO audit(occurred_at,actor,action,status) VALUES (?,?,?,?)", Instant.now().toEpochMilli(), actor, action, status);
        db.update("DELETE FROM audit WHERE id <= (SELECT COALESCE(MAX(id),0)-10000 FROM audit)");
    }
    public List<Map<String, Object>> audit() { return db.queryForList("SELECT occurred_at,actor,action,status FROM audit ORDER BY id DESC LIMIT 200"); }
    private static List<String> lines(String value) { return value == null || value.isBlank() ? List.of() : value.lines().toList(); }
}

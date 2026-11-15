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
    public record Token(String id, String name, long expiresAt, List<String> sources, boolean revoked, boolean readOnly, int ownerId, String ownerName) { }
    public record Identity(String tokenId, List<String> sources) { }
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public Storage(JdbcTemplate db) {
        this.db = db;
        db.execute("CREATE TABLE IF NOT EXISTS administrator (id INT PRIMARY KEY, username VARCHAR(100) NOT NULL, password_hash VARCHAR(100) NOT NULL)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(512)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS totp_step BIGINT DEFAULT -1 NOT NULL");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS generation BIGINT DEFAULT 0 NOT NULL");
        // 기존 단일 행의 값과 ID를 그대로 보존하며 다중 계정 제약으로 확장한다.
        for (var constraint : db.queryForList("SELECT t.CONSTRAINT_NAME,c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON t.CONSTRAINT_CATALOG=c.CONSTRAINT_CATALOG AND t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_NAME='ADMINISTRATOR' AND t.CONSTRAINT_TYPE='CHECK'")) {
            String clause = constraint.get("CHECK_CLAUSE").toString().replaceAll("[\\s\"()]", "");
            if (clause.equalsIgnoreCase("ID=1") || clause.equalsIgnoreCase("1=ID"))
                db.execute("ALTER TABLE administrator DROP CONSTRAINT \"" + constraint.get("CONSTRAINT_NAME").toString().replace("\"", "\"\"") + "\"");
        }
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS role VARCHAR(10) DEFAULT 'ADMIN' NOT NULL");
        db.execute("ALTER TABLE administrator ALTER COLUMN role SET DEFAULT 'USER'");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE NOT NULL");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE NOT NULL");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS oidc_match VARCHAR(320)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS oidc_match_by VARCHAR(10)");
        db.execute("ALTER TABLE administrator ADD COLUMN IF NOT EXISTS oidc_issuer VARCHAR(2048)");
        db.execute("CREATE TABLE IF NOT EXISTS oidc_links (user_id INT PRIMARY KEY, issuer VARCHAR(2048) NOT NULL, subject VARCHAR(255) NOT NULL, UNIQUE(issuer,subject))");
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS account_username ON administrator(username)");
        db.execute("CREATE TABLE IF NOT EXISTS account_sources (user_id INT NOT NULL, source_id VARCHAR(36) NOT NULL, PRIMARY KEY(user_id,source_id))");
        db.execute("CREATE TABLE IF NOT EXISTS account_lock (id INT PRIMARY KEY)");
        db.update("MERGE INTO account_lock KEY(id) VALUES (1)");
        db.execute("CREATE TABLE IF NOT EXISTS recovery_codes (code_hash VARCHAR(64) PRIMARY KEY)");
        db.execute("ALTER TABLE recovery_codes ADD COLUMN IF NOT EXISTS user_id INT DEFAULT 1 NOT NULL");
        db.execute("CREATE TABLE IF NOT EXISTS sources (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, root VARCHAR(4096) NOT NULL, includes CLOB NOT NULL, excludes CLOB NOT NULL)");
        db.execute("ALTER TABLE sources ADD COLUMN IF NOT EXISTS kind VARCHAR(20) DEFAULT 'directory' NOT NULL");
        // 소스별 "항상 Docling"(기존 소스는 false).
        db.execute("ALTER TABLE sources ADD COLUMN IF NOT EXISTS always_docling BOOLEAN DEFAULT FALSE NOT NULL");
        db.execute("CREATE TABLE IF NOT EXISTS tokens (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, expires_at BIGINT NOT NULL, sources CLOB NOT NULL, revoked BOOLEAN DEFAULT FALSE)");
        db.execute("ALTER TABLE tokens ADD COLUMN IF NOT EXISTS owner_id INT DEFAULT 1 NOT NULL");
        db.execute("CREATE TABLE IF NOT EXISTS audit (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, occurred_at BIGINT NOT NULL, actor VARCHAR(100) NOT NULL, action VARCHAR(200) NOT NULL, status INT NOT NULL)");
        db.execute("ALTER TABLE audit ADD COLUMN IF NOT EXISTS actor_id INT");
        db.execute("ALTER TABLE audit ADD COLUMN IF NOT EXISTS actor_name VARCHAR(100)");
    }
    public boolean configured() { return db.queryForObject("SELECT COUNT(*) FROM administrator", Integer.class) > 0; }
    static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,100}")) throw new IllegalArgumentException("사용자명 오류 / Invalid username");
    }
    public synchronized void setup(String username, String password) {
        if (configured()) throw new IllegalStateException("이미 설정되었습니다");
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,100}"))
            throw new IllegalArgumentException("사용자명은 영문·숫자·_.- 3~100자 / Username must contain 3–100 ASCII letters, digits or _.-");
        PasswordPolicy.validate(password);
        db.update("INSERT INTO administrator(id,username,password_hash,role) VALUES (1,?,?,'ADMIN')", username, passwords.encode(password));
    }
    public org.springframework.security.core.userdetails.UserDetails user(String username) {
        var users = db.query("SELECT * FROM administrator WHERE username=? AND active=TRUE", (rs, row) ->
                org.springframework.security.core.userdetails.User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash")).roles(rs.getString("role")).build(), username);
        if (users.isEmpty()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("인증 실패");
        return users.getFirst();
    }
    record Account(int id, String username, String role, boolean active, long generation) { }
    Account account(int id) {
        return db.query("SELECT id,username,role,active,generation FROM administrator WHERE id=? AND deleted=FALSE", (r,n) ->
                new Account(r.getInt(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getLong(5)), id).stream().findFirst().orElse(null);
    }
    Account account(String name) {
        var ids = db.queryForList("SELECT id FROM administrator WHERE username=?", Integer.class, name);
        return ids.isEmpty() ? null : account(ids.getFirst());
    }
    int currentId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && !(auth.getPrincipal() instanceof Identity)) {
            var account = account(auth.getName());
            if (account != null && account.active()) return account.id;
        }
        throw new org.springframework.security.access.AccessDeniedException("인증이 필요합니다");
    }

    boolean admin() { var a = account(currentId()); return a != null && a.active && a.role.equals("ADMIN"); }
    List<String> allowed(int id) {
        var a = account(id);
        if (a == null || !a.active) return List.of();
        return a.role.equals("ADMIN") ? sources().stream().map(Source::id).toList()
                : db.queryForList("SELECT source_id FROM account_sources WHERE user_id=?", String.class, id);
    }
    Identity sessionIdentity() { return new Identity("user:" + currentId(), allowed(currentId())); }
    Identity effective(String token, int owner, List<String> scope, boolean all) {
        var a = account(owner); if (a == null || !a.active) return null;
        var permitted = allowed(owner);
        return new Identity(token, all ? permitted : scope.stream().filter(permitted::contains).toList());
    }
    void requireTokenOwner(String id) {
        if (!admin() && db.queryForObject("SELECT COUNT(*) FROM tokens WHERE id=? AND owner_id=?", Integer.class, id, currentId()) == 0)
            throw new org.springframework.security.access.AccessDeniedException("토큰 접근 불가");
    }
    long generation() { var a = account(currentId()); return a == null || !a.active ? -1 : a.generation; }
    boolean changeAccount(long generation, String username, String passwordHash) {
        return db.update("UPDATE administrator SET username=?,password_hash=?,generation=generation+1 WHERE id=? AND generation=? AND active=TRUE", username, passwordHash, currentId(), generation) == 1;
    }
    int deleteInactiveTokens(String id) {
        if (id != null) requireTokenOwner(id);
        return db.update("DELETE FROM tokens WHERE (revoked=TRUE OR expires_at<=?) AND (?=TRUE OR owner_id=?) AND (? IS NULL OR id=?)", System.currentTimeMillis(), admin(), currentId(), id, id);
    }
    public List<Source> sources() {
        return db.query("SELECT * FROM sources ORDER BY name", (rs, row) -> new Source(rs.getString("id"), rs.getString("name"),
                Path.of(rs.getString("root")), lines(rs.getString("includes")), lines(rs.getString("excludes")), rs.getString("kind"), rs.getBoolean("always_docling")));
    }
    public Source source(String id) { return sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("소스 없음")); }
    public void save(Source s) {
        db.update("MERGE INTO sources(id,name,root,includes,excludes,kind,always_docling) KEY(id) VALUES (?,?,?,?,?,?,?)", s.id(), s.name(), s.root().toString(), String.join("\n", s.includes()), String.join("\n", s.excludes()), s.kind(), s.alwaysDocling());
    }
    public void deleteSource(String id) {
        db.update("DELETE FROM trash WHERE source_id=?", id);
        db.update("UPDATE shares SET revoked=TRUE WHERE source_id=?", id);
        db.update("DELETE FROM sources WHERE id=?", id);
    }
    public void addToken(String id, String name, String hash, long expires, List<String> sources) {
        db.update("INSERT INTO tokens(id,name,token_hash,expires_at,sources,owner_id) VALUES (?,?,?,?,?,?)", id, name, hash, expires, String.join("\n", sources), currentId());
    }
    public List<Token> tokens() {
        return db.query("SELECT t.*,a.username AS owner_name FROM tokens t LEFT JOIN administrator a ON a.id=t.owner_id WHERE ?=TRUE OR t.owner_id=? ORDER BY t.name", (rs, row) ->
                new Token(rs.getString("id"), rs.getString("name"), rs.getLong("expires_at"), lines(rs.getString("sources")), rs.getBoolean("revoked"), true, rs.getInt("owner_id"), Objects.toString(rs.getString("owner_name"), "")), admin(), currentId());
    }
    public Identity authenticate(String hash) {
        var found = db.queryForList("SELECT id,sources,owner_id FROM tokens WHERE token_hash=? AND revoked=FALSE AND expires_at>?", hash, Instant.now().toEpochMilli());
        return found.isEmpty() ? null : tokenIdentity(found.getFirst());
    }
    // 소유자 조회는 바깥 결과 집합/연결을 반환한 뒤 수행한다(작은 연결 풀의 교착 방지).
    private Identity tokenIdentity(Map<String, Object> row) {
        var scope = lines(row.get("SOURCES").toString());
        return effective(row.get("ID").toString(), ((Number)row.get("OWNER_ID")).intValue(), scope, scope.isEmpty());
    }
    // 다운로드 티켓 사용 때 OAuth 토큰도 현재 승인·만료·소스 범위를 다시 확인한다.
    private java.util.function.Function<String, Identity> oauthIdentity = id -> null;
    public void oauthIdentity(java.util.function.Function<String, Identity> resolver) { this.oauthIdentity = resolver; }
    public Identity identity(String id) {
        if (id.startsWith("user:")) return effective(id, Integer.parseInt(id.substring(5)), List.of(), true);
        if (id.startsWith("oauth:")) return oauthIdentity.apply(id);
        var rows = db.queryForList("SELECT id,sources,owner_id FROM tokens WHERE id=? AND revoked=FALSE AND expires_at>?", id, Instant.now().toEpochMilli());
        return rows.isEmpty() ? null : tokenIdentity(rows.getFirst());
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
    public void revoke(String id) { requireTokenOwner(id); db.update("UPDATE tokens SET revoked=TRUE WHERE id=?", id); }
    public void audit(String actor, String action, int status) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Account acting = null;
        String identity = auth != null && auth.getPrincipal() instanceof Identity token ? token.tokenId() : actor;
        if (identity.startsWith("user:")) acting = account(Integer.parseInt(identity.substring(5)));
        else {
            var ids = identity.startsWith("oauth:")
                    ? db.queryForList("SELECT g.owner_id FROM oauth_tokens t JOIN oauth_grants g ON g.id=t.grant_id WHERE t.id=?", Integer.class, identity)
                    : db.queryForList("SELECT owner_id FROM tokens WHERE id=?", Integer.class, identity);
            if (!ids.isEmpty()) acting = account(ids.getFirst());
        }
        if (acting == null && auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) && !(auth.getPrincipal() instanceof Identity)) {
            acting = account(auth.getName());
        }
        db.update("INSERT INTO audit(occurred_at,actor,action,status,actor_id,actor_name) VALUES (?,?,?,?,?,?)", Instant.now().toEpochMilli(), actor, action, status, acting == null ? null : acting.id, acting == null ? null : acting.username);
        db.update("DELETE FROM audit WHERE id <= (SELECT COALESCE(MAX(id),0)-10000 FROM audit)");
    }
    public List<Map<String, Object>> audit() { return db.queryForList("SELECT occurred_at,actor,action,status,actor_id,actor_name FROM audit ORDER BY id DESC LIMIT 200"); }
    private static List<String> lines(String value) { return value == null || value.isBlank() ? List.of() : value.lines().toList(); }
}

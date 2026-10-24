package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import java.io.*;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ShareService {
    record Share(String id, String source, String path, boolean directory, long expires, String password, long maximum, long downloads, int failures, long retryAt, String fileKey) { }
    record Target(Share share, Source source, Path relative, boolean directory) { }
    static class Missing extends RuntimeException { }
    static class PasswordRequired extends RuntimeException { }
    static class Delayed extends RuntimeException { }
    private final JdbcTemplate db;
    private final Storage storage;
    private final DownloadService downloads;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    ShareService(JdbcTemplate db, Storage storage, DownloadService downloads) { this(db, storage, downloads, Clock.systemUTC()); }
    ShareService(JdbcTemplate db, Storage storage, DownloadService downloads, Clock clock) {
        this(db, storage, downloads, clock, new BCryptPasswordEncoder(12));
    }
    ShareService(JdbcTemplate db, Storage storage, DownloadService downloads, Clock clock, org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.passwords = passwords;
        this.db = db; this.storage = storage; this.downloads = downloads; this.clock = clock;
        db.execute("CREATE TABLE IF NOT EXISTS shares (id VARCHAR(36) PRIMARY KEY, token_hash VARCHAR(64) UNIQUE NOT NULL, source_id VARCHAR(36) NOT NULL, path VARCHAR(4096) NOT NULL, directory BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, password_hash VARCHAR(100), max_downloads BIGINT NOT NULL, downloads BIGINT DEFAULT 0 NOT NULL, revoked BOOLEAN DEFAULT FALSE, failures INT DEFAULT 0 NOT NULL, retry_at BIGINT DEFAULT 0 NOT NULL)");
        db.execute("ALTER TABLE shares ADD COLUMN IF NOT EXISTS file_key VARCHAR(512)");
        db.execute("CREATE TABLE IF NOT EXISTS share_failures (share_id VARCHAR(36), ip VARCHAR(64), failures INT NOT NULL, retry_at BIGINT NOT NULL, PRIMARY KEY(share_id,ip))");
    }
    public Map<String, Object> create(String sourceId, String path, Long expiresAt, String password, Long maximum) throws IOException {
        long now = clock.millis(), expires = expiresAt == null ? now + 7 * 86400000L : expiresAt;
        if (expires <= now || expires > now + 365 * 86400000L || (maximum != null && (maximum < 1 || maximum > 1000000))) throw new IllegalArgumentException("공유 만료·횟수 오류");
        if (password != null && !password.isEmpty() && (password.length() < 8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)) throw new IllegalArgumentException("공유 비밀번호 8자 이상/72바이트 이하");
        Source source = storage.source(sourceId); Path relative = SafeFiles.relative(path, true);
        boolean directory;
        if (relative.toString().isEmpty()) { try (var ignored = new SafeFiles(source.root())) { directory = true; } }
        else try (var parent = new SafeFiles(SafeFiles.parent(source.root(), relative))) {
            var attrs = parent.attributes(relative.getFileName());
            if (attrs.isSymbolicLink() || !(attrs.isDirectory() || attrs.isRegularFile())) throw new Missing();
            directory = attrs.isDirectory();
        }
        SafeFiles.allowed(source, relative, directory);
        if (directory) { try (var ignored = new SafeFiles(source.root().resolve(relative))) { } }
        else { try (var ignored = downloads.open(sourceId + ":" + relative)) { } }
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), id = UUID.randomUUID().toString();
        db.update("INSERT INTO shares(id,token_hash,source_id,path,directory,expires_at,password_hash,max_downloads,file_key) VALUES (?,?,?,?,?,?,?,?,?)",
                id, SecurityConfiguration.hash(value), sourceId, relative.toString(), directory, expires,
                password == null || password.isEmpty() ? null : passwords.encode(password), maximum == null ? 0 : maximum, fileKey(source, relative));
        storage.audit("admin", "share-create", 200);
        return Map.of("id", id, "url", "/s/" + value, "expiresAt", expires);
    }
    public List<Map<String, Object>> list() {
        return db.queryForList("SELECT id,source_id,path,directory,expires_at,max_downloads,downloads,revoked,(password_hash IS NOT NULL) AS protected FROM shares ORDER BY expires_at DESC LIMIT 1000");
    }
    public void revoke(String id) { db.update("UPDATE shares SET revoked=TRUE WHERE id=?", id); storage.audit("admin", "share-revoke", 200); }
    private Share find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new Missing();
        var rows = db.query("SELECT * FROM shares WHERE token_hash=? AND revoked=FALSE AND expires_at>? AND (max_downloads=0 OR downloads<max_downloads)",
                (r, n) -> new Share(r.getString("id"), r.getString("source_id"), r.getString("path"), r.getBoolean("directory"), r.getLong("expires_at"), r.getString("password_hash"), r.getLong("max_downloads"), r.getLong("downloads"), r.getInt("failures"), r.getLong("retry_at"), r.getString("file_key")),
                SecurityConfiguration.hash(token), clock.millis());
        if (rows.isEmpty()) throw new Missing(); return rows.getFirst();
    }
    private void password(Share share, String password, String ip) {
        if (share.password == null) return;
        long now = clock.millis();
        synchronized (this) {
            if (retryAt(share.id, ip) > now) throw new Delayed();
        }
        if (password == null) throw new PasswordRequired();
        // 비용이 큰 BCrypt는 전역 잠금 밖에서 실행한다.
        boolean matches = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72 && passwords.matches(password, share.password);
        synchronized (this) {
            if (retryAt(share.id, ip) > now) throw new Delayed();
            if (!matches) {
                var rows = db.queryForList("SELECT failures FROM share_failures WHERE share_id=? AND ip=?", share.id, ip);
                int previous = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("FAILURES")).intValue();
                int failures = previous >= 5 ? 1 : previous + 1;
                long wait = failures >= 5 ? 900000 : 1000L << (failures - 1);
                db.update("DELETE FROM share_failures WHERE retry_at<?", now - 900000);
                if (rows.isEmpty() && db.queryForObject("SELECT COUNT(*) FROM share_failures", Integer.class) >= 4096)
                    db.update("DELETE FROM share_failures WHERE (share_id,ip) IN (SELECT share_id,ip FROM share_failures ORDER BY retry_at LIMIT 1)");
                db.update("MERGE INTO share_failures KEY(share_id,ip) VALUES (?,?,?,?)", share.id, ip, failures, now + wait);
                storage.audit("share-visitor", "share-password", 401); throw new PasswordRequired();
            }
            db.update("DELETE FROM share_failures WHERE share_id=? AND ip=?", share.id, ip);
        }
    }
    private long retryAt(String id, String ip) {
        var values = db.queryForList("SELECT retry_at FROM share_failures WHERE share_id=? AND ip=?", Long.class, id, ip);
        return values.isEmpty() ? 0 : values.getFirst();
    }
    private static String fileKey(Source source, Path path) throws IOException {
        Object key;
        if (path.toString().isEmpty()) {
            try (var root = new SafeFiles(source.root())) {
                key = root.directory.getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView.class).readAttributes().fileKey();
            }
        } else try (var parent = new SafeFiles(SafeFiles.parent(source.root(), path))) {
            key = parent.attributes(path.getFileName()).fileKey();
        }
        if (key == null) throw new IOException("공유 대상 식별자 미지원");
        return key.toString();
    }
    private Target target(Share share, String path) throws IOException {
        Source source = storage.source(share.source);
        Path suffix = SafeFiles.relative(path, true), root = SafeFiles.relative(share.path, true);
        if (share.fileKey == null || !share.fileKey.equals(fileKey(source, root))) throw new Missing();
        if (!share.directory && !suffix.toString().isEmpty()) throw new Missing();
        Path relative = suffix.toString().isEmpty() ? root : root.resolve(suffix);
        // 공유 루트도 매번 검사한다. 하위 경로가 허용돼도 공유 폴더가 제외되면 접근 불가다.
        SafeFiles.allowed(source, root, share.directory);
        if (share.directory) { try (var ignored = new SafeFiles(source.root().resolve(root))) { } }
        boolean directory;
        if (relative.toString().isEmpty()) directory = true;
        else try (var parent = new SafeFiles(SafeFiles.parent(source.root(), relative))) {
            var attrs = parent.attributes(relative.getFileName());
            if (attrs.isSymbolicLink() || !(attrs.isDirectory() || attrs.isRegularFile())) throw new Missing();
            directory = attrs.isDirectory();
        }
        if (suffix.toString().isEmpty() && directory != share.directory) throw new Missing();
        SafeFiles.allowed(source, relative, directory);
        // 실경로 검사와 별개로 이후 목록/다운로드도 NOFOLLOW 핸들로 연다.
        Path resolved = source.root().resolve(relative).toRealPath();
        if (!resolved.startsWith(source.root()) || !resolved.startsWith(source.root().resolve(root))) throw new Missing();
        return new Target(share, source, relative, directory);
    }
    Target authorize(String token, String path, String password) throws IOException {
        return authorize(token, path, password, "local");
    }
    Target authorize(String token, String path, String password, String ip) throws IOException {
        Share share = find(token); password(share, password, ip); return target(find(token), path);
    }
    List<Map<String, Object>> entries(Target target) throws IOException {
        target = refresh(target);
        return SafeFiles.list(target.source, target.relative);
    }
    private Share findById(Share share) {
        if (db.queryForObject("SELECT COUNT(*) FROM shares WHERE id=? AND revoked=FALSE AND expires_at>?", Integer.class, share.id, clock.millis()) != 1) throw new Missing();
        return share;
    }
    private Target refresh(Target target) throws IOException {
        Path root = Path.of(target.share.path);
        return target(findById(target.share), target.relative.equals(root) ? "" : root.relativize(target.relative).toString());
    }
    ReadableByteChannel open(Target target) throws IOException {
        target = refresh(target);
        if (target.directory) throw new Missing();
        ReadableByteChannel channel = downloads.open(target.source.id() + ":" + target.relative);
        try {
            if (db.update("UPDATE shares SET downloads=downloads+1 WHERE id=? AND revoked=FALSE AND expires_at>? AND (max_downloads=0 OR downloads<max_downloads)", target.share.id, clock.millis()) != 1) throw new Missing();
            storage.audit("share-visitor", "share-download", 200); return channel;
        } catch (RuntimeException e) { channel.close(); throw e; }
    }
}

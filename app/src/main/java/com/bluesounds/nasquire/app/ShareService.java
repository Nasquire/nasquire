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
    record Share(String id, String source, String path, boolean directory, long expires, String password, long maximum, long downloads, int failures, long retryAt) { }
    record Target(Share share, Source source, Path relative, boolean directory) { }
    static class Missing extends RuntimeException { }
    static class PasswordRequired extends RuntimeException { }
    static class Delayed extends RuntimeException { }
    private final JdbcTemplate db;
    private final Storage storage;
    private final DownloadService downloads;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    ShareService(JdbcTemplate db, Storage storage, DownloadService downloads) { this(db, storage, downloads, Clock.systemUTC()); }
    ShareService(JdbcTemplate db, Storage storage, DownloadService downloads, Clock clock) {
        this.db = db; this.storage = storage; this.downloads = downloads; this.clock = clock;
        db.execute("CREATE TABLE IF NOT EXISTS shares (id VARCHAR(36) PRIMARY KEY, token_hash VARCHAR(64) UNIQUE NOT NULL, source_id VARCHAR(36) NOT NULL, path VARCHAR(4096) NOT NULL, directory BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, password_hash VARCHAR(100), max_downloads BIGINT NOT NULL, downloads BIGINT DEFAULT 0 NOT NULL, revoked BOOLEAN DEFAULT FALSE, failures INT DEFAULT 0 NOT NULL, retry_at BIGINT DEFAULT 0 NOT NULL)");
    }
    public synchronized Map<String, Object> create(String sourceId, String path, Long expiresAt, String password, Long maximum) throws IOException {
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
        db.update("INSERT INTO shares(id,token_hash,source_id,path,directory,expires_at,password_hash,max_downloads) VALUES (?,?,?,?,?,?,?,?)",
                id, SecurityConfiguration.hash(value), sourceId, relative.toString(), directory, expires,
                password == null || password.isEmpty() ? null : passwords.encode(password), maximum == null ? 0 : maximum);
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
                (r, n) -> new Share(r.getString("id"), r.getString("source_id"), r.getString("path"), r.getBoolean("directory"), r.getLong("expires_at"), r.getString("password_hash"), r.getLong("max_downloads"), r.getLong("downloads"), r.getInt("failures"), r.getLong("retry_at")),
                SecurityConfiguration.hash(token), clock.millis());
        if (rows.isEmpty()) throw new Missing(); return rows.getFirst();
    }
    private void password(Share share, String password) {
        if (share.password == null) return;
        long now = clock.millis();
        if (share.retryAt > now) throw new Delayed();
        if (password == null) throw new PasswordRequired();
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || !passwords.matches(password, share.password)) {
            int failures = share.failures >= 5 ? 1 : share.failures + 1;
            long wait = failures >= 5 ? 900000 : 1000L << (failures - 1);
            db.update("UPDATE shares SET failures=?,retry_at=? WHERE id=?", failures, now + wait, share.id);
            storage.audit("share-visitor", "share-password", 401); throw new PasswordRequired();
        }
        db.update("UPDATE shares SET failures=0,retry_at=0 WHERE id=?", share.id);
    }
    private Target target(Share share, String path) throws IOException {
        Source source = storage.source(share.source);
        Path suffix = SafeFiles.relative(path, true), root = SafeFiles.relative(share.path, true);
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
    synchronized Target authorize(String token, String path, String password) throws IOException {
        Share share = find(token); Target target = target(share, path); password(share, password); return target;
    }
    synchronized List<Map<String, Object>> entries(Target target) throws IOException {
        return SafeFiles.list(target.source, target.relative);
    }
    synchronized ReadableByteChannel open(Target target) throws IOException {
        if (target.directory) throw new Missing();
        ReadableByteChannel channel = downloads.open(target.source.id() + ":" + target.relative);
        try {
            if (db.update("UPDATE shares SET downloads=downloads+1 WHERE id=? AND revoked=FALSE AND expires_at>? AND (max_downloads=0 OR downloads<max_downloads)", target.share.id, clock.millis()) != 1) throw new Missing();
            storage.audit("share-visitor", "share-download", 200); return channel;
        } catch (RuntimeException e) { channel.close(); throw e; }
    }
}

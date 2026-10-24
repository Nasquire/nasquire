package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class UploadService {
    /** 쓰기 함수는 검사를 통과한 업로드 소스만 받는다. 일반 Source를 받는 쓰기 함수는 없다. */
    record WritableSource(Source source) {
        WritableSource {
            if (!source.kind().equals("upload")) throw new AccessDeniedException("읽기 전용 소스");
        }
    }
    private final Storage storage;
    private final IndexingService indexing;
    private final JdbcTemplate db;
    private final Path data;
    private final long maxBytes, retention;
    UploadService(Storage storage, IndexingService indexing, JdbcTemplate db, Path dataFolder,
            @Value("${nasquire.upload-max-bytes:33554432}") long maxBytes,
            @Value("${nasquire.trash-retention-days:30}") long days) {
        if (maxBytes < 1 || maxBytes > 1073741824L || days < 1 || days > 365) throw new IllegalArgumentException("업로드 설정 범위 오류");
        this.storage = storage; this.indexing = indexing; this.db = db; this.data = dataFolder;
        this.maxBytes = maxBytes; this.retention = days * 86400000L;
        db.execute("CREATE TABLE IF NOT EXISTS trash (id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, original VARCHAR(4096) NOT NULL, deleted_at BIGINT NOT NULL)");
        db.execute("ALTER TABLE trash ADD COLUMN IF NOT EXISTS cleanup_after BIGINT DEFAULT 0 NOT NULL");
        db.execute("ALTER TABLE trash ADD COLUMN IF NOT EXISTS cleanup_error BOOLEAN DEFAULT FALSE NOT NULL");
    }
    @jakarta.annotation.PostConstruct
    public void cleanupStaging() throws IOException {
        // OCR을 끈 상태로 재시작해도 이전 OCR 작업이 남긴 임시 폴더를 정리한다.
        com.bluesounds.nasquire.core.OcrScratch.cleanStale(data);
        long before = System.currentTimeMillis() - 86400000L;
        Path multipart = data.resolve("multipart");
        if (Files.isDirectory(multipart, LinkOption.NOFOLLOW_LINKS)) {
            try (var directory = new SafeFiles(multipart)) { cleanupParts(directory, before, true, 0); }
        }
        for (Source source : storage.sources()) if (source.kind().equals("upload")) {
            try (var root = new SafeFiles(writable(source.id()).source().root())) { cleanupParts(root, before, false, 0); }
            catch (IOException | RuntimeException e) { storage.audit("system", "staging-cleanup", 409); }
        }
    }
    private void cleanupParts(SafeFiles root, long before, boolean multipart, int depth) throws IOException {
        cleanupParts(root.directory, before, multipart, depth);
    }
    private void cleanupParts(SecureDirectoryStream<Path> directory, long before, boolean multipart, int depth) throws IOException {
        if (depth > 64) return;
        for (Path entry : directory) {
            Path name = entry.getFileName();
            try {
                var attr = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (attr.isDirectory() && !multipart && !name.toString().equals(".nasquire-trash")) {
                    try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) { cleanupParts(child, before, false, depth + 1); }
                } else if (attr.isRegularFile() && attr.lastModifiedTime().toMillis() < before
                        && (multipart || (name.toString().startsWith(".nasquire-") && name.toString().endsWith(".part")))) {
                    directory.deleteFile(name); storage.audit("system", "staging-cleanup", 200);
                }
            } catch (IOException e) { storage.audit("system", "staging-cleanup", 409); }
        }
    }
    private WritableSource writable(String id) {
        var source = storage.source(id);
        if (source.root().startsWith(data) || data.startsWith(source.root())) throw new AccessDeniedException("데이터 폴더 중첩");
        for (Source other : storage.sources()) if (!other.id().equals(id)
                && (other.root().startsWith(source.root()) || source.root().startsWith(other.root())))
            throw new AccessDeniedException("소스 중첩");
        return new WritableSource(source);
    }
    public List<Map<String, Object>> list(String id, String path) throws IOException {
        return SafeFiles.list(writable(id).source(), SafeFiles.relative(path, true));
    }
    private void changed(WritableSource source, String action) {
        indexing.reindex(source.source().id()); storage.audit("admin", action, 200);
    }
    public synchronized void upload(String id, String path, InputStream input, boolean overwrite) throws IOException {
        var source = writable(id); Path relative = SafeFiles.relative(path, false);
        SafeFiles.allowed(source.source(), relative, false);
        // 요청 본문은 작은 버퍼로 디스크에 흘리고, 완료된 파일만 원자적 rename으로 공개한다.
        try (var parent = new SafeFiles(SafeFiles.parent(source.source().root(), relative))) {
            Path temporary = Path.of(".nasquire-" + UUID.randomUUID() + ".part");
            boolean staged = false;
            try {
                try (var channel = parent.directory.newByteChannel(temporary, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    staged = true; byte[] bytes = new byte[65536]; long total = 0; int count;
                    while ((count = input.read(bytes)) != -1) {
                        total += count; if (total > maxBytes) throw new IllegalArgumentException("업로드 크기 상한 초과");
                        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count); while (buffer.hasRemaining()) channel.write(buffer);
                    }
                }
                target(parent, relative.getFileName(), overwrite);
                if (overwrite) storage.revokeShares(id, relative);
                if (overwrite) parent.directory.move(temporary, parent.directory, relative.getFileName());
                else SecureDirectories.moveNew(SafeFiles.parent(source.source().root(), relative).resolve(temporary), source.source().root().resolve(relative));
                staged = false;
            } finally { if (staged) parent.directory.deleteFile(temporary); }
        }
        changed(source, "upload-file");
    }
    private static void target(SafeFiles parent, Path name, boolean overwrite) throws IOException {
        if (parent.exists(name)) {
            var attr = parent.attributes(name);
            if (!overwrite || !attr.isRegularFile() || attr.isSymbolicLink()) throw new IllegalStateException("대상 존재 또는 링크");
        }
    }
    private void mkdir(WritableSource source, Path relative) throws IOException {
        SecureDirectories.create(source.source().root().resolve(relative));
    }
    public synchronized void folder(String id, String path) throws IOException {
        var source = writable(id); Path relative = SafeFiles.relative(path, false);
        if (relative.getNameCount() > 64) throw new IllegalArgumentException("폴더 깊이 상한");
        SafeFiles.allowed(source.source(), relative, true); mkdir(source, relative); changed(source, "upload-folder");
    }
    public synchronized void move(String id, String path, String destination, boolean overwrite) throws IOException {
        var source = writable(id); Path from = SafeFiles.relative(path, false), to = SafeFiles.relative(destination, false);
        if (to.startsWith(from)) throw new IllegalArgumentException("자기 하위 이동 금지");
        try (var origin = new SafeFiles(SafeFiles.parent(source.source().root(), from)); var target = new SafeFiles(SafeFiles.parent(source.source().root(), to))) {
            var attr = origin.attributes(from.getFileName());
            if (attr.isSymbolicLink() || !(attr.isRegularFile() || attr.isDirectory())) throw new AccessDeniedException("링크 거부");
            SafeFiles.allowed(source.source(), from, attr.isDirectory()); SafeFiles.allowed(source.source(), to, attr.isDirectory());
            if (attr.isDirectory()) {
                try (var descendants = Files.walk(source.source().root().resolve(from))) {
                    if (descendants.anyMatch(p -> to.getNameCount() + source.source().root().resolve(from).relativize(p).getNameCount() > 64))
                        throw new IllegalArgumentException("폴더 깊이 상한");
                }
            }
            target(target, to.getFileName(), overwrite);
            storage.revokeShares(id, from); storage.revokeShares(id, to);
            if (overwrite) origin.directory.move(from.getFileName(), target.directory, to.getFileName());
            else SecureDirectories.moveNew(source.source().root().resolve(from), source.source().root().resolve(to));
        }
        changed(source, "upload-move");
    }
    public synchronized String trash(String id, String path) throws IOException {
        var source = writable(id); Path relative = SafeFiles.relative(path, false);
        Path trash = Path.of(".nasquire-trash");
        try (var root = new SafeFiles(source.source().root())) { if (!root.exists(trash)) mkdir(source, trash); }
        String item = UUID.randomUUID().toString();
        try (var origin = new SafeFiles(SafeFiles.parent(source.source().root(), relative)); var target = new SafeFiles(source.source().root().resolve(trash))) {
            var attr = origin.attributes(relative.getFileName());
            if (attr.isSymbolicLink() || !(attr.isRegularFile() || attr.isDirectory())) throw new AccessDeniedException("링크 거부");
            SafeFiles.allowed(source.source(), relative, attr.isDirectory());
            // 메타데이터를 먼저 보존하여 프로세스 중단 시 복원 정보가 사라지지 않게 한다.
            db.update("INSERT INTO trash(id,source_id,original,deleted_at) VALUES (?,?,?,?)", item, id, relative.toString(), System.currentTimeMillis());
            storage.revokeShares(id, relative);
            origin.directory.move(relative.getFileName(), target.directory, Path.of(item));
        }
        changed(source, "upload-trash"); return item;
    }
    public synchronized List<Map<String, Object>> trashList(String id) throws IOException {
        writable(id); return db.queryForList("SELECT id, original, deleted_at, cleanup_error FROM trash WHERE source_id=? ORDER BY deleted_at DESC", id);
    }
    public synchronized void restore(String id, String item) throws IOException {
        var source = writable(id); UUID.fromString(item);
        var rows = db.queryForList("SELECT original FROM trash WHERE source_id=? AND id=?", id, item);
        if (rows.isEmpty()) throw new NoSuchElementException();
        Path to = SafeFiles.relative(rows.getFirst().get("ORIGINAL").toString(), false);
        try (var origin = new SafeFiles(source.source().root().resolve(".nasquire-trash")); var target = new SafeFiles(SafeFiles.parent(source.source().root(), to))) {
            var attr = origin.attributes(Path.of(item));
            if (attr.isSymbolicLink()) throw new AccessDeniedException("링크 거부");
            SafeFiles.allowed(source.source(), to, attr.isDirectory()); target(target, to.getFileName(), false);
            SecureDirectories.moveNew(source.source().root().resolve(".nasquire-trash").resolve(item), source.source().root().resolve(to));
        }
        db.update("DELETE FROM trash WHERE id=?", item); changed(source, "upload-restore");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public synchronized void cleanup() {
        for (var row : db.queryForList("SELECT id,source_id FROM trash WHERE deleted_at<? AND cleanup_after<=? ORDER BY deleted_at LIMIT 100", System.currentTimeMillis() - retention, System.currentTimeMillis())) {
            try {
                var source = writable(row.get("SOURCE_ID").toString());
                try (var trash = new SafeFiles(source.source().root().resolve(".nasquire-trash"))) {
                    erase(trash.directory, Path.of(row.get("ID").toString()), 0);
                }
                db.update("DELETE FROM trash WHERE id=?", row.get("ID")); storage.audit("system", "trash-expire", 200);
            } catch (IOException | RuntimeException e) {
                db.update("UPDATE trash SET cleanup_error=TRUE,cleanup_after=? WHERE id=?", System.currentTimeMillis() + 3600000, row.get("ID"));
                storage.audit("system", "trash-expire", 409);
            }
        }
    }
    private static void erase(SecureDirectoryStream<Path> parent, Path name, int depth) throws IOException {
        if (depth > 64) throw new IOException("휴지통 깊이 상한");
        var attr = parent.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attr.isDirectory()) {
            try (var child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path entry : child) erase(child, entry.getFileName(), depth + 1);
            }
            parent.deleteDirectory(name);
        } else parent.deleteFile(name);
    }
}

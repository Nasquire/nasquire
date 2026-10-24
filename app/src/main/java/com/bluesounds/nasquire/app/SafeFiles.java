package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;

/** 절대경로의 모든 조상을 NOFOLLOW로 열어 고정한다. 미지원 파일시스템은 거부한다. */
final class SafeFiles implements AutoCloseable {
    private final List<DirectoryStream<Path>> handles = new ArrayList<>();
    final SecureDirectoryStream<Path> directory;
    SafeFiles(Path absolute) throws IOException {
        try {
            DirectoryStream<Path> first = Files.newDirectoryStream(absolute.getRoot()); handles.add(first);
            if (!(first instanceof SecureDirectoryStream<Path> secure)) throw new IOException("안전한 디렉터리 열기 미지원");
            var current = secure;
            for (Path component : absolute) {
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS); handles.add(current);
            }
            directory = current;
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    /** 이미 식별자를 확인한 루트 fd를 의도적으로 다시 열고 하위만 NOFOLLOW로 따라간다. */
    SafeFiles(com.bluesounds.nasquire.core.SecureFiles.Directory root, Path relative) throws IOException {
        try {
            var first = Files.newDirectoryStream(root.path()); handles.add(first);
            if (!(first instanceof SecureDirectoryStream<Path> secure)) throw new IOException("안전한 디렉터리 열기 미지원");
            var current = secure;
            if (!relative.toString().isEmpty()) for (Path component : relative) {
                name(component.toString());
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS); handles.add(current);
            }
            directory = current;
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    static Path relative(String value, boolean empty) {
        if (value == null || value.length() > 4096 || (!empty && value.isEmpty())
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) throw new IllegalArgumentException("NFC 상대경로 필요");
        if (empty && value.isEmpty()) return Path.of("");
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path)) throw new AccessDeniedException("상대경로 필요");
        for (Path part : path) name(part.toString());
        if (!path.toString().replace('\\', '/').equals(value)) throw new IllegalArgumentException("경로 표기 오류");
        return path;
    }
    static void name(String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")
                || name.startsWith(".nasquire-") || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240
                || name.matches(".*[\\x00-\\x1f\\x7f<>:\"/\\\\|?*].*")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
            throw new IllegalArgumentException("파일 이름 오류");
    }
    static void allowed(Source source, Path relative, boolean directory) {
        if (relative.toString().isEmpty()) return;
        if (!(directory ? source.acceptsDirectory(relative) : source.accepts(relative))) throw new AccessDeniedException("제외 경로");
        for (int i = 1; i < relative.getNameCount(); i++)
            if (!source.acceptsDirectory(relative.subpath(0, i))) throw new AccessDeniedException("제외 경로");
    }
    BasicFileAttributes attributes(Path name) throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
    }
    boolean exists(Path name) throws IOException {
        try { attributes(name); return true; } catch (NoSuchFileException e) { return false; }
    }
    static Path parent(Path root, Path relative) { return relative.getParent() == null ? root : root.resolve(relative.getParent()); }
    public void close() throws IOException {
        IOException failure = null;
        for (int i = handles.size() - 1; i >= 0; i--) try { handles.get(i).close(); } catch (IOException e) { failure = e; }
        if (failure != null) throw failure;
    }
    static List<Map<String, Object>> list(Source source, Path relative) throws IOException {
        allowed(source, relative, true);
        try (var opened = new SafeFiles(source.root().resolve(relative))) {
            return list(source, relative, opened);
        }
    }
    static List<Map<String, Object>> list(Source source, Path relative, SafeFiles opened) throws IOException {
        allowed(source, relative, true);
        var result = new ArrayList<Map<String, Object>>();
        for (Path child : opened.directory) {
            Path name = child.getFileName(), candidate = relative.resolve(name);
            try {
                relative(candidate.toString(), false);
                var attr = opened.attributes(name);
                if (attr.isSymbolicLink() || !(attr.isRegularFile() || attr.isDirectory())) continue;
                allowed(source, candidate, attr.isDirectory());
                result.add(Map.of("name", name.toString(), "path", candidate.toString(), "directory", attr.isDirectory(), "size", attr.size()));
                if (result.size() >= 1000) break;
            } catch (IllegalArgumentException | AccessDeniedException | NoSuchFileException ignored) { }
        }
        result.sort(Comparator.comparing(row -> row.get("name").toString()));
        return result;
    }
}

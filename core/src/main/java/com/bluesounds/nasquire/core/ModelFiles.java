package com.bluesounds.nasquire.core;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;

/** 명시적으로 설정한 HTTPS 다운로드만 허용하고 기존 파일도 해시 검증한다. */
public final class ModelFiles {
    private ModelFiles() { }
    public static Path verified(Path path, String url, String sha256) throws Exception {
        return verified(path, url, sha256, null);
    }
    public static Path verified(Path path, String url, String sha256, Path downloadRoot) throws Exception {
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("SHA-256 필요");
        if (!Files.exists(path)) {
            if (url == null || url.isBlank()) throw new IOException("모델 파일 없음");
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) throw new IllegalArgumentException("HTTPS 모델 주소 필요");
            if (downloadRoot != null) {
                Path absolute = path.toAbsolutePath().normalize(), existing = absolute.getParent();
                while (!Files.exists(existing)) existing = existing.getParent();
                Path resolved = existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
                if (!resolved.startsWith(downloadRoot.toRealPath())) throw new IOException("다운로드는 데이터 폴더 안에서만 허용됩니다");
            }
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "model-", ".part");
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
                var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body(); var out = Files.newOutputStream(temporary)) {
                    if (response.statusCode() != 200) throw new IOException("모델 다운로드 실패");
                    byte[] buffer = new byte[65536]; long total = 0; int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n; if (total > 256L * 1024 * 1024) throw new IOException("모델 파일 상한 초과");
                        out.write(buffer, 0, n);
                    }
                }
                check(temporary, sha256);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        check(path, sha256); return path;
    }
    private static void check(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) != -1) digest.update(buf, 0, n); }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) throw new IOException("모델 SHA-256 불일치");
    }
}

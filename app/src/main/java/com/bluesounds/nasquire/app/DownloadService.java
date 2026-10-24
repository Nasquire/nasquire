package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 서명 키는 프로세스 수명에 한정한다. 재시작하면 기존 다운로드 주소도 폐기된다. */
@Service
public class DownloadService {
    private final Storage storage;
    private final DocumentService documents;
    private final String publicUrl;
    private final long ttl;
    private final Clock clock;
    private final byte[] key = new byte[32];
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadService(Storage storage, DocumentService documents,
            @Value("${nasquire.public-url:}") String publicUrl,
            @Value("${nasquire.download-ttl-seconds:300}") long ttl) {
        this(storage, documents, publicUrl, ttl, Clock.systemUTC());
    }
    DownloadService(Storage storage, DocumentService documents, String publicUrl, long ttl, Clock clock) {
        if (ttl < 1 || ttl > 86400) throw new IllegalArgumentException("다운로드 유효시간 1~86400초");
        this.storage = storage; this.documents = documents; this.ttl = ttl; this.clock = clock;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        if (!this.publicUrl.isBlank()) validateBase(this.publicUrl);
        new SecureRandom().nextBytes(key);
    }
    private static void validateBase(String base) {
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("공개 주소 오류");
    }
    public Map<String, Object> issue(Storage.Identity identity, String path, String requestBase) throws IOException {
        String actor = identity == null ? "admin" : identity.tokenId();
        try {
            String source = sourceId(path);
            documents.scope(identity, source);
            // 발급할 때도 링크·glob·실경로를 확인하고 읽기 전용으로 열 수 있어야 한다.
            try (var ignored = open(path)) { }
            long expires = clock.instant().plusSeconds(ttl).getEpochSecond();
            byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
            String payload = expires + "\n" + ENCODER.encodeToString(nonce) + "\n" + path;
            String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String ticket = encoded + "." + ENCODER.encodeToString(sign(encoded));
            String base = publicUrl.isBlank() ? requestBase : publicUrl; validateBase(base);
            storage.audit(actor, "download-issue", 200);
            return Map.of("url", base.replaceAll("/+$", "") + "/download?ticket=" + ticket, "expiresAt", expires * 1000);
        } catch (IOException | RuntimeException e) { storage.audit(actor, "download-issue", 403); throw e; }
    }
    private byte[] sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    String verify(String ticket) {
        try {
            if (ticket == null || ticket.length() > 24000) throw new IllegalArgumentException();
            String[] parts = ticket.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            String[] payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\n", 3);
            long expires = Long.parseLong(payload[0]), now = clock.instant().getEpochSecond();
            if (payload.length != 3 || expires <= now || expires > now + ttl) throw new IllegalArgumentException();
            sourceId(payload[2]); return payload[2];
        } catch (RuntimeException e) { throw new AccessDeniedException("다운로드 주소 만료 또는 서명 오류"); }
    }
    static String sourceId(String path) {
        if (path == null || path.length() > 4096 || path.indexOf(':') < 1 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0)
            throw new IllegalArgumentException("소스 ID:상대경로가 필요합니다");
        return path.substring(0, path.indexOf(':'));
    }
    ReadableByteChannel open(String path) throws IOException {
        Source source = storage.source(sourceId(path));
        Path relative = Path.of(path.substring(path.indexOf(':') + 1));
        if (relative.isAbsolute() || relative.toString().isBlank() || !relative.normalize().equals(relative)
                || relative.startsWith("..") || !source.accepts(relative)) throw new AccessDeniedException("소스 범위 밖");
        for (int i = 1; i < relative.getNameCount(); i++)
            if (!source.acceptsDirectory(relative.subpath(0, i))) throw new AccessDeniedException("제외 디렉터리");
        Path root = source.root().toRealPath(), file = root.resolve(relative);
        if (!root.equals(source.root().toAbsolutePath().normalize()) || !file.toRealPath().startsWith(root)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new AccessDeniedException("소스 범위 밖");
        // 루트부터 각 디렉터리를 고정하고 NOFOLLOW로 연다. 검사/열기 사이 링크 교체도 차단한다.
        var opened = new ArrayList<DirectoryStream<Path>>();
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(file.getRoot()); opened.add(stream);
            if (!(stream instanceof SecureDirectoryStream<Path>)) throw new IOException("안전한 디렉터리 열기 미지원");
            var current = (SecureDirectoryStream<Path>) stream;
            for (int i = 0; i < file.getNameCount() - 1; i++) {
                current = current.newDirectoryStream(file.getName(i), LinkOption.NOFOLLOW_LINKS); opened.add(current);
            }
            return current.newByteChannel(file.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } finally { for (int i = opened.size() - 1; i >= 0; i--) opened.get(i).close(); }
    }
}

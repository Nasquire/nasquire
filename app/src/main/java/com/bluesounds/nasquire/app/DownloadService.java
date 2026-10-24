package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.Source;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 불투명 티켓은 프로세스 메모리에만 보관한다. 재시작하면 기존 주소도 폐기된다. */
@Service
public class DownloadService {
    private final Storage storage;
    private final DocumentService documents;
    private final String publicUrl;
    private final long ttl;
    private final Clock clock;
    record Ticket(String path, String tokenId, long expires) { }
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadService(Storage storage, DocumentService documents,
            @Value("${nasquire.public-url:}") String publicUrl,
            @Value("${nasquire.download-ttl-seconds:300}") long ttl) {
        this(storage, documents, publicUrl, ttl, Clock.systemUTC());
    }
    DownloadService(Storage storage, DocumentService documents, String publicUrl, long ttl, Clock clock) {
        if (ttl < 1 || ttl > 3600) throw new IllegalArgumentException("다운로드 유효시간 1~3600초");
        this.storage = storage; this.documents = documents; this.ttl = ttl; this.clock = clock;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        if (!this.publicUrl.isBlank()) validateBase(this.publicUrl);
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
            String ticket = ENCODER.encodeToString(nonce);
            synchronized (tickets) {
                tickets.entrySet().removeIf(e -> e.getValue().expires <= clock.instant().getEpochSecond());
                if (tickets.size() >= 10000) throw new IllegalStateException("다운로드 주소 한도");
                tickets.put(ticket, new Ticket(path, identity == null ? null : identity.tokenId(), expires));
            }
            storage.audit(actor, "download-issue", 200);
            return Map.of("url", publicUrl + "/download?ticket=" + ticket, "expiresAt", expires * 1000,
                    "notice", publicUrl.isBlank() ? "상대 주소입니다. MCP 클라이언트는 nasquire.public-url 설정을 권장합니다." : "");
        } catch (IOException | RuntimeException e) { storage.audit(actor, "download-issue", 403); throw e; }
    }
    String verify(String ticket) {
        try {
            if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{22}")) throw new IllegalArgumentException();
            Ticket value;
            synchronized (tickets) { value = tickets.get(ticket); }
            if (value == null || value.expires <= clock.instant().getEpochSecond()) throw new IllegalArgumentException();
            if (value.tokenId != null) {
                var identity = storage.identity(value.tokenId);
                if (identity == null) throw new IllegalArgumentException();
                documents.scope(identity, sourceId(value.path));
            }
            return value.path;
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
        // O_NONBLOCK으로 FIFO 교체에도 멈추지 않고 열린 fd의 fstat으로 유형을 확인한다.
        return com.bluesounds.nasquire.core.SecureFiles.readRegular(file);
    }
}

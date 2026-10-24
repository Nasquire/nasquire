package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    record Setup(String username, String password) { }
    record SourceInput(String name, String path, List<String> includes, List<String> excludes, String kind) { }
    record TokenInput(String name, Long expiresAt, List<String> sources) { }
    private final EmbeddingSettings embeddings;
    private final Storage storage;
    private final IndexingService indexing;
    private final DocumentService documents;
    private final Path dataFolder;
    public ApiController(Storage storage, IndexingService indexing, DocumentService documents, Path dataFolder, EmbeddingSettings embeddings, FullSettings full) {
        this.full = full;
        this.embeddings = embeddings;
        this.storage = storage; this.indexing = indexing; this.documents = documents; this.dataFolder = dataFolder;
    }
    private final FullSettings full;
    @GetMapping("/full") Map<String, Object> full() { return full.view(); }
    @PutMapping("/full") void full(@RequestBody Map<String, String> input) throws Exception {
        full.save(input);
        for (var source : storage.sources()) indexing.reindex(source.id());
        storage.audit("admin", "full-save", 200);
    }
    @GetMapping("/embeddings") Map<String, Object> embeddings() { return embeddings.view(); }
    @PutMapping("/embeddings") void embeddings(@RequestBody Map<String, String> input) throws Exception { embeddings.save(input); storage.audit("admin", "embedding-save", 200); }
    @PostMapping("/embeddings/test") Map<String, Object> testEmbedding() { return embeddings.test(); }
    @PostMapping("/embeddings/reindex") void reindexEmbeddings() { embeddings.reindex(); }
    @GetMapping("/auth") Map<String, Object> auth(CsrfToken csrf, Authentication authentication, HttpServletRequest request) {
        return Map.of("totpRequired", TotpController.pending(request) != null, "configured", storage.configured(), "authenticated", authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
                "csrfToken", csrf.getToken(), "csrfHeader", csrf.getHeaderName());
    }
    @PostMapping("/setup") void setup(@RequestBody Setup input) { storage.setup(input.username, input.password); storage.audit("admin", "setup", 200); }
    @GetMapping("/sources") List<Map<String, Object>> sources() {
        return storage.sources().stream().map(s -> Map.<String, Object>of("id", s.id(), "name", s.name(), "path", s.root().toString(),
                "kind", s.kind(), "includes", s.includes(), "excludes", s.excludes(), "status", indexing.status(s.id()))).toList();
    }
    @PostMapping("/sources") Source create(@RequestBody SourceInput input) throws IOException { return save(UUID.randomUUID().toString(), input); }
    @PutMapping("/sources/{id}") Source update(@PathVariable String id, @RequestBody SourceInput input) throws IOException { storage.source(id); return save(id, input); }
    private synchronized Source save(String id, SourceInput input) throws IOException {
        if (input.name == null || input.name.length() > 200 || input.path == null || input.path.length() > 4096) throw new IllegalArgumentException("소스 입력 오류");
        Path root = Path.of(input.path).toRealPath();
        if (!Files.isDirectory(root) || !Files.isReadable(root) || root.startsWith(dataFolder) || dataFolder.startsWith(root))
            throw new IllegalArgumentException("읽을 수 있는 폴더를 지정하고 데이터 폴더와 분리하세요");
        for (var globs : List.of(input.includes == null ? List.<String>of() : input.includes, input.excludes == null ? List.<String>of() : input.excludes))
            if (globs.size() > 50 || globs.stream().anyMatch(g -> g == null || g.length() > 500 || g.contains("\n") || g.contains("\r"))) throw new IllegalArgumentException("glob 입력 오류");
        String kind = input.kind == null ? "directory" : input.kind;
        if (kind.equals("upload") && !Files.isWritable(root)) throw new IllegalArgumentException("쓰기 가능한 별도 폴더 필요");
        for (Source other : storage.sources()) {
            if (other.id().equals(id)) {
                if (!other.kind().equals(kind) || !other.root().equals(root))
                    throw new IllegalArgumentException("기존 소스 종류·경로 변경 대신 등록 해제 후 추가하세요");
            } else if ((kind.equals("upload") || other.kind().equals("upload"))
                    && (root.startsWith(other.root()) || other.root().startsWith(root)))
                throw new IllegalArgumentException("업로드 소스 중첩 금지");
        }
        var source = new Source(id, input.name, root, input.includes, input.excludes, kind);
        storage.save(source); indexing.reindex(id); storage.audit("admin", "source-save " + id, 200); return source;
    }
    @DeleteMapping("/sources/{id}") void delete(@PathVariable String id) { storage.source(id); storage.deleteSource(id); storage.audit("admin", "source-delete " + id, 200); }
    @PostMapping("/sources/{id}/reindex") void reindex(@PathVariable String id) { storage.source(id); indexing.reindex(id); }
    @GetMapping("/sources/{id}/status") IndexingService.Status status(@PathVariable String id) { storage.source(id); return indexing.status(id); }
    @GetMapping("/tokens") List<Storage.Token> tokens() { return storage.tokens(); }
    @PostMapping("/tokens") Map<String, Object> token(@RequestBody TokenInput input) {
        if (input.name == null || input.name.isBlank() || input.name.length() > 200 || input.expiresAt == null || input.expiresAt <= Instant.now().toEpochMilli()) throw new IllegalArgumentException("토큰 이름과 미래 만료 시간이 필요합니다");
        var scopes = input.sources == null ? List.<String>of() : input.sources;
        if (scopes.size() > 100) throw new IllegalArgumentException("소스 범위 상한 초과");
        for (var id : scopes) storage.source(id);
        var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String value = "nsq_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        storage.addToken(id, input.name, SecurityConfiguration.hash(value), input.expiresAt, scopes);
        storage.audit("admin", "token-create " + id, 200);
        return Map.of("id", id, "value", value, "readOnly", true);
    }
    @DeleteMapping("/tokens/{id}") void revoke(@PathVariable String id) { storage.revoke(id); storage.audit("admin", "token-revoke " + id, 200); }
    @GetMapping("/audit") List<Map<String, Object>> audit() { return storage.audit(); }
    @GetMapping("/search") List<SearchIndex.Hit> search(@RequestParam String query, @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String mode, @RequestParam(defaultValue = "false") boolean rerank, HttpServletRequest req) throws IOException {
        return documents.search((Storage.Identity)req.getAttribute("nasquire.token"), query, source, limit, mode, rerank);
    }
    @GetMapping("/document") Map<String, Object> document(@RequestParam String path,
            @RequestParam(name = "page_from", required = false) Integer from, @RequestParam(name = "page_to", required = false) Integer to,
            @RequestParam(required = false) Integer offset, @RequestParam(required = false) Integer length, HttpServletRequest req) throws IOException {
        return documents.document((Storage.Identity)req.getAttribute("nasquire.token"), path, from, to, offset, length);
    }
}

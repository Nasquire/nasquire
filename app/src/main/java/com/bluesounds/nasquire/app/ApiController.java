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
    /** alwaysDocling이 없으면(이전 클라이언트) 새 소스는 false, 기존 소스는 저장값을 유지한다. */
    record SourceInput(String name, String path, List<String> includes, List<String> excludes, String kind, Boolean alwaysDocling) { }
    record TokenInput(String name, Long expiresAt, List<String> sources) { }
    private final EmbeddingSettings embeddings;
    private final Storage storage;
    private final IndexingService indexing;
    private final DocumentService documents;
    private final Path dataFolder;
    public ApiController(Storage storage, IndexingService indexing, DocumentService documents, Path dataFolder, EmbeddingSettings embeddings, FullSettings full, IndexingSettings indexingSettings, RerankerSettings reranker,
            OcrSettings ocr, DoclingSettings docling) {
        this.reranker = reranker; this.ocr = ocr; this.docling = docling;
        this.indexingSettings = indexingSettings;
        this.full = full;
        this.embeddings = embeddings;
        this.storage = storage; this.indexing = indexing; this.documents = documents; this.dataFolder = dataFolder;
    }
    private final IndexingSettings indexingSettings;
    record IndexingInput(long scanMillis, long maxFileBytes) { }
    @GetMapping("/indexing") Map<String, Long> indexingSettings() { return indexingSettings.view(); }
    @PutMapping("/indexing") void indexingSettings(@RequestBody IndexingInput input) throws IOException {
        indexingSettings.save(input.scanMillis(), input.maxFileBytes(), indexing);
        storage.audit("admin", "indexing-save", 200);
    }
    @GetMapping("/supported-extensions") List<String> supportedExtensions() {
        var extensions = new ArrayList<>(List.of("txt", "md", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "hwp", "hwpx"));
        // full 빌드(Tesseract 선택 가능) 또는 원격 OCR이 켜져 있으면 이미지 형식을 안내한다. OCR off에서도 full 안내는 유지한다.
        if (FullSettings.available() || "remote".equals(ocr.view().get("ocr"))) extensions.addAll(List.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif"));
        return List.copyOf(extensions);
    }
    @PostMapping("/sources/{id}/scan") void scan(@PathVariable String id) {
        storage.source(id); indexing.scanNow(id); storage.audit("admin", "source-scan " + id, 200);
    }
    private final FullSettings full;
    @GetMapping("/full") Map<String, Object> full() { return full.view(); }
    @PutMapping("/full") void full(@RequestBody Map<String, String> input) throws Exception {
        // 이전 형식(ocr·ocrMaxPages)은 OCR 일반 설정으로 넘긴다. Tesseract 제한만 full에 저장한다.
        var legacy = new LinkedHashMap<String, Object>(); var limits = new LinkedHashMap<String, String>();
        for (var e : input.entrySet()) {
            if (FullSettings.LEGACY_OCR.contains(e.getKey())) legacy.put(e.getKey().equals("ocrMaxPages") ? "maxPages" : e.getKey(), e.getValue());
            else limits.put(e.getKey(), e.getValue());
        }
        boolean reindex = false;
        if (!limits.isEmpty() && full.save(limits)) reindex = ocr.tesseractChanged();
        if (!legacy.isEmpty()) reindex |= ocr.saveSettings(legacy);
        if (reindex) for (var source : storage.sources()) indexing.reindex(source.id());
        storage.audit("admin", "full-save", 200);
    }
    private final OcrSettings ocr;
    @GetMapping("/ocr") Map<String, Object> ocr() { return ocr.view(); }
    /** OCR 공급자·엔진·쪽 상한이 바뀌면 전체 소스를 재색인한다. 서버 목록·키만 바뀌면 재색인하지 않는다. */
    @PutMapping("/ocr") void ocr(@RequestBody Map<String, Object> input) throws Exception {
        if (ocr.saveSettings(input)) for (var source : storage.sources()) indexing.reindex(source.id());
        storage.audit("admin", "ocr-save", 200);
    }
    @DeleteMapping("/ocr/key") void clearOcrKey(@RequestParam(required = false) String server) throws Exception {
        ocr.clearKey(server); storage.audit("admin", "ocr-key-delete", 200);
    }
    @PostMapping("/ocr/test") Map<String, Object> testOcr(@RequestParam String server) { return ocr.test(server); }
    private final DoclingSettings docling;
    @GetMapping("/docling") Map<String, Object> docling() { return docling.view(); }
    @PutMapping("/docling") void docling(@RequestBody Map<String, Object> input) throws Exception { docling.save(input); storage.audit("admin", "docling-save", 200); }
    @DeleteMapping("/docling/key") void clearDoclingKey(@RequestParam(required = false) String server) throws Exception {
        docling.clearKey(server); storage.audit("admin", "docling-key-delete", 200);
    }
    @PostMapping("/docling/test") Map<String, Object> testDocling(@RequestParam String server) { return docling.test(server); }
    private final RerankerSettings reranker;
    @GetMapping("/reranker") Map<String, Object> reranker() { return reranker.view(); }
    @PutMapping("/reranker") void reranker(@RequestBody Map<String, Object> input) throws Exception { reranker.save(input); storage.audit("admin", "reranker-save", 200); }
    @DeleteMapping("/reranker/key") void clearRerankerKey(@RequestParam(required = false) String server) throws Exception {
        reranker.clearKey(server); storage.audit("admin", "reranker-key-delete", 200);
    }
    @PostMapping("/reranker/test") Map<String, Object> testReranker(@RequestParam String server) { return reranker.test(server); }
    /** 기능별·서버별 원격 상태(이름·상태·최근 오류 종류). 주소·키는 넣지 않는다. */
    /** docling에는 쪽 처리 집계(pages: 변환·실패·건너뜀 쪽 수, 재시도 대기 파일 수)도 넣는다. */
    @GetMapping("/health") Map<String, Object> health() { return Map.of("embedding", embeddings.health(), "reranker", reranker.health(), "ocr", ocr.health(), "docling", docling.health()); }
    @DeleteMapping("/embeddings/key") void clearEmbeddingKey(@RequestParam(required = false) String server) throws Exception {
        if (server == null) embeddings.clearKey(false); else embeddings.clearServerKey(server);
        storage.audit("admin", "embedding-key-delete", 200);
    }
    @DeleteMapping("/embeddings/qdrant-key") void clearQdrantKey() throws Exception { embeddings.clearKey(true); storage.audit("admin", "qdrant-key-delete", 200); }
    @GetMapping("/embeddings") Map<String, Object> embeddings() { return embeddings.view(); }
    @PutMapping("/embeddings") void embeddings(@RequestBody Map<String, Object> input) throws Exception { embeddings.save(input); storage.audit("admin", "embedding-save", 200); }
    @PostMapping("/embeddings/test") Map<String, Object> testEmbedding(@RequestParam(required = false) String server) { return server == null ? embeddings.test() : embeddings.test(server); }
    @PostMapping("/embeddings/reindex") void reindexEmbeddings() { embeddings.reindex(); for (var source : storage.sources()) indexing.scanNow(source.id()); }
    @GetMapping("/auth") Map<String, Object> auth(CsrfToken csrf, Authentication authentication, HttpServletRequest request) {
        boolean authenticated = authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_USER"));
        return Map.of("totpRequired", TotpController.pending(request) != null, "configured", storage.configured(), "authenticated", authenticated,
                "role", authenticated && storage.admin() ? "ADMIN" : "USER", "csrfToken", csrf.getToken(), "csrfHeader", csrf.getHeaderName());
    }
    @PostMapping("/setup") void setup(@RequestBody Setup input) { storage.setup(input.username, input.password); storage.audit("user:1", "setup", 200); }
    @GetMapping("/sources") List<Map<String, Object>> sources() {
        return storage.sources().stream().map(s -> Map.<String, Object>of("id", s.id(), "name", s.name(), "path", s.root().toString(),
                "kind", s.kind(), "includes", s.includes(), "excludes", s.excludes(), "alwaysDocling", s.alwaysDocling(), "status", indexing.status(s.id()))).toList();
    }
    @GetMapping("/allowed-sources") List<Map<String, Object>> allowedSources() {
        return storage.sources().stream().filter(s -> storage.allowed(storage.currentId()).contains(s.id()))
                .map(s -> Map.<String,Object>of("id", s.id(), "name", s.name())).toList();
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
        boolean alwaysDocling = Boolean.TRUE.equals(input.alwaysDocling);
        for (Source other : storage.sources()) {
            if (other.id().equals(id)) {
                if (input.alwaysDocling == null) alwaysDocling = other.alwaysDocling();
                if (!other.kind().equals(kind) || !other.root().equals(root))
                    throw new IllegalArgumentException("기존 소스 종류·경로 변경 대신 등록 해제 후 추가하세요");
            } else if ((kind.equals("upload") || other.kind().equals("upload"))
                    && (root.startsWith(other.root()) || other.root().startsWith(root)))
                throw new IllegalArgumentException("업로드 소스 중첩 금지");
        }
        var source = new Source(id, input.name, root, input.includes, input.excludes, kind, alwaysDocling);
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
        for (var id : scopes) {
            storage.source(id);
            if (!storage.allowed(storage.currentId()).contains(id)) throw new org.springframework.security.access.AccessDeniedException("소스 접근 불가");
        }
        // 빈 토큰 범위는 기존 호환성을 위해 소유자의 현재 전체 허용 범위를 뜻한다.
        var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String value = "nsq_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        storage.addToken(id, input.name, SecurityConfiguration.hash(value), input.expiresAt, scopes);
        storage.audit("admin", "token-create " + id, 200);
        return Map.of("id", id, "value", value, "readOnly", true);
    }
    @DeleteMapping("/tokens/inactive") Map<String, Integer> deleteInactiveTokens() {
        int count = storage.deleteInactiveTokens(null); storage.audit("admin", "token-delete-inactive", 200); return Map.of("deleted", count);
    }
    @DeleteMapping("/tokens/{id}/record") void deleteToken(@PathVariable String id) {
        if (storage.deleteInactiveTokens(id) == 0) {
            if (storage.tokens().stream().anyMatch(t -> t.id().equals(id))) {
                storage.audit("admin", "token-delete-active-refused", 409);
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
            }
            throw new NoSuchElementException("토큰 없음");
        }
        storage.audit("admin", "token-delete " + id, 200);
    }
    @DeleteMapping("/tokens/{id}") void revoke(@PathVariable String id) { storage.revoke(id); storage.audit("admin", "token-revoke " + id, 200); }
    @GetMapping("/audit") List<Map<String, Object>> audit() { return storage.audit(); }
    @GetMapping("/search") List<SearchIndex.Hit> search(@RequestParam String query, @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String mode, @RequestParam(defaultValue = "false") boolean rerank,
            HttpServletRequest req, jakarta.servlet.http.HttpServletResponse response) throws IOException {
        var result = documents.searchTraced((Storage.Identity)req.getAttribute("nasquire.token"), query, source, limit, mode, rerank);
        // 처리한 원격 서버 이름과 페일오버 여부만 알린다(주소 비노출). 이름은 영문·숫자·._-로 제한된다.
        var embedding = result.remote().get("embedding"); var reranked = result.remote().get("reranker");
        if (embedding != null) response.setHeader("X-Nasquire-Embedding-Server", embedding.server());
        if (reranked != null) response.setHeader("X-Nasquire-Rerank-Server", reranked.server());
        if (rerank) response.setHeader("X-Nasquire-Rerank", result.rerankApplied() ? "applied" : "original-order");
        if (!result.remote().isEmpty()) response.setHeader("X-Nasquire-Failover", Boolean.toString(result.remote().values().stream().anyMatch(e -> e.failover())));
        return result.hits();
    }
    @GetMapping("/document") Map<String, Object> document(@RequestParam String path,
            @RequestParam(name = "page_from", required = false) Integer from, @RequestParam(name = "page_to", required = false) Integer to,
            @RequestParam(required = false) Integer offset, @RequestParam(required = false) Integer length, HttpServletRequest req) throws IOException {
        return documents.document((Storage.Identity)req.getAttribute("nasquire.token"), path, from, to, offset, length);
    }
}

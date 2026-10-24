package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.stereotype.Service;

/** full 기능은 기본 off. 관리 API 외에는 활성화하지 않으며 키는 용도별 AAD로 저장한다. */
@Service
public final class FullSettings implements AutoCloseable {
    private final Path folder, file;
    private final EmbeddingSettings secrets;
    private Properties settings = defaults();
    private Providers.RerankerProvider reranker;
    private TextExtractor ocr;
    /** 시작 시 비루프백 평문 리랭커 주소로 원격 리랭커를 끈 이유. */
    private String disabledReason;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FullSettings.class);
    private static final Set<String> OCR_FIELDS = Set.of("ocr", "ocrTimeoutSeconds", "ocrMemoryMiB", "ocrMaxPages");
    private static final Set<String> FIELDS = Set.of("reranker", "baseUrl", "model", "apiKey", "topN", "timeoutSeconds", "ocr", "ocrTimeoutSeconds", "ocrMemoryMiB", "ocrMaxPages",
            "localModelPath", "localTokenizerPath", "localDownload", "allowInsecureHttp", "clearKey");
    public FullSettings(Path dataFolder, EmbeddingSettings secrets) throws Exception {
        folder = dataFolder; file = folder.resolve("full.properties"); this.secrets = secrets;
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { settings.load(in); }
            String encrypted = (String)settings.remove("encryptedKey");
            if (encrypted != null) settings.setProperty("apiKey", secrets.decrypt("reranker", encrypted));
        }
        // 업그레이드 호환: 비루프백 평문 주소는 시작을 막지 않고 원격 리랭커만 끈 뒤 상태로 알린다.
        try { checkTransport(settings); }
        catch (IllegalArgumentException e) { disabledReason = e.getMessage(); LOG.warn("원격 리랭커를 비활성화했습니다: {}", e.getMessage()); }
        reranker = disabledReason == null ? createReranker(settings) : null; ocr = createOcr(settings);
    }
    private static void checkTransport(Properties p) {
        String allow = p.getProperty("allowInsecureHttp", "false");
        if (!Set.of("true", "false").contains(allow)) throw new IllegalArgumentException("allowInsecureHttp 설정 오류");
        if ("remote".equals(p.getProperty("reranker"))) RemoteUrl.require(p.getProperty("baseUrl"), allow.equals("true"));
    }
    private static Properties defaults() {
        var p = new Properties(); p.setProperty("reranker", "off"); p.setProperty("baseUrl", "https://api.example.com/v1");
        p.setProperty("model", "reranker"); p.setProperty("topN", "20"); p.setProperty("timeoutSeconds", "5");
        p.setProperty("ocr", "off"); p.setProperty("ocrTimeoutSeconds", "15"); p.setProperty("ocrMemoryMiB", "512"); p.setProperty("ocrMaxPages", "20");
        // 빈 경로는 데이터 폴더의 models/<모델 이름>/ 기본 위치를 뜻한다.
        p.setProperty("localModelPath", ""); p.setProperty("localTokenizerPath", ""); p.setProperty("localDownload", "false");
        p.setProperty("allowInsecureHttp", "false");
        return p;
    }
    public synchronized Map<String, Object> view() {
        var result = new LinkedHashMap<String, Object>();
        for (String k : settings.stringPropertyNames()) if (!k.equals("apiKey")) result.put(k, settings.getProperty(k));
        result.put("hasKey", !settings.getProperty("apiKey", "").isEmpty());
        result.put("disabledReason", disabledReason == null ? "" : disabledReason);
        result.put("insecureHttp", "remote".equals(settings.getProperty("reranker")) && RemoteUrl.insecure(settings.getProperty("baseUrl")));
        result.put("available", ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent());
        return result;
    }
    /** 저장 뒤 OCR 관련 필드가 바뀌어 전체 재색인이 필요하면 true. */
    public synchronized boolean save(Map<String, String> input) throws Exception {
        var next = new Properties(); next.putAll(settings);
        for (var e : input.entrySet()) {
            if (!FIELDS.contains(e.getKey()) || e.getValue() == null || e.getValue().length() > 4096) throw new IllegalArgumentException("full 설정 오류");
            if (!e.getKey().equals("apiKey") || !e.getValue().isEmpty()) next.setProperty(e.getKey(), e.getValue());
        }
        // 주소가 바뀌면 새 키를 함께 입력하지 않는 한 기존 키를 새 주소로 보내지 않는다. clearKey=true는 명시적 삭제.
        if (!Objects.equals(next.getProperty("baseUrl"), settings.getProperty("baseUrl")) && input.getOrDefault("apiKey", "").isEmpty()
                || "true".equals(input.get("clearKey"))) next.remove("apiKey");
        next.remove("clearKey");
        boolean ocrChanged = OCR_FIELDS.stream().anyMatch(k -> !Objects.equals(settings.getProperty(k), next.getProperty(k)));
        String blocked = null;
        try { checkTransport(next); }
        catch (IllegalArgumentException e) {
            if (disabledReason == null || !input.keySet().equals(Set.of("clearKey"))) throw e;
            blocked = e.getMessage();
        }
        // 파일 게시 전 공급자를 만들고 검증한다. 네트워크나 프로세스를 실행하지 않는다.
        var newReranker = blocked == null ? createReranker(next) : null;
        TextExtractor newOcr;
        try {
            newOcr = createOcr(next);
            var stored = new Properties(); stored.putAll(next); stored.remove("apiKey");
            if (!next.getProperty("apiKey", "").isEmpty()) stored.setProperty("encryptedKey", secrets.encrypt("reranker", next.getProperty("apiKey")));
            Path temporary = Files.createTempFile(folder, "full-", ".properties");
            try {
                if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire full settings"); }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception e) { if (newReranker != null) newReranker.close(); throw e; }
        if (reranker != null) reranker.close(); reranker = newReranker; ocr = newOcr; settings = next; disabledReason = blocked;
        return ocrChanged;
    }
    /** 저장된 리랭커 키를 메모리·암호문 모두에서 지운다. */
    public void clearKey() throws Exception { save(Map.of("clearKey", "true")); }
    private static int number(Properties p, String name, int min, int max) {
        int n = Integer.parseInt(p.getProperty(name));
        if (n < min || n > max) throw new IllegalArgumentException("full 제한 오류"); return n;
    }
    private Providers.RerankerProvider createReranker(Properties p) {
        int top = number(p, "topN", 1, 100), timeout = number(p, "timeoutSeconds", 1, 30);
        return switch (p.getProperty("reranker")) {
            case "off" -> null;
            case "remote" -> Providers.full().reranker(p.getProperty("baseUrl"), p.getProperty("model"), p.getProperty("apiKey", ""), top, timeout);
            case "builtin" -> {
                String name = "mmarco-mMiniLMv2-L12-H384-v1";
                Path model = p.getProperty("localModelPath", "").isBlank() ? folder.resolve("models").resolve(name).resolve("model-qint8.onnx") : Path.of(p.getProperty("localModelPath"));
                Path tokenizer = p.getProperty("localTokenizerPath", "").isBlank() ? folder.resolve("models").resolve(name).resolve("tokenizer.json") : Path.of(p.getProperty("localTokenizerPath"));
                if (!Set.of("true", "false").contains(p.getProperty("localDownload"))) throw new IllegalArgumentException("다운로드 설정 오류");
                yield Providers.full().localReranker(model, tokenizer, Boolean.parseBoolean(p.getProperty("localDownload")), folder, top, timeout);
            }
            default -> throw new IllegalArgumentException("리랭커 공급자 오류");
        };
    }
    private TextExtractor createOcr(Properties p) {
        int timeout = number(p, "ocrTimeoutSeconds", 1, 60), memory = number(p, "ocrMemoryMiB", 128, 2048), pages = number(p, "ocrMaxPages", 1, 100);
        return switch (p.getProperty("ocr")) {
            case "off" -> null;
            case "tesseract" -> Providers.full().ocr(folder, timeout, memory, pages);
            default -> throw new IllegalArgumentException("OCR 공급자 오류");
        };
    }
    public List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits, boolean enabled) {
        Providers.RerankerProvider snapshot; synchronized (this) { snapshot = reranker; }
        return enabled && snapshot != null ? snapshot.rerank(query, hits) : hits;
    }
    public TextExtractor extractor() {
        return new TextExtractor() {
            public boolean supports(Path path) { synchronized (FullSettings.this) { return ocr != null && ocr.supports(path); } }
            public void extract(Path path, PageConsumer consumer, int max) throws IOException { extractLocated(path, (p, l, t) -> consumer.accept(p, t), max); }
            public void extractLocated(Path path, LocatedConsumer consumer, int max) throws IOException {
                TextExtractor snapshot; synchronized (FullSettings.this) { snapshot = ocr; }
                // 설정 해제와 색인 선택이 겹쳐도 텍스트 PDF는 기존 추출기로 처리한다.
                if (snapshot == null) {
                    if (new PdfTextExtractor().supports(path)) new PdfTextExtractor().extractLocated(path, consumer, max);
                    else throw new IOException("OCR 공급자가 꺼졌습니다");
                } else snapshot.extractLocated(path, consumer, max);
            }
        };
    }
    public synchronized void close() { if (reranker != null) reranker.close(); }
}

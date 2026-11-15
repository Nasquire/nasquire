package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.ocr.OcrTextExtractor;
import com.bluesounds.nasquire.core.remote.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * OCR 일반 설정(ocr.properties, AAD ocr). 기능별 택일: lite는 off|remote, full은 off|tesseract|remote이며 서로 대체하지 않는다.
 * 원격은 Nasquire ML 서버 호환 /ocr 서버 묶음, tesseract는 full의 로컬 CLI이고 실행 제한(시간·메모리)은 {@link FullSettings}(/api/full)에 둔다.
 * 쪽 렌더링·이미지 디코딩은 두 경우 모두 core의 격리 자식 프로세스가 맡고 공급자에는 PNG만 넘긴다.
 * 이전 full.properties의 ocr·ocrMaxPages는 처음 시작할 때 옮긴다.
 */
@Service
public final class OcrSettings extends RemoteFeatureSettings<Providers.OcrProvider> {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OcrSettings.class);
    private static final Set<String> SCALARS = Set.of("ocr", "engine", "maxPages", "timeoutSeconds", "allowInsecureHttp");
    /** 바뀌면 전체 재색인이 필요한 항목. */
    private static final Set<String> REINDEX = Set.of("ocr", "engine", "maxPages");
    private final FullSettings full;
    private TextExtractor ocr;
    public OcrSettings(Path dataFolder, EmbeddingSettings secrets, FullSettings full) throws Exception {
        super(dataFolder, secrets, EmbeddingSettings.OCR);
        this.full = full;
        load();
        ocr = extractor(settings, provider);
    }
    @Override protected void migrate() throws Exception {
        Path old = folder.resolve("full.properties");
        if (!Files.exists(old)) return;
        var stored = new Properties(); try (var in = Files.newInputStream(old)) { stored.load(in); }
        if (!stored.containsKey("ocr") && !stored.containsKey("ocrMaxPages")) return;
        var next = defaults();
        if (stored.containsKey("ocr")) next.setProperty("ocr", stored.getProperty("ocr"));
        if (stored.containsKey("ocrMaxPages")) next.setProperty("maxPages", stored.getProperty("ocrMaxPages"));
        write(next);
        LOG.info("이전 full OCR 선택을 ocr.properties로 이관했습니다");
    }
    @Override protected Properties defaults() {
        var p = new Properties();
        p.setProperty("ocr", "off"); p.setProperty("engine", RemoteOcrProvider.DEFAULT_ENGINE); p.setProperty("maxPages", "20");
        p.setProperty("timeoutSeconds", "60"); p.setProperty("allowInsecureHttp", "false");
        return p;
    }
    @Override protected Set<String> scalars() { return SCALARS; }
    @Override protected int maxServerTimeout() { return RemoteServer.MAX_TIMEOUT_SECONDS; }
    @Override protected int defaultServerTimeout(Properties next) { return Math.min(number(next, "timeoutSeconds", 1, 300), RemoteServer.MAX_TIMEOUT_SECONDS); }
    @Override protected Providers.OcrProvider create(Properties p, List<RemoteServer> list) {
        number(p, "maxPages", 1, 100);
        int timeout = number(p, "timeoutSeconds", 1, 300);
        String engine = p.getProperty("engine", "");
        if (engine.isBlank() || engine.length() > 256) throw new IllegalArgumentException("OCR 엔진 오류");
        return switch (p.getProperty("ocr")) {
            case "off" -> null;
            case "remote" -> {
                if (list.isEmpty()) throw new IllegalArgumentException("원격 서버를 하나 이상 등록하세요");
                var pool = new RemotePool(feature, list);
                try { yield new RemoteOcrProvider(pool, engine, timeout); }
                catch (RuntimeException e) { pool.close(); throw e; }
            }
            // full이 없으면 IllegalStateException: 시작은 거부하고 저장은 409로 돌려준다.
            case "tesseract" -> full.tesseract();
            default -> throw new IllegalArgumentException("OCR 공급자 오류");
        };
    }
    @Override protected RemotePool pool(Providers.OcrProvider provider) { return provider instanceof RemoteOcrProvider r ? r.pool() : null; }
    @Override protected RemoteCheck verify(Providers.OcrProvider provider, String server, Duration limit) { return ((RemoteOcrProvider)provider).verify(server, limit); }
    @Override protected void published(Providers.OcrProvider created) { ocr = extractor(settings, created); }
    private TextExtractor extractor(Properties p, Providers.OcrProvider provider) {
        return provider == null ? null : new OcrTextExtractor(provider, Integer.parseInt(p.getProperty("maxPages")), folder);
    }
    @Override public synchronized Map<String, Object> view() {
        var result = super.view();
        result.put("fullAvailable", FullSettings.available());
        return result;
    }
    /** 저장 뒤 전체 재색인이 필요하면 true(공급자·엔진·쪽 상한 변경). 서버 목록·키·시간 상한만 바뀌면 재색인하지 않는다. */
    public boolean saveSettings(Map<String, ?> input) throws Exception {
        Properties before; synchronized (this) { before = (Properties)settings.clone(); }
        save(input);
        synchronized (this) { return REINDEX.stream().anyMatch(k -> !Objects.equals(before.getProperty(k), settings.getProperty(k))); }
    }
    /** /api/full의 Tesseract 제한이 바뀐 뒤 호출한다. tesseract 사용 중이면 공급자를 다시 만들고 true(전체 재색인 필요). */
    public boolean tesseractChanged() throws Exception {
        Providers.OcrProvider old;
        synchronized (this) {
            if (!"tesseract".equals(mode())) return false;
            old = provider; provider = full.tesseract(); ocr = extractor(settings, provider);
        }
        if (old != null) old.close();
        return true;
    }
    /** OCR 공급자가 켜져 있는지(off·비활성이면 false). */
    public synchronized boolean enabled() { return ocr != null; }
    /** 색인기에 넣는 추출기. 설정을 바꾸면 다음 파일부터 새 공급자를 쓴다. */
    public TextExtractor extractor() {
        return new TextExtractor() {
            public boolean supports(Path name) { synchronized (OcrSettings.this) { return ocr != null && ocr.supports(name); } }
            public void extract(Path path, PageConsumer consumer, int max) throws IOException { extractLocated(path, (p, l, t) -> consumer.accept(p, t), max); }
            public void extractLocated(Path path, LocatedConsumer consumer, int max) throws IOException {
                extractLocated(path, path, consumer, max);
            }
            @Override
            public void extractLocated(Path name, Path content, LocatedConsumer consumer, int max) throws IOException {
                extractLocated(name, content, consumer, max, null);
            }
            @Override
            public void extractLocated(Path name, Path content, LocatedConsumer consumer, int max, com.bluesounds.nasquire.core.layout.LayoutCheck check) throws IOException {
                TextExtractor snapshot; synchronized (OcrSettings.this) { snapshot = ocr; }
                // 설정 해제와 색인 선택이 겹쳐도 텍스트 PDF는 기존 추출기로 처리한다.
                if (snapshot == null) {
                    if (new PdfTextExtractor().supports(name)) new PdfTextExtractor().extractLocated(name, content, consumer, max, check);
                    else throw new IOException("OCR 공급자가 꺼졌습니다");
                } else snapshot.extractLocated(name, content, consumer, max, check);
            }
        };
    }
}

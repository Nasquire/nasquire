package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.bluesounds.nasquire.core.remote.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 원격 Docling(docling-serve) 연결 설정(docling.properties, AAD docling). off|remote만 있으며 lite·full 공통이다.
 * 연결되면 색인기가 PDF·단일 이미지의 표·그림 의심 쪽만 Docling으로 보낸다({@link DoclingPages}). off이거나 비활성이면 판단·전송 비용이 없다.
 * 쪽 판단 기준값(minGridLines·minColumns·minRows·minImagePercent·maxPages)과 파일당 재시도 상한(maxRetries)도 여기 둔다. 기준값을 바꿔도
 * 자동 재색인하지 않는다(다음에 색인하는 파일부터, 기존 파일은 소스 재색인 때 적용).
 * 키는 X-Api-Key 헤더로 보낸다. 시간 상한 기본값은 docling-serve 기본 동기 대기 상한(300초)이다.
 */
@Service
public final class DoclingSettings extends RemoteFeatureSettings<RemoteDoclingClient> {
    private static final Set<String> SCALARS = Set.of("docling", "timeoutSeconds", "maxFileMiB", "allowInsecureHttp",
            "minGridLines", "minColumns", "minRows", "minImagePercent", "maxPages", "maxRetries");
    /** 첫 재시도 대기(이후 두 배). 실제 재시도는 대기 뒤 첫 주기 검사에서 한다. */
    static final long RETRY_DELAY_MILLIS = 10 * 60 * 1000L;
    static final int DEFAULT_MAX_RETRIES = 3;
    private final DoclingPages pages;
    public DoclingSettings(Path dataFolder, EmbeddingSettings secrets) throws Exception {
        super(dataFolder, secrets, EmbeddingSettings.DOCLING);
        load();
        pages = new DoclingPages(this::client, this::thresholds, this::maxRetries, RETRY_DELAY_MILLIS);
    }
    /** 색인기에 연결하는 쪽 변환기. */
    public DoclingPages pages() { return pages; }
    @Override protected Properties defaults() {
        var p = new Properties();
        p.setProperty("docling", "off"); p.setProperty("timeoutSeconds", Integer.toString(RemoteDoclingClient.DEFAULT_TIMEOUT_SECONDS));
        p.setProperty("maxFileMiB", Long.toString(RemoteDoclingClient.DEFAULT_MAX_FILE_BYTES / (1024 * 1024))); p.setProperty("allowInsecureHttp", "false");
        var t = LayoutCheck.Thresholds.DEFAULT;
        p.setProperty("minGridLines", Integer.toString(t.minGridLines())); p.setProperty("minColumns", Integer.toString(t.minColumns()));
        p.setProperty("minRows", Integer.toString(t.minRows())); p.setProperty("minImagePercent", Integer.toString(t.minImagePercent()));
        p.setProperty("maxPages", Integer.toString(t.maxPages())); p.setProperty("maxRetries", Integer.toString(DEFAULT_MAX_RETRIES));
        return p;
    }
    @Override protected Set<String> scalars() { return SCALARS; }
    @Override protected int maxServerTimeout() { return RemoteServer.MAX_TIMEOUT_SECONDS; }
    @Override protected int defaultServerTimeout(Properties next) {
        return Math.min(number(next, "timeoutSeconds", 1, RemoteDoclingClient.MAX_TIMEOUT_SECONDS), RemoteServer.MAX_TIMEOUT_SECONDS);
    }
    @Override protected RemoteDoclingClient create(Properties p, List<RemoteServer> list) {
        int timeout = number(p, "timeoutSeconds", 1, RemoteDoclingClient.MAX_TIMEOUT_SECONDS);
        int maxMiB = number(p, "maxFileMiB", 1, (int)(RemoteDoclingClient.MAX_FILE_BYTES / (1024 * 1024)));
        thresholds(p); number(p, "maxRetries", 0, 10);
        return switch (p.getProperty("docling")) {
            case "off" -> null;
            case "remote" -> {
                if (list.isEmpty()) throw new IllegalArgumentException("원격 서버를 하나 이상 등록하세요");
                var pool = new RemotePool(feature, list);
                try { yield new RemoteDoclingClient(pool, timeout, maxMiB * 1024L * 1024L); }
                catch (RuntimeException e) { pool.close(); throw e; }
            }
            default -> throw new IllegalArgumentException("Docling 공급자 오류");
        };
    }
    @Override protected RemotePool pool(RemoteDoclingClient provider) { return provider == null ? null : provider.pool(); }
    @Override protected RemoteCheck verify(RemoteDoclingClient provider, String server, Duration limit) { return provider.verify(server, limit); }
    private static LayoutCheck.Thresholds thresholds(Properties p) {
        return new LayoutCheck.Thresholds(number(p, "minGridLines", 2, 50), number(p, "minColumns", 2, 20), number(p, "minRows", 2, 100),
                number(p, "minImagePercent", 0, 100), number(p, "maxPages", 1, 100));
    }
    /** 현재 기준값. 저장값이 잘못됐으면(기능이 비활성) 기본값. */
    public synchronized LayoutCheck.Thresholds thresholds() {
        try { return thresholds(settings); } catch (IllegalArgumentException e) { return LayoutCheck.Thresholds.DEFAULT; }
    }
    public synchronized int maxRetries() {
        try { return number(settings, "maxRetries", 0, 10); } catch (IllegalArgumentException e) { return DEFAULT_MAX_RETRIES; }
    }
    /** 관리 화면: 연결 설정에 쪽 처리 집계·최근 판단(소스 상대 경로·쪽·근거·결과)을 더한다. 주소는 넣지 않는다. */
    @Override public synchronized Map<String, Object> view() {
        var result = super.view();
        var stats = pages.stats();
        result.put("pages", Map.of("converted", stats.converted(), "failed", stats.failed(), "skipped", stats.skipped(),
                "retryWaiting", stats.retryWaiting(), "recent", stats.recent()));
        return result;
    }
    /** /api/health: 서버 상태에 쪽 처리 집계(수만)를 더한다. */
    @Override public synchronized Map<String, Object> health() {
        var result = new LinkedHashMap<>(super.health());
        var stats = pages.stats();
        result.put("pages", Map.of("converted", stats.converted(), "failed", stats.failed(), "skipped", stats.skipped(), "retryWaiting", stats.retryWaiting()));
        return result;
    }
    /** 현재 클라이언트(꺼져 있거나 설정 오류로 비활성이면 비어 있음). 쪽 판단·변환({@link DoclingPages})이 사용한다. */
    public synchronized Optional<RemoteDoclingClient> client() {
        return provider == null || !provider.pool().disabledReason().isEmpty() ? Optional.empty() : Optional.of(provider);
    }
}

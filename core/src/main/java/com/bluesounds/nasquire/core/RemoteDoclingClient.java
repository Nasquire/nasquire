package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import com.google.gson.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * docling-serve 문서 변환 클라이언트(동기 POST /v1/convert/file, multipart files, 인증 헤더 X-Api-Key). 기준 버전은 docling-serve 1.38.2다.
 * 결과는 inbody JSON의 document.md_content(선택 json_content)만 읽는다. 원본 파일 이름 대신 확장자만 남긴 이름(document.pdf)을 보낸다.
 * 그림은 placeholder로 두고 쪽 이미지를 받지 않아 응답 크기를 줄인다. 쪽 범위(page_range)로 특정 쪽만 변환할 수 있다.
 * 서버 묶음의 round_robin·페일오버·서킷 브레이커를 따르며 실패 시 대체 변환을 하지 않는다. 동시 변환은 1건이다.
 */
public final class RemoteDoclingClient implements AutoCloseable {
    /**
     * docling-serve의 기본 동기 대기 상한(300초)을 기본 전체 시간 상한으로 쓴다. 서버 하나의 요청은 {@link RemoteServer#MAX_TIMEOUT_SECONDS}(300초) 이하이고,
     * 전체 상한은 페일오버 1회를 포함해 600초까지 둔다.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300, MAX_TIMEOUT_SECONDS = 600;
    /** 파일 크기 상한. 최댓값은 docling-serve 기본 업로드 상한(64 MiB), 기본값은 힙을 고려한 32 MiB다. */
    public static final long DEFAULT_MAX_FILE_BYTES = 32L * 1024 * 1024, MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** 변환 결과 응답 상한. */
    public static final int MAX_RESPONSE = 32 * 1024 * 1024;
    /** 1부터 시작하는 쪽 범위(양 끝 포함). */
    public record PageRange(int from, int to) {
        public PageRange {
            if (from < 1 || to < from) throw new IllegalArgumentException("쪽 범위 오류");
        }
        public static PageRange single(int page) { return new PageRange(page, page); }
    }
    /**
     * 변환 결과. status는 docling-serve의 success·partial_success·failure·skipped, errors는 오류 항목 수(내용은 담지 않는다).
     * json은 요청한 경우의 DoclingDocument이며 그 밖에는 null이다.
     */
    public record Result(String markdown, JsonObject json, String status, int errors, String server, boolean failover) {
        public boolean converted() { return "success".equals(status) || "partial_success".equals(status); }
    }
    private final RemotePool pool;
    private final Duration timeout;
    private final long maxFileBytes;
    private final Semaphore active = new Semaphore(1);
    private volatile String expectedVersion;
    /** seconds는 페일오버를 포함한 요청 전체 상한(1~600초), maxFileBytes는 보낼 파일 크기 상한이다. 닫으면 묶음도 닫는다. */
    public RemoteDoclingClient(RemotePool pool, int seconds, long maxFileBytes) {
        if (pool == null || seconds < 1 || seconds > MAX_TIMEOUT_SECONDS || maxFileBytes < 1 || maxFileBytes > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Docling 설정 범위 오류");
        this.pool = pool; this.timeout = Duration.ofSeconds(seconds); this.maxFileBytes = maxFileBytes;
    }
    public RemotePool pool() { return pool; }
    public long maxFileBytes() { return maxFileBytes; }
    /** 문서 전체를 Markdown으로 변환한다. */
    public Result convert(byte[] content, String fileName) { return convert(content, fileName, null, false); }
    /**
     * 문서를 변환한다. range가 있으면 그 쪽만 변환하고(PDF 등 쪽이 있는 형식), json이 true면 DoclingDocument JSON도 받는다.
     * 크기 초과는 IllegalArgumentException, 다른 변환이 진행 중이면 UNAVAILABLE, 원격 실패는 {@link RemoteException}이다.
     */
    public Result convert(byte[] content, String fileName, PageRange range, boolean json) { return convert(content, fileName, range, json, null); }
    /** 마감 시각(System.nanoTime 기준)이 있는 변환. 전체·서버당 시간 상한과 docling-serve document_timeout을 남은 시간으로 줄인다. */
    public Result convert(byte[] content, String fileName, PageRange range, boolean json, long deadlineNanos) {
        return convert(content, fileName, range, json, Long.valueOf(deadlineNanos));
    }
    private Result convert(byte[] content, String fileName, PageRange range, boolean json, Long deadlineNanos) {
        if (content == null || content.length == 0 || content.length > maxFileBytes) throw new IllegalArgumentException("Docling 파일 크기 상한 초과");
        String name = uploadName(fileName);
        if (!active.tryAcquire()) throw new RemoteException(RemoteException.Kind.UNAVAILABLE);
        try {
            RemotePool.Call<Result> call = (http, server, limit) -> convert(http, server, limit, content, name, range, json);
            var result = deadlineNanos == null ? pool.execute(timeout, call) : pool.execute(timeout, deadlineNanos, call);
            var value = result.value();
            return new Result(value.markdown(), value.json(), value.status(), value.errors(), result.server(), result.failover());
        } finally { active.release(); }
    }
    /** 원본 이름은 보내지 않는다. docling-serve가 형식을 판별하도록 확장자(영문·숫자 1~10자)만 남긴다. */
    static String uploadName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        if (!extension.matches("[a-z0-9]{1,10}")) throw new IllegalArgumentException("Docling 파일 형식 오류");
        return "document." + extension;
    }
    /** multipart 요청 본문(서버 하나). 시험에서도 같은 형식을 쓴다. */
    static RemoteHttp.Multipart request(byte[] content, String name, PageRange range, boolean json, Duration limit) {
        var form = new RemoteHttp.Multipart().file("files", name, "application/octet-stream", content);
        form.field("to_formats", "md");
        if (json) form.field("to_formats", "json");
        form.field("target_type", "inbody").field("image_export_mode", "placeholder")
                .field("include_images", "false").field("abort_on_error", "false")
                // 서버도 같은 시간 안에서 문서 처리를 멈추게 한다.
                .field("document_timeout", Long.toString(Math.max(1, limit.toSeconds())));
        if (range != null) form.field("page_range", Integer.toString(range.from())).field("page_range", Integer.toString(range.to()));
        return form;
    }
    private static Result convert(RemoteHttp http, RemoteServer server, Duration limit, byte[] content, String name, PageRange range, boolean json) {
        var response = http.postMultipart(server, "/v1/convert/file", request(content, name, range, json, limit), RemoteHttp.Auth.X_API_KEY, limit, MAX_RESPONSE);
        return parse(response, json);
    }
    static Result parse(JsonElement response, boolean json) {
        try {
            var body = response.getAsJsonObject();
            var document = body.getAsJsonObject("document");
            String status = body.get("status").getAsString();
            if (document == null || !Set.of("pending", "started", "failure", "success", "partial_success", "skipped").contains(status))
                throw new RemoteException(RemoteException.Kind.INVALID_RESPONSE);
            var md = document.get("md_content");
            String markdown = md == null || md.isJsonNull() ? "" : md.getAsString();
            var jsonContent = json && document.get("json_content") instanceof JsonObject object ? object : null;
            int errors = body.get("errors") instanceof JsonArray list ? list.size() : 0;
            return new Result(markdown, jsonContent, status, errors, null, false);
        } catch (RemoteException e) { throw e; }
        catch (RuntimeException e) { throw new RemoteException(RemoteException.Kind.INVALID_RESPONSE); }
    }
    /**
     * 서버 하나를 확인한다. /version의 docling-serve 버전이 먼저 확인한 서버와 다르면 mismatch(version),
     * 그 뒤 작은 Markdown 변환 1건으로 인증(X-Api-Key)과 응답 형식을 확인한다.
     */
    public RemoteCheck verify(String server, Duration limit) {
        try {
            var version = pool.probe(server, limit, (http, s, t) -> version(http, s, t));
            if (!version.isEmpty()) {
                synchronized (this) {
                    if (expectedVersion == null) expectedVersion = version;
                    else if (!expectedVersion.equals(version)) return new RemoteCheck(server, "mismatch", "version");
                }
            }
            var result = pool.probe(server, limit, (http, s, t) -> convert(http, s, t, SAMPLE, "document.md", null, false));
            if (!result.converted()) return new RemoteCheck(server, "error", "conversion");
            return new RemoteCheck(server, "ok", version);
        } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
    }
    /** docling-serve 버전 문자열. 경로가 없거나 형식이 다르면 빈 값(확인 생략). 연결 실패·5xx는 그대로 던진다. */
    private static String version(RemoteHttp http, RemoteServer server, Duration limit) {
        try {
            var value = http.get(server, "/version", RemoteHttp.Auth.X_API_KEY, limit, 64 * 1024).getAsJsonObject().get("docling-serve");
            String text = value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
            return text.matches("[A-Za-z0-9._+-]{1,64}") ? text : "";
        } catch (RemoteException e) {
            if (e.retryable() || e.kind() == RemoteException.Kind.UNAVAILABLE) throw e;
            return "";
        } catch (RuntimeException e) { return ""; }
    }
    private static final byte[] SAMPLE = "# Nasquire connection test\n\nconnection test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public void close() { pool.close(); }
}

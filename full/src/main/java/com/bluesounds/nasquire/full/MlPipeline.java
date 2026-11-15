package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.bluesounds.nasquire.core.ocr.IsolatedExtractor;
import com.bluesounds.nasquire.core.ocr.OcrTextExtractor;
import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ML 서버 공통 문서 처리 파이프라인(Spring 없음). {@code /v1/ocr}·{@code /v1/extract}·{@code /process}가 모두 이 경로를 탄다.
 * <ul>
 * <li>HWP/HWPX·Office·텍스트: 색인기와 같은 기존 추출기(원본 표 구조). Docling을 쓰지 않는다.</li>
 * <li>PDF 글자 층 쪽: PDFBox. 스캔 쪽(글자 없는 쪽)·이미지: OCR(ML 설정의 tesseract|remote, 원본 열기·렌더링·디코딩은 격리 자식 프로세스).</li>
 * <li>표·그림 의심 쪽: Docling(ML Docling remote가 켜졌을 때만). 판단은 core {@link LayoutCheck}, 변환·반영은 {@link DoclingPages}를 그대로 쓴다.
 *     실패·혼잡이면 1차 결과로 응답하고(요청 단위 재시도 없음) 응답 메타데이터에 표시한다.</li>
 * </ul>
 * 요청마다 단일 마감 시각(nasquire.ml.document-timeout-seconds, 기본 120초·최대 600초)을 두고 격리 자식 실행·OCR·Docling 호출에는
 * 남은 시간만 넘긴다. 비신뢰 문서 파싱(PDF 글자 층·쪽 판단, Office/HWP)은 {@link IsolatedExtractor}의 격리 자식에서 하며, 추출·OCR이
 * 마감을 넘기면 자식을 강제 종료(종료 확인 뒤 슬롯 반환)하고 504로 거절한다. Docling 단계가 마감에 닿으면 남은 쪽을 보내지 않고 1차 결과로 응답한다.
 * 동시 처리는 문서 1건이며 기다리지 않고 {@link Busy}로 거절한다. 본문은 데이터 폴더 {@code ml-tmp}(0700)의 임시 파일(0600)에 상한까지만 받고
 * 처리 뒤 지운다. 파일 이름·본문은 로그에 남기지 않는다.
 */
final class MlPipeline implements AutoCloseable {
    /** 문서 본문 상한. Docling 기본 파일 상한과 같다. 이미지는 기존 /v1/ocr 상한, Office/HWP는 추출기 상한, 텍스트는 8 MiB다. */
    static final long MAX_DOCUMENT = RemoteDoclingClient.DEFAULT_MAX_FILE_BYTES, MAX_IMAGE = MlServer.MAX_IMAGE,
            MAX_OFFICE = 8L * 1024 * 1024, MAX_TEXT = 8L * 1024 * 1024;
    /** 문서 하나의 추출 글자 상한. */
    static final int MAX_CHARACTERS = 2_000_000;
    static final int MAX_FILENAME = 255;
    static final Set<String> PDF = Set.of("pdf"), IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif"),
            OFFICE = Set.of("docx", "doc", "pptx", "ppt", "xlsx", "xls", "hwp", "hwpx"), TEXT = Set.of("txt", "md");
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("application/pdf", "pdf"), Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"), Map.entry("image/tiff", "tif"),
            Map.entry("image/bmp", "bmp"), Map.entry("image/gif", "gif"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"), Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"), Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"), Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/x-hwp", "hwp"), Map.entry("application/haansofthwp", "hwp"), Map.entry("application/vnd.hancom.hwp", "hwp"),
            Map.entry("application/hwp+zip", "hwpx"), Map.entry("application/haansofthwpx", "hwpx"), Map.entry("application/vnd.hancom.hwpx", "hwpx"),
            Map.entry("text/plain", "txt"), Map.entry("text/markdown", "md"));

    /** Docling 사용 방식. auto=의심 쪽만, off=쓰지 않음, force=PDF·단일 이미지의 모든 쪽(최대 쪽 수 상한 안). */
    enum Layout {
        AUTO, OFF, FORCE;
        static Layout parse(String value) {
            if (value == null) return AUTO;
            return switch (value) { case "auto" -> AUTO; case "off" -> OFF; case "force" -> FORCE; default -> throw new Rejected(400, "layout은 auto|off|force"); };
        }
        String label() { return name().toLowerCase(Locale.ROOT); }
    }
    /** 쪽 하나. page는 1부터, source는 pdfbox·ocr·docling·extractor. */
    record Page(int page, String text, String source) { }
    /** 처리 결과. type은 판별한 확장자, docling은 off·none·converted·partial·failed·unavailable, doclingPages는 쪽별 결과다. */
    record Result(String type, List<Page> pages, Layout layout, String docling, SortedMap<Integer, String> doclingPages) {
        JsonObject metadata() {
            var o = new JsonObject();
            o.addProperty("type", type); o.addProperty("pages", pages.size()); o.addProperty("layout", layout.label());
            o.addProperty("docling", docling);
            if (!doclingPages.isEmpty()) {
                var p = new JsonObject(); doclingPages.forEach((k, v) -> p.addProperty(Integer.toString(k), v)); o.add("doclingPages", p);
            }
            return o;
        }
    }
    /** 상태 코드가 정해진 거절(400·404·413·415·504). 메시지에는 입력 내용을 넣지 않는다. */
    static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status, String message) { super(message, null, false, false); this.status = status; }
    }
    /** 다른 문서를 처리 중(503). */
    static final class Busy extends RuntimeException { Busy() { super(null, null, false, false); } }

    private final Path temporary;
    private final OcrTextExtractor ocr;
    private final IsolatedExtractor isolated;
    private final long documentTimeoutNanos;
    private final RemoteDoclingClient docling;
    private final DoclingPages pages;
    private final Semaphore slot = new Semaphore(1);

    /**
     * data는 임시 파일 폴더의 부모, ocr·docling은 꺼졌으면 null, doclingMaxPages는 문서 하나에서 Docling으로 보낼 최대 쪽 수,
     * documentTimeoutSeconds는 요청 하나의 전체 시간 상한, isolated는 격리 추출(null이면 data·ocr로 만든다. 시험은 멈추는 자식을 넣는다)이다.
     */
    MlPipeline(Path data, OcrTextExtractor ocr, RemoteDoclingClient docling, int doclingMaxPages, int documentTimeoutSeconds, IsolatedExtractor isolated) throws IOException {
        if (documentTimeoutSeconds < 1 || documentTimeoutSeconds > MlConfig.MAX_DOCUMENT_TIMEOUT_SECONDS) throw new IllegalArgumentException("문서 처리 시간 상한 범위 오류");
        var folder = data.resolve("ml-tmp");
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
            Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        this.temporary = folder.toRealPath(LinkOption.NOFOLLOW_LINKS);
        cleanStale(temporary);
        this.ocr = ocr; this.docling = docling;
        this.isolated = isolated != null ? isolated : new IsolatedExtractor(data, ocr);
        this.documentTimeoutNanos = TimeUnit.SECONDS.toNanos(documentTimeoutSeconds);
        var t = LayoutCheck.Thresholds.DEFAULT;
        var thresholds = new LayoutCheck.Thresholds(t.minGridLines(), t.minColumns(), t.minRows(), t.minImagePercent(), doclingMaxPages);
        // 요청 단위 재시도는 하지 않으므로 재시도 상한·대기는 0이다.
        this.pages = new DoclingPages(() -> Optional.ofNullable(docling), () -> thresholds, () -> 0, 0);
    }
    boolean doclingConnected() { return docling != null; }
    boolean ocrEnabled() { return ocr != null; }
    DoclingPages.Stats doclingStats() { return pages.stats(); }

    /** 강제 종료로 남은 24시간 지난 요청 임시 파일만 지운다(같은 데이터 폴더를 쓰는 다른 실행과 겹치지 않게). */
    private static void cleanStale(Path folder) {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        try (var entries = Files.newDirectoryStream(folder, "req-*")) {
            for (Path entry : entries) {
                try {
                    var a = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (a.lastModifiedTime().toMillis() < cutoff && !a.isDirectory()) Files.deleteIfExists(entry);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /**
     * 형식을 정한다. 파일 이름의 확장자가 지원 형식이면 그것을, 아니면 Content-Type(매개변수 제외)으로 정한다. 둘 다 아니면 415.
     */
    static String type(String filename, String contentType) {
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT); int dot = lower.lastIndexOf('.');
            String ext = dot < 0 ? "" : lower.substring(dot + 1);
            if (supported(ext)) return ext;
        }
        if (contentType != null) {
            String media = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String ext = MEDIA_TYPES.get(media);
            if (ext != null) return ext;
        }
        throw new Rejected(415, "지원하지 않는 형식 / Unsupported media type");
    }
    private static boolean supported(String ext) { return PDF.contains(ext) || IMAGES.contains(ext) || OFFICE.contains(ext) || TEXT.contains(ext); }
    static long limit(String type) {
        if (IMAGES.contains(type)) return MAX_IMAGE;
        if (OFFICE.contains(type)) return MAX_OFFICE;
        if (TEXT.contains(type)) return MAX_TEXT;
        return MAX_DOCUMENT;
    }

    /**
     * X-Filename 해석. Open WebUI는 Python {@code urllib.parse.quote}로 퍼센트 인코딩한 UTF-8 이름을 보낸다. '+'는 공백으로 바꾸지 않는다.
     * 경로 부분을 버리고 NFC로 정규화하며 제어 문자는 '?'로 바꾼다. 잘못된 인코딩은 400이다. 비어 있으면 null.
     */
    static String filename(String header) {
        if (header == null || header.isBlank()) return null;
        if (header.length() > 3 * MAX_FILENAME * 4) throw new Rejected(400, "X-Filename 길이 초과");
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '%') {
                if (i + 2 >= header.length()) throw new Rejected(400, "X-Filename 인코딩 오류");
                int hi = Character.digit(header.charAt(i + 1), 16), lo = Character.digit(header.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new Rejected(400, "X-Filename 인코딩 오류");
                bytes.write(hi * 16 + lo); i += 2;
            } else if (c < 0x80) bytes.write(c);
            else throw new Rejected(400, "X-Filename 인코딩 오류");
        }
        String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new Rejected(400, "X-Filename 인코딩 오류"); }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}\\p{Cf}]", "?").strip();
        if (name.isEmpty()) return null;
        if (name.codePointCount(0, name.length()) > MAX_FILENAME) throw new Rejected(400, "X-Filename 길이 초과");
        return name;
    }

    /** 본문을 상한까지만 임시 파일로 받는다. 선언된 길이·실제 길이가 상한을 넘으면 413. */
    Path receive(InputStream in, long declaredLength, long limit) throws IOException {
        if (declaredLength > limit) throw new Rejected(413, "요청 크기 상한 초과");
        Path file = Files.createTempFile(temporary, "req-", ".bin", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        boolean ok = false;
        try (in; var out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) throw new Rejected(413, "요청 크기 상한 초과");
                out.write(buffer, 0, n);
            }
            if (total == 0) throw new Rejected(400, "빈 본문 / Empty body");
            ok = true;
            return file;
        } finally { if (!ok) Files.deleteIfExists(file); }
    }

    /** 다른 문서 처리 중이면 기다리지 않고 Busy. 받은 슬롯은 {@link #release()}로 돌려준다. */
    void acquire() { if (!slot.tryAcquire()) throw new Busy(); }
    void release() { slot.release(); }

    /** 기존 /v1/ocr: 단일 프레임 이미지를 격리 자식이 디코딩하고 OCR한다(Docling 없음). 호출자는 슬롯을 가진 상태여야 한다. */
    Result image(byte[] data) throws IOException {
        if (ocr == null) throw new Rejected(404, "비활성 기능 / Feature disabled");
        long deadline = System.nanoTime() + documentTimeoutNanos;
        String text = bounded(deadline, () -> ocr.recognizeImage(data, deadline));
        return new Result("image", List.of(new Page(1, text, "ocr")), Layout.OFF, "off", new TreeMap<>());
    }
    private interface Work<T> { T run() throws IOException; }
    /** 마감을 넘겼거나 시간 상한으로 중단된 실패는 504로 바꾼다. 상한 위반·거절은 그대로 둔다. */
    private static <T> T bounded(long deadline, Work<T> work) throws IOException {
        try { return work.run(); }
        catch (Rejected | ExtractionLimit e) { throw e; }
        catch (IOException | RuntimeException e) {
            if (System.nanoTime() - deadline >= 0 || timedOut(e)) throw new Rejected(504, "문서 처리 시간 초과 / Processing timed out");
            throw e;
        }
    }
    private static boolean timedOut(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof ExtractionFailure f && f.timedOut()) return true;
        }
        return false;
    }

    /**
     * 받은 파일을 형식별로 추출한다. 호출자는 슬롯을 가진 상태여야 하며 파일은 호출자가 지운다.
     * force인데 Docling이 연결되지 않았으면 400, 이미지인데 OCR이 꺼졌으면 404다.
     */
    Result extract(Path file, String type, Layout layout) throws IOException {
        if (layout == Layout.FORCE && docling == null) throw new Rejected(400, "Docling 미연결 / layout=force requires Docling");
        if (Files.size(file) > limit(type)) throw new Rejected(413, "요청 크기 상한 초과");
        if (IMAGES.contains(type) && ocr == null) throw new Rejected(404, "OCR 비활성 / OCR disabled");
        Path name = Path.of("document." + type);
        // 요청 하나의 단일 마감. 추출·OCR·Docling이 모두 이 시각까지 남은 시간만 쓴다.
        long deadline = System.nanoTime() + documentTimeoutNanos;
        // Docling이 연결됐고 대상 형식(PDF·이미지)일 때만 판단 객체를 만든다. off이거나 미연결이면 null이라 판단 비용이 없다.
        LayoutCheck check = layout == Layout.OFF ? null : pages.check(name, layout == Layout.FORCE);
        var parts = new ArrayList<DoclingPages.Part>();
        TextExtractor.LocatedConsumer collect = (page, location, text) -> parts.add(new DoclingPages.Part(page, location, text));
        bounded(deadline, () -> {
            // 텍스트는 문자 집합 판별·글자 상한만 있는 기존 추출기를 그대로 쓰고, 나머지 비신뢰 형식은 격리 자식에서 파싱한다.
            if (TEXT.contains(type)) new PlainTextExtractor().extractLocated(name, file, collect, MAX_CHARACTERS, null);
            else isolated.extract(name, file, collect, MAX_CHARACTERS, check, deadline);
            if (System.nanoTime() - deadline >= 0) throw new ExtractionFailure("deadline", null, true, "TimeoutException");
            return null;
        });
        String status = check == null ? "off" : "none";
        SortedMap<Integer, String> results = new TreeMap<>();
        List<DoclingPages.Part> finalParts = parts;
        if (check != null && !check.suspects().isEmpty()) {
            var outcome = pages.refine("ml-request", name, file, check, parts, MAX_CHARACTERS, deadline);
            finalParts = outcome.parts(); results = outcome.pages();
            status = summary(outcome);
        }
        return new Result(type, group(type, finalParts), layout, status, results);
    }
    /** 쪽별 결과 요약. 일시 실패(혼잡·연결 실패·5xx·429)가 있으면 unavailable, 변환 쪽이 있으면 converted 또는 partial. */
    static String summary(DoclingPages.Outcome outcome) {
        var values = outcome.pages().values();
        long ok = values.stream().filter(v -> v.equals("converted") || v.equals("supplemented")).count();
        if (values.isEmpty()) return "none";
        if (ok == values.size()) return "converted";
        if (ok > 0) return "partial";
        return outcome.retry() ? "unavailable" : "failed";
    }
    /**
     * 조각을 응답 쪽으로 묶는다. PDF·이미지는 쪽 번호, 슬라이드 형식은 슬라이드, 시트 형식은 시트 순서, 그 밖(문서·HWP·텍스트)은 한 쪽이다.
     * 쪽 안에 Docling 결과가 있으면 docling, OCR이면 ocr, PDF 글자 층이면 pdfbox, 그 밖은 extractor다.
     */
    static List<Page> group(String type, List<DoclingPages.Part> parts) {
        boolean paged = PDF.contains(type) || IMAGES.contains(type);
        var texts = new TreeMap<Integer, StringBuilder>(); var sources = new TreeMap<Integer, String>();
        var sheets = new LinkedHashMap<String, Integer>();
        for (var part : parts) {
            var l = part.location();
            int key;
            if (paged) key = part.page();
            else if (l != null && l.slide() != null) key = l.slide();
            else if (l != null && l.sheet() != null) key = sheets.computeIfAbsent(l.sheet(), s -> sheets.size() + 1);
            else key = 1;
            var text = texts.computeIfAbsent(key, k -> new StringBuilder());
            if (!text.isEmpty() && !part.text().isEmpty()) text.append(paged ? "\n\n" : "\n");
            text.append(part.text());
            String source = l == null ? "extractor" : switch (l.type()) {
                case "docling" -> "docling"; case "ocr" -> "ocr"; case "page" -> PDF.contains(type) ? "pdfbox" : "extractor"; default -> "extractor";
            };
            sources.merge(key, source, MlPipeline::stronger);
        }
        if (texts.isEmpty()) texts.put(1, new StringBuilder());
        var result = new ArrayList<Page>();
        texts.forEach((k, v) -> result.add(new Page(k, v.toString(), sources.getOrDefault(k, paged && PDF.contains(type) ? "pdfbox" : "extractor"))));
        return result;
    }
    private static String stronger(String a, String b) {
        var order = List.of("extractor", "pdfbox", "ocr", "docling");
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }
    public void close() { }
}

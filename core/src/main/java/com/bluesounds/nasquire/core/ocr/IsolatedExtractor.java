package com.bluesounds.nasquire.core.ocr;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * ML 서버 문서 처리용 격리 추출. 비신뢰 문서 파싱(PDF 글자 층 추출·표·그림 쪽 판단, Office/HWP 추출)을 OCR 격리 자식 프로세스
 * ({@link OcrWorker} extract 모드)에서 하고, 스캔 쪽·이미지는 기존 OCR 렌더링 자식과 OCR 공급자로 처리한다. 모든 단계는 호출자의 마감 시각
 * 안에서만 진행되며 넘기면 자식(자손 포함)을 강제 종료하고 종료를 확인한 뒤 슬롯을 돌려준다. 자식 결과는 크기·조각 수·글자 수를 다시 검사한다.
 * 색인기는 이 클래스를 쓰지 않는다(기존 {@link OcrTextExtractor}·추출기 경로 그대로).
 */
public final class IsolatedExtractor {
    static final int MAX_PARTS = 100_000;
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif"),
            OFFICE = Set.of("docx", "doc", "pptx", "ppt", "xlsx", "xls", "hwp", "hwpx");
    private final OcrSandbox sandbox;
    private final OcrTextExtractor ocr;

    /** ocr은 OCR이 꺼졌으면 null이다. 켜졌으면 그 OCR과 같은 격리 작업 폴더를 쓴다. */
    public IsolatedExtractor(Path data, OcrTextExtractor ocr) { this(ocr != null ? ocr.sandbox() : new OcrSandbox(data), ocr); }
    IsolatedExtractor(OcrSandbox sandbox, OcrTextExtractor ocr) { this.sandbox = Objects.requireNonNull(sandbox); this.ocr = ocr; }

    /** 격리 추출 대상 형식(PDF·이미지·Office/HWP)인지. 원래 이름의 확장자로 정한다. */
    public static boolean supports(Path name) {
        String type = type(name);
        return type.equals("pdf") || IMAGES.contains(type) || OFFICE.contains(type);
    }
    private static String type(Path name) {
        String lower = name.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    /**
     * name은 형식 판별용 이름(document.pdf 등), file은 받은 파일, check는 Docling 연결 시의 쪽 판단(없으면 null), deadlineNanos는
     * System.nanoTime 기준 마감 시각이다. 상한 위반은 {@link ExtractionLimit}, 시간 초과는 timedOut인 {@link ExtractionFailure}다.
     */
    public void extract(Path name, Path file, TextExtractor.LocatedConsumer consumer, int maxCharacters, LayoutCheck check, long deadlineNanos) throws IOException {
        String type = type(name);
        int[] remaining = {maxCharacters};
        boolean grid = check != null && !check.always();
        if (IMAGES.contains(type)) {
            if (ocr == null) throw new IOException("OCR 비활성");
            try (var job = limited(() -> sandbox.image(file, ocr.maxPages(), grid, deadlineNanos))) {
                // 여러 프레임 이미지는 쪽 범위로 나눠 보낼 수 없어 판단하지 않는다.
                if (check != null && job.count() == 1) OcrTextExtractor.scanned(check, 1, job.grid(1));
                for (int i = 1; i <= job.count(); i++) emit(consumer, i, OcrTextExtractor.ocrLocation(i), ocr.recognize(job.page(i), deadlineNanos), remaining);
            }
            return;
        }
        boolean pdf = type.equals("pdf");
        if (!pdf && !OFFICE.contains(type)) throw new IOException("격리 추출 형식 오류");
        var t = check == null ? null : check.thresholds();
        // OCR이 켜졌으면 글자 없는 쪽은 자식 렌더링 래스터로 판단하므로 글자 층 판단에서 뺀다(색인기 OCR 경로와 같다).
        var params = new OcrWorker.Params(type, maxCharacters, check == null ? "none" : check.always() ? "always" : "inspect", ocr == null,
                t == null ? null : new int[] {t.minGridLines(), t.minColumns(), t.minRows(), t.minImagePercent(), t.maxPages()});
        OcrWorker.Extracted extracted;
        try (var job = limited(() -> sandbox.extract(file, OcrWorker.GSON.toJson(params), deadlineNanos))) {
            extracted = parse(job.result(), maxCharacters);
        }
        if (check != null) extracted.suspects().forEach(check::suspect);
        var parts = extracted.parts();
        var blank = new ArrayList<Integer>();
        if (pdf && ocr != null) for (var p : parts) if (p.text().isBlank()) blank.add(p.order());
        if (blank.isEmpty()) { for (var p : parts) emit(consumer, p.order(), p.location(), p.text(), remaining); return; }
        if (blank.size() > ocr.maxPages()) throw new ExtractionLimit("OCR 쪽 상한 초과");
        try (var job = limited(() -> sandbox.pdf(file, blank, ocr.maxPages(), grid, deadlineNanos))) {
            int n = 0;
            for (var p : parts) {
                if (p.text().isBlank()) {
                    int index = ++n;
                    if (check != null) OcrTextExtractor.scanned(check, p.order(), job.grid(index));
                    emit(consumer, p.order(), OcrTextExtractor.ocrLocation(p.order()), ocr.recognize(job.page(index), deadlineNanos), remaining);
                } else emit(consumer, p.order(), p.location(), p.text(), remaining);
            }
        }
    }

    /** 자식 결과를 다시 검사한다. 조각 수·글자 수·쪽 번호·근거 길이가 맞지 않으면 실패(자식 출력은 신뢰하지 않는다). */
    static OcrWorker.Extracted parse(byte[] json, int maxCharacters) throws IOException {
        OcrWorker.Extracted extracted;
        try { extracted = OcrWorker.GSON.fromJson(new String(json, StandardCharsets.UTF_8), OcrWorker.Extracted.class); }
        catch (RuntimeException e) { throw new IOException("격리 추출 결과 오류"); }
        if (extracted == null || extracted.parts() == null || extracted.parts().size() > MAX_PARTS) throw new IOException("격리 추출 결과 오류");
        long total = 0;
        for (var p : extracted.parts()) {
            if (p == null || p.order() < 1 || p.text() == null || p.location() == null || p.location().type() == null || p.location().type().length() > 32)
                throw new IOException("격리 추출 결과 오류");
            total += p.text().length();
        }
        if (total > maxCharacters) throw new ExtractionLimit("추출 텍스트 상한 초과");
        var suspects = extracted.suspects() == null ? Map.<Integer, String>of() : extracted.suspects();
        for (var e : suspects.entrySet())
            if (e.getKey() == null || e.getKey() < 1 || e.getValue() == null || !e.getValue().matches("[A-Za-z0-9 %.]{1,64}")) throw new IOException("격리 추출 결과 오류");
        return new OcrWorker.Extracted(extracted.parts(), new TreeMap<>(suspects));
    }

    private interface Start { OcrSandbox.Job start() throws IOException; }
    /** 자식이 상한 위반으로 끝났으면 종류를 보존해 {@link ExtractionLimit}로 바꾼다. */
    private static OcrSandbox.Job limited(Start start) throws IOException {
        try { return start.start(); }
        catch (ExtractionFailure e) {
            var limit = OcrTextExtractor.limit(e);
            if (limit != null) throw limit;
            throw e;
        }
    }
    private static void emit(TextExtractor.LocatedConsumer consumer, int page, Location location, String text, int[] remaining) throws IOException {
        if (text.length() > remaining[0]) throw new ExtractionLimit("추출 텍스트 상한 초과");
        remaining[0] -= text.length(); consumer.accept(page, location, text);
    }
}

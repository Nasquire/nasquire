package com.bluesounds.nasquire.core.ocr;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * 텍스트가 없는 PDF 쪽만 OCR하고 이미지는 프레임 단위로 처리한다. 원본 열기·검사·렌더링·디코딩은 {@link OcrSandbox}의 자식 프로세스가 하고
 * 이 JVM은 자식이 만든 PNG만 OCR 공급자(Tesseract 외부 프로세스)에 넘긴다.
 */
public final class OcrTextExtractor implements TextExtractor {
    private final Providers.OcrProvider provider;
    private final int maxPages;
    private final OcrSandbox sandbox;
    /** OCR에 넘기는 렌더링·디코딩 결과의 픽셀 상한. 원본이 크면 부분 샘플링·DPI 축소로 이 안에 맞춘다. */
    static final int MAX_PIXELS = 6_000_000;
    /** 디코딩 결과 래스터(가로×세로×밴드×바이트) 상한과 PDF 내장 이미지 원본 래스터 상한. */
    static final long MAX_DECODE_BYTES = 24L * 1024 * 1024, MAX_EMBEDDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SIDE = 16_384, MIN_DPI = 50, DPI = 150;
    public OcrTextExtractor(Providers.OcrProvider provider, int maxPages, Path data) { this(provider, maxPages, new OcrSandbox(data)); }
    OcrTextExtractor(Providers.OcrProvider provider, int maxPages, OcrSandbox sandbox) {
        if (maxPages < 1 || maxPages > 100) throw new IllegalArgumentException("OCR 쪽 상한 오류");
        this.provider = provider; this.maxPages = maxPages; this.sandbox = sandbox;
    }
    OcrSandbox sandbox() { return sandbox; }
    int maxPages() { return maxPages; }
    public boolean supports(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif").contains(name.substring(name.lastIndexOf('.') + 1));
    }
    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(path, (page, location, text) -> consumer.accept(page, text), maxCharacters);
    }
    private record Part(int page, Location location, String text) { }
    public void extractLocated(Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(path, path, consumer, maxCharacters);
    }
    @Override
    public void extractLocated(Path name, Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(name, path, consumer, maxCharacters, null);
    }
    /**
     * check가 있으면(Docling 연결 시에만) 글자 층 쪽은 PDFBox 신호로, 스캔 쪽·단일 프레임 이미지는 자식이 렌더링한 래스터의 직선 격자로 판단한다.
     * 원격 OCR·Tesseract 모두 글자만 돌려주므로(단어 위치 없음) 스캔 쪽의 단어 위치 정렬은 쓰지 않는다.
     */
    @Override
    public void extractLocated(Path name, Path path, LocatedConsumer consumer, int maxCharacters, LayoutCheck check) throws IOException {
        int[] remaining = {maxCharacters};
        boolean grid = check != null && !check.always();
        if (name.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            // 텍스트 추출은 기존 PDF 추출기(같은 상한)로 하고, 텍스트 없는 쪽만 모아 자식 프로세스 한 번으로 렌더링한다.
            var parts = new ArrayList<Part>(); var blank = new ArrayList<Integer>();
            new PdfTextExtractor().extract(path, (page, text) -> {
                if (text.isBlank()) {
                    if (blank.size() >= maxPages) throw new IOException("OCR 쪽 상한 초과");
                    blank.add(page); parts.add(new Part(page, null, null));
                } else parts.add(new Part(page, Location.page(page), text));
            }, maxCharacters, check, false);
            if (blank.isEmpty()) { for (var p : parts) accept(consumer, p.page(), p.location(), p.text(), remaining); return; }
            try (var job = sandbox.pdf(path, blank, maxPages, grid)) {
                int n = 0;
                for (var p : parts) {
                    if (p.text() == null) {
                        int index = ++n;
                        if (check != null) scanned(check, p.page(), job.grid(index));
                        accept(consumer, p.page(), ocrLocation(p.page()), recognize(job.page(index)), remaining);
                    }
                    else accept(consumer, p.page(), p.location(), p.text(), remaining);
                }
            }
        } else {
            try (var job = sandbox.image(path, maxPages, grid)) {
                // 여러 프레임 이미지는 쪽 범위로 나눠 보낼 수 없어 판단하지 않는다.
                if (check != null && job.count() == 1) scanned(check, 1, job.grid(1));
                for (int i = 1; i <= job.count(); i++) accept(consumer, i, ocrLocation(i), recognize(job.page(i)), remaining);
            }
        }
    }
    static void scanned(LayoutCheck check, int page, int[] lines) {
        if (check.always()) check.always(page);
        else if (lines != null) check.scannedPage(page, lines[0], lines[1]);
    }
    /** 기본 150 DPI. 큰 쪽은 픽셀 상한에 맞춰 DPI를 낮추고 최소 DPI로도 넘으면 거부한다. */
    static float renderDpi(float widthPoints, float heightPoints) throws IOException {
        double area = (double)widthPoints * heightPoints / (72.0 * 72.0);
        if (!(area > 0) || !Double.isFinite(area)) throw new IOException("PDF 쪽 크기 오류");
        double dpi = Math.min(DPI, Math.floor(Math.sqrt(MAX_PIXELS / area)));
        if (dpi < MIN_DPI || widthPoints * dpi / 72 > 8192 || heightPoints * dpi / 72 > 8192) throw new IOException("OCR 픽셀 상한 초과");
        return (float)dpi;
    }
    /** 쪽 자원에 직접 있는 이미지의 원본 래스터 크기를 렌더링 전에 검사한다(중첩 Form·인라인 이미지는 렌더링 시간 제한에 맡긴다). */
    static void checkEmbeddedImages(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
        var resources = page.getResources();
        if (resources == null) return;
        for (var name : resources.getXObjectNames()) {
            if (!resources.isImageXObject(name) || !(resources.getXObject(name) instanceof PDImageXObject image)) continue;
            int bands;
            try { bands = image.getColorSpace().getNumberOfComponents(); } catch (IOException | RuntimeException e) { bands = 4; }
            if (image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE
                    || rasterBytes(image.getWidth(), image.getHeight(), Math.max(1, bands), Math.max(8, image.getBitsPerComponent())) > MAX_EMBEDDED_BYTES)
                throw new IOException("PDF 내장 이미지 상한 초과");
        }
    }
    /**
     * ML 서버용: 단일 프레임 이미지를 같은 자식 프로세스 격리·상한으로 디코딩하고 PNG로 다시 인코딩해 OCR한다.
     * 픽셀·형식·출력 상한 위반은 {@link ExtractionLimit}(ML 서버 413), 그 밖의 입력 오류는 IllegalArgumentException(400)이다.
     */
    public String recognizeImage(byte[] data) throws IOException { return recognizeImage(data, null); }
    /** 마감 시각(System.nanoTime 기준)이 있는 인식. 자식 실행과 OCR 공급자 호출에 남은 시간만 쓴다. */
    public String recognizeImage(byte[] data, long deadlineNanos) throws IOException { return recognizeImage(data, Long.valueOf(deadlineNanos)); }
    private String recognizeImage(byte[] data, Long deadlineNanos) throws IOException {
        try (var job = sandbox.single(data, deadlineNanos)) {
            byte[] png = job.page(1);
            return deadlineNanos == null ? recognize(png) : recognize(png, deadlineNanos);
        }
        catch (ExtractionLimit e) { throw e; }
        catch (ExtractionFailure e) {
            var limit = limit(e);
            if (limit != null) throw limit;
            if (Integer.valueOf(OcrWorker.LIMIT).equals(e.exitCode())) throw new IllegalArgumentException("이미지 오류");
            throw e;
        }
    }
    /** 격리 자식이 상한 위반 종류로 끝났으면 그 종류를 보존한 {@link ExtractionLimit}, 아니면 null. */
    static ExtractionLimit limit(ExtractionFailure failure) {
        if (!Integer.valueOf(OcrWorker.LIMIT).equals(failure.exitCode())) return null;
        String type = failure.exceptionType().split("\\$cause\\$", 2)[0];
        return type.equals(ExtractionLimit.class.getName()) || type.equals(Limit.class.getName()) ? new ExtractionLimit("처리 상한 초과", failure) : null;
    }
    /** 이미지 상한 위반. 자식 종료 코드 2와 종류 이름으로 부모에 전달되며 ML 서버는 413으로 응답한다. */
    static final class Limit extends ExtractionLimit { Limit(String message) { super(message); } }
    /** 헤더 크기만 보고 부분 샘플링 비율을 정해 디코딩 결과를 픽셀·바이트 상한 안에 둔다. */
    static BufferedImage readSampled(javax.imageio.ImageReader reader, int frame) throws IOException {
        int width = reader.getWidth(frame), height = reader.getHeight(frame);
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) throw new Limit("OCR 픽셀 상한 초과");
        var type = reader.getRawImageType(frame);
        if (type == null) {
            var types = reader.getImageTypes(frame);
            if (!types.hasNext()) throw new IOException("이미지 형식 검사 실패");
            type = types.next();
        }
        int bands = type.getNumBands(), bits = Arrays.stream(type.getSampleModel().getSampleSize()).max().orElse(8);
        if (bands < 1 || bands > 8 || bits < 1 || bits > 64) throw new Limit("OCR 이미지 형식 상한 초과");
        int sample = Math.max(1, (int)Math.ceil(Math.sqrt((double)width * height / MAX_PIXELS)));
        while (rasterBytes(ceilDiv(width, sample), ceilDiv(height, sample), bands, bits) > MAX_DECODE_BYTES) sample++;
        var param = reader.getDefaultReadParam();
        if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0);
        return reader.read(frame, param);
    }
    private static int ceilDiv(int value, int divisor) { return (value + divisor - 1) / divisor; }
    static long rasterBytes(int width, int height, int bands, int bits) { return (long)width * height * bands * ((bits + 7) / 8); }
    private String recognize(byte[] png) throws IOException {
        if (png.length == 0 || png.length > OcrWorker.MAX_PNG) throw new IOException("OCR 이미지 상한 초과");
        return provider.recognize(png, "kor+eng");
    }
    /** 마감 시각이 지났으면 보내지 않고 시간 초과, 아니면 남은 시간을 공급자에 넘긴다. */
    String recognize(byte[] png, long deadlineNanos) throws IOException {
        if (png.length == 0 || png.length > OcrWorker.MAX_PNG) throw new ExtractionLimit("OCR 이미지 상한 초과");
        if (deadlineNanos - System.nanoTime() <= 0) throw new ExtractionFailure("ocr", null, true, "TimeoutException");
        return provider.recognize(png, "kor+eng", deadlineNanos);
    }
    static Location ocrLocation(int page) { return new Location("ocr", page, null, null, null, null, null, null); }
    private static void accept(LocatedConsumer consumer, int page, Location location, String text, int[] remaining) throws IOException {
        if (text.length() > remaining[0]) throw new IOException("OCR 텍스트 상한 초과");
        remaining[0] -= text.length(); consumer.accept(page, location, text);
    }
}

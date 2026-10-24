package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 텍스트가 없는 PDF 쪽만 OCR하고 이미지는 프레임 단위로 처리한다. */
public final class OcrTextExtractor implements TextExtractor {
    private final Providers.OcrProvider provider;
    private final int maxPages;
    private final long renderMillis;
    /** OCR에 넘기는 렌더링·디코딩 결과의 픽셀 상한. 원본이 크면 부분 샘플링·DPI 축소로 이 안에 맞춘다. */
    static final int MAX_PIXELS = 6_000_000;
    /** 디코딩 결과 래스터(가로×세로×밴드×바이트) 상한과 PDF 내장 이미지 원본 래스터 상한. */
    static final long MAX_DECODE_BYTES = 24L * 1024 * 1024, MAX_EMBEDDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SIDE = 16_384, MIN_DPI = 50, DPI = 150;
    static final long RENDER_MILLIS = 20_000;
    // 렌더링·디코딩은 별도 스레드에서 시간 제한한다. 중단을 무시하는 디코더도 색인 작업자를 붙잡지 않으며 잔류 스레드는 최대 2개다.
    private static final ThreadPoolExecutor DECODERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { var t = new Thread(r, "nasquire-image"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    static <T> T limited(Callable<T> task, long millis) throws IOException {
        Future<T> future;
        try { future = DECODERS.submit(task); }
        catch (RejectedExecutionException e) { throw new IOException("이미지 작업자 사용 불가 (이전 렌더링 미종료)"); }
        try { return future.get(millis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { future.cancel(true); throw new IOException("이미지 렌더링·디코딩 시간 초과"); }
        catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new IOException("이미지 처리 중단"); }
        catch (ExecutionException e) {
            // 메모리 부족은 그 파일만 실패로 처리한다. 렌더 스레드의 할당은 이미 해제 가능 상태다.
            if (e.getCause() instanceof OutOfMemoryError) throw new IOException("이미지 처리 메모리 부족");
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IOException("이미지 처리 실패");
        }
    }
    public OcrTextExtractor(Providers.OcrProvider provider, int maxPages) { this(provider, maxPages, RENDER_MILLIS); }
    OcrTextExtractor(Providers.OcrProvider provider, int maxPages, long renderMillis) {
        if (maxPages < 1 || maxPages > 100 || renderMillis < 1) throw new IllegalArgumentException("OCR 쪽 상한 오류");
        this.provider = provider; this.maxPages = maxPages; this.renderMillis = renderMillis;
    }
    public boolean supports(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif").contains(name.substring(name.lastIndexOf('.') + 1));
    }
    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(path, (page, location, text) -> consumer.accept(page, text), maxCharacters);
    }
    public void extractLocated(Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        int[] remaining = {maxCharacters};
        if (path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                var renderer = new PDFRenderer(pdf); renderer.setSubsamplingAllowed(true);
                int[] ocrPages = {0};
                new PdfTextExtractor().extractLocated(path, (page, location, text) -> {
                    if (text.isBlank()) {
                        if (++ocrPages[0] > maxPages) throw new IOException("OCR 쪽 상한 초과");
                        var pdPage = pdf.getPage(page - 1);
                        var box = pdPage.getCropBox();
                        float dpi = renderDpi(box.getWidth(), box.getHeight());
                        checkEmbeddedImages(pdPage);
                        // GRAY는 픽셀당 1바이트다. 렌더링 시간 초과·메모리 부족은 그 파일의 추출 실패로 남는다.
                        var image = limited(() -> renderer.renderImageWithDPI(page - 1, dpi, ImageType.GRAY), renderMillis);
                        text = recognize(image);
                        location = ocrLocation(page);
                    }
                    accept(consumer, page, location, text, remaining);
                }, maxCharacters);
            }
        } else {
            try (var input = ImageIO.createImageInputStream(path.toFile())) {
                if (input == null) throw new IOException("이미지 읽기 실패");
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("지원하지 않는 이미지");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int pages = reader.getNumImages(true);
                    if (pages < 1 || pages > maxPages) throw new IOException("OCR 쪽 상한 초과");
                    for (int i = 0; i < pages; i++) {
                        int frame = i;
                        var image = limited(() -> readSampled(reader, frame), renderMillis);
                        accept(consumer, i + 1, ocrLocation(i + 1), recognize(image), remaining);
                    }
                } finally { reader.dispose(); }
            }
        }
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
    private static void checkEmbeddedImages(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
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
    /** ML 서버용: 단일 프레임 이미지를 같은 상한·시간 제한으로 디코딩하고 PNG로 다시 인코딩해 OCR한다. */
    static String recognizeImage(Providers.OcrProvider provider, byte[] data) throws IOException {
        var image = limited(() -> decodeImage(data), RENDER_MILLIS);
        return new OcrTextExtractor(provider, 1).recognize(image);
    }
    private static BufferedImage decodeImage(byte[] data) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) throw new IllegalArgumentException("이미지 읽기 실패");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("지원하지 않는 이미지");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (reader.getNumImages(true) != 1) throw new IllegalArgumentException("단일 프레임 이미지만 지원");
                try { return readSampled(reader, 0); } catch (Limit e) { throw new IllegalArgumentException("OCR 픽셀 상한 초과"); }
            } finally { reader.dispose(); }
        }
    }
    /** 이미지 상한 위반. ML 서버는 입력 오류(400)로 바꾼다. */
    static final class Limit extends IOException { Limit(String message) { super(message); } }
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
    private String recognize(BufferedImage image) throws IOException {
        try {
            if ((long)image.getWidth() * image.getHeight() > MAX_PIXELS) throw new IOException("OCR 픽셀 상한 초과");
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            if (bytes.size() > 16 * 1024 * 1024) throw new IOException("OCR 이미지 상한 초과");
            return provider.recognize(bytes.toByteArray(), "kor+eng");
        } finally { image.flush(); }
    }
    private static Location ocrLocation(int page) { return new Location("ocr", page, null, null, null, null, null, null); }
    private static void accept(LocatedConsumer consumer, int page, Location location, String text, int[] remaining) throws IOException {
        if (text.length() > remaining[0]) throw new IOException("OCR 텍스트 상한 초과");
        remaining[0] -= text.length(); consumer.accept(page, location, text);
    }
}

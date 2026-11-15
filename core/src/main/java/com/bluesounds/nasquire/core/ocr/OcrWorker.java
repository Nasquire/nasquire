package com.bluesounds.nasquire.core.ocr;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.*;

/**
 * OCR 자식 프로세스 진입점. 부모가 데이터 폴더 ocr-tmp 안 0700 작업 폴더에 복사한 입력만 읽고, 그 폴더에 쪽별 PNG만 쓴다.
 * 파일 열기·헤더/프레임/자원 검사·렌더링·디코딩을 모두 여기서 하므로 악성 입력이 멈추거나 메모리를 써도 부모 JVM에는 영향이 없다.
 * 인자: {@code pdf|image|single <작업 폴더> <최대 쪽> [쪽 번호,...]}. 종류 뒤에 {@code -grid}가 붙으면(pdf-grid·image-grid, Docling 연결 시에만)
 * 쪽마다 래스터 직선 격자 수를 {@code grid-N}("가로 세로")에 함께 쓴다. {@code extract}(ML 문서 처리)는 부모가 쓴 {@code params}(JSON)대로
 * PDF 글자 층 추출·쪽 판단 또는 Office/HWP 추출을 하고 조각·의심 쪽을 {@code result}(JSON)에 쓴다.
 * 종료 코드 0 성공, 2 상한·입력 오류, 3 메모리 부족, 1 기타 실패.
 */
public final class OcrWorker {
    static final int OK = 0, FAILED = 1, LIMIT = 2, MEMORY = 3;
    /** 쪽 PNG 하나와 전체 출력 상한. 부모도 같은 상한으로 다시 검사한다. */
    static final long MAX_PNG = 16L * 1024 * 1024, MAX_OUTPUT = 256L * 1024 * 1024;
    private OcrWorker() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }
    public static int run(String[] args) {
        try {
            if (args.length < 3) return FAILED;
            ImageIO.setUseCache(false);
            Path folder = Path.of(args[1]);
            if (!jobFolder(folder)) return FAILED;
            int maxPages = Integer.parseInt(args[2]);
            if (maxPages < 1 || maxPages > 100) return FAILED;
            long[] written = {0};
            boolean grid = args[0].endsWith("-grid");
            String mode = grid ? args[0].substring(0, args[0].length() - 5) : args[0];
            int count = switch (mode) {
                case "pdf" -> pdf(folder, args.length > 3 ? args[3] : "", maxPages, written, grid);
                case "image" -> image(folder, maxPages, false, written, grid);
                case "single" -> image(folder, 1, true, written, false);
                case "extract" -> extract(folder);
                default -> throw new IOException("작업 종류 오류");
            };
            Files.writeString(folder.resolve("count"), String.valueOf(count), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return OK;
        } catch (OcrTextExtractor.Limit | IllegalArgumentException e) { report(args, e); return LIMIT; }
        catch (OutOfMemoryError e) { report(args, e); return MEMORY; }
        catch (Throwable e) {
            // 추출기 상한 위반(원인 사슬 안 포함)은 종류를 남겨 부모가 상한 위반으로 구분하게 한다.
            var limit = com.bluesounds.nasquire.core.ExtractionLimit.find(e);
            if (limit != null) { report(args, limit); return LIMIT; }
            report(args, e); return FAILED;
        }
    }

    /** 스택·메시지 대신 예외 종류만 제한된 파일에 남긴다. */
    private static void report(String[] args, Throwable failure) {
        if (args.length < 2) return;
        Path folder = Path.of(args[1]);
        if (!jobFolder(folder)) return;
        Throwable cause = failure;
        for (int depth = 0; depth < 4 && cause.getCause() != null; depth++) cause = cause.getCause();
        String type = failure.getClass().getName();
        if (cause != failure) type += "$cause$" + cause.getClass().getName();
        if (type.length() > 160) type = failure.getClass().getName();
        try { Files.writeString(folder.resolve("failure"), type, StandardOpenOption.CREATE_NEW); }
        catch (IOException ignored) { }
    }

    /**
     * 직접 실행해도 데이터 폴더 ocr-tmp 안 job-* 작업 폴더(실경로, 링크 아님, 현재 사용자 소유 0700)만 받는다. 다른 폴더의 input을 읽거나
     * 임의 위치에 PNG를 쓰지 않게 한다.
     */
    static boolean jobFolder(Path folder) {
        try {
            if (!folder.isAbsolute() || !folder.normalize().equals(folder) || folder.getParent() == null
                    || !folder.getFileName().toString().matches("job-[0-9A-Za-z_-]{1,64}")
                    || !folder.getParent().getFileName().toString().equals("ocr-tmp") || !folder.toRealPath().equals(folder)) return false;
            var attributes = Files.readAttributes(folder, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            int uid = Files.readAllLines(Path.of("/proc/self/status")).stream().filter(line -> line.startsWith("Uid:"))
                    .map(line -> Integer.parseInt(line.split("\\s+")[2])).findFirst().orElse(-1);
            return attributes.isDirectory() && uid >= 0 && Integer.valueOf(uid).equals(Files.getAttribute(folder, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                    && attributes.permissions().equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | RuntimeException e) { return false; }
    }

    /** extract 결과 조각(순서·근거·글자). */
    record Part(int order, com.bluesounds.nasquire.core.Location location, String text) { }
    /** extract 결과. suspects는 의심 쪽 번호와 근거(쪽 판단을 요청한 PDF만). */
    record Extracted(List<Part> parts, Map<Integer, String> suspects) { }
    /** extract 설정. check는 none·inspect·always, thresholds는 {@link com.bluesounds.nasquire.core.layout.LayoutCheck.Thresholds} 5개 값이다. */
    record Params(String type, int maxCharacters, String check, boolean inspectBlank, int[] thresholds) { }
    static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    private static int extract(Path folder) throws IOException {
        var params = GSON.fromJson(new String(OcrSandbox.readBounded(folder.resolve("params"), 4096), java.nio.charset.StandardCharsets.UTF_8), Params.class);
        if (params == null || params.type() == null || params.maxCharacters() < 1 || params.check() == null) throw new IOException("추출 설정 오류");
        com.bluesounds.nasquire.core.layout.LayoutCheck check = null;
        if (!params.check().equals("none")) {
            int[] t = params.thresholds();
            if (t == null || t.length != 5) throw new IOException("추출 설정 오류");
            check = new com.bluesounds.nasquire.core.layout.LayoutCheck(new com.bluesounds.nasquire.core.layout.LayoutCheck.Thresholds(t[0], t[1], t[2], t[3], t[4]),
                    params.check().equals("always"));
        }
        var parts = new ArrayList<Part>();
        Path input = folder.resolve("input");
        if (params.type().equals("pdf"))
            new com.bluesounds.nasquire.core.PdfTextExtractor().extract(input, (page, text) -> parts.add(new Part(page, com.bluesounds.nasquire.core.Location.page(page), text)),
                    params.maxCharacters(), check, params.inspectBlank());
        else new com.bluesounds.nasquire.core.OfficeTextExtractor().extractLocated(Path.of("document." + params.type()), input,
                (order, location, text) -> parts.add(new Part(order, location, text)), params.maxCharacters());
        byte[] result = GSON.toJson(new Extracted(parts, check == null ? Map.of() : new TreeMap<>(check.suspects()))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (result.length > OcrSandbox.MAX_RESULT) throw new com.bluesounds.nasquire.core.ExtractionLimit("추출 결과 상한 초과");
        Files.write(folder.resolve("result"), result, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return 1;
    }

    private static int pdf(Path folder, String list, int maxPages, long[] written, boolean grid) throws IOException {
        var pages = new ArrayList<Integer>();
        for (String part : list.split(",")) if (!part.isBlank()) pages.add(Integer.parseInt(part));
        if (pages.isEmpty() || pages.size() > maxPages) throw new OcrTextExtractor.Limit("OCR 쪽 상한 초과");
        try (var pdf = Loader.loadPDF(folder.resolve("input").toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            var renderer = new PDFRenderer(pdf); renderer.setSubsamplingAllowed(true);
            int n = 0;
            for (int page : pages) {
                if (page < 1 || page > pdf.getNumberOfPages()) throw new IOException("쪽 번호 오류");
                var pdPage = pdf.getPage(page - 1);
                var box = pdPage.getCropBox();
                float dpi = OcrTextExtractor.renderDpi(box.getWidth(), box.getHeight());
                OcrTextExtractor.checkEmbeddedImages(pdPage);
                // GRAY는 픽셀당 1바이트다.
                write(folder, ++n, renderer.renderImageWithDPI(page - 1, dpi, ImageType.GRAY), written, grid);
            }
            return n;
        }
    }

    private static int image(Path folder, int maxPages, boolean single, long[] written, boolean grid) throws IOException {
        try (var input = ImageIO.createImageInputStream(folder.resolve("input").toFile())) {
            if (input == null) throw new IllegalArgumentException("이미지 읽기 실패");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("지원하지 않는 이미지");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int pages = reader.getNumImages(true);
                if (single && pages != 1) throw new IllegalArgumentException("단일 프레임 이미지만 지원");
                if (pages < 1 || pages > maxPages) throw new OcrTextExtractor.Limit("OCR 쪽 상한 초과");
                for (int i = 0; i < pages; i++) write(folder, i + 1, OcrTextExtractor.readSampled(reader, i), written, grid);
                return pages;
            } finally { reader.dispose(); }
        }
    }

    private static void write(Path folder, int n, BufferedImage image, long[] written, boolean grid) throws IOException {
        try {
            if ((long)image.getWidth() * image.getHeight() > OcrTextExtractor.MAX_PIXELS) throw new OcrTextExtractor.Limit("OCR 픽셀 상한 초과");
            if (grid) {
                int[] lines = com.bluesounds.nasquire.core.layout.RasterGrid.lines(image);
                Files.writeString(folder.resolve("grid-" + n), lines[0] + " " + lines[1], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            Path out = folder.resolve("out-" + n + ".png");
            try (var stream = new BufferedOutputStream(Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                if (!ImageIO.write(image, "png", stream)) throw new IOException("PNG 인코딩 실패");
            }
            long size = Files.size(out);
            written[0] += size;
            if (size > MAX_PNG || written[0] > MAX_OUTPUT) throw new OcrTextExtractor.Limit("OCR 이미지 상한 초과");
        } finally { image.flush(); }
    }
}

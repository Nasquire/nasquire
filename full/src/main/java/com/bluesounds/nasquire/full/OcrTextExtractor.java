package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 텍스트가 없는 PDF 쪽만 OCR하고 이미지는 프레임 단위로 처리한다. */
public final class OcrTextExtractor implements TextExtractor {
    private final Providers.OcrProvider provider;
    private final int maxPages;
    public OcrTextExtractor(Providers.OcrProvider provider, int maxPages) {
        if (maxPages < 1 || maxPages > 100) throw new IllegalArgumentException("OCR 쪽 상한 오류");
        this.provider = provider; this.maxPages = maxPages;
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
                        var box = pdf.getPage(page - 1).getCropBox();
                        checkPixels((int)Math.ceil(box.getWidth() * 150 / 72), (int)Math.ceil(box.getHeight() * 150 / 72));
                        text = recognize(renderer.renderImageWithDPI(page - 1, 150));
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
                        checkPixels(reader.getWidth(i), reader.getHeight(i));
                        accept(consumer, i + 1, ocrLocation(i + 1), recognize(reader.read(i)), remaining);
                    }
                } finally { reader.dispose(); }
            }
        }
    }
    private String recognize(BufferedImage image) throws IOException {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            if (bytes.size() > 16 * 1024 * 1024) throw new IOException("OCR 이미지 상한 초과");
            return provider.recognize(bytes.toByteArray(), "kor+eng");
        } finally { image.flush(); }
    }
    private static void checkPixels(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long)width * height > 16_000_000) throw new IOException("OCR 픽셀 상한 초과");
    }
    private static Location ocrLocation(int page) { return new Location("ocr", page, null, null, null, null, null, null); }
    private static void accept(LocatedConsumer consumer, int page, Location location, String text, int[] remaining) throws IOException {
        if (text.length() > remaining[0]) throw new IOException("OCR 텍스트 상한 초과");
        remaining[0] -= text.length(); consumer.accept(page, location, text);
    }
}

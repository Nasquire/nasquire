package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.layout.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.text.*;

public final class PdfTextExtractor implements TextExtractor {
    public boolean supports(Path path) { return path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"); }

    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        extract(path, consumer, maxCharacters, null, true);
    }

    @Override
    public void extractLocated(Path name, Path content, LocatedConsumer consumer, int maxCharacters, LayoutCheck check) throws IOException {
        extract(content, (page, text) -> consumer.accept(page, Location.page(page), text), maxCharacters, check, true);
    }

    /**
     * 쪽 단위로 글자를 추출한다. check가 있으면(Docling 연결 시에만) 같은 문서 열기 안에서 쪽마다 표·그림 신호를 재어 알린다.
     * inspectBlank가 false면 글자가 없는 쪽(스캔 쪽)은 재지 않는다. OCR 추출기가 래스터 격자로 따로 판단한다.
     */
    public void extract(Path path, PageConsumer consumer, int maxCharacters, LayoutCheck check, boolean inspectBlank) throws IOException {
        try (var pdf = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (!pdf.getCurrentAccessPermission().canExtractContent()) throw new IOException("PDF 텍스트 추출이 허용되지 않습니다");
            int[] remaining = {maxCharacters};
            var glyphs = check == null || check.always() ? null : new PdfLayout.Glyphs();
            var stripper = glyphs == null ? new PDFTextStripper() : new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition text) {
                    super.processTextPosition(text);
                    glyphs.add(text.getXDirAdj(), text.getYDirAdj(), text.getWidthDirAdj(), text.getFontSizeInPt());
                }
            };
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (glyphs != null) glyphs.clear();
                var text = new StringBuilder();
                stripper.writeText(pdf, new Writer() {
                    public void write(char[] chars, int offset, int length) throws IOException {
                        if (length > remaining[0]) throw new ExtractionLimit("추출 텍스트 상한 초과");
                        remaining[0] -= length;
                        text.append(chars, offset, length);
                    }
                    public void flush() { }
                    public void close() { }
                });
                String value = text.toString();
                if (check != null && (inspectBlank || !value.isBlank())) inspect(check, pdf.getPage(page - 1), page, glyphs, !value.isBlank());
                consumer.accept(page, value);
            }
        }
    }

    /** 신호 측정 실패(손상된 그리기 명령 등)는 추출을 막지 않고 그 쪽을 의심하지 않는다. */
    private static void inspect(LayoutCheck check, org.apache.pdfbox.pdmodel.PDPage page, int number, PdfLayout.Glyphs glyphs, boolean hasText) {
        if (check.always()) { check.always(number); return; }
        try { check.textPage(number, PdfLayout.inspect(page, glyphs, hasText, check.thresholds().minRows())); }
        catch (IOException | RuntimeException e) { check.textPage(number, new PageSignals(0, 0, 0, 0, 0, hasText)); }
    }
}

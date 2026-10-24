package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfTextExtractor implements TextExtractor {
    public boolean supports(Path path) { return path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"); }

    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        try (var pdf = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (!pdf.getCurrentAccessPermission().canExtractContent()) throw new IOException("PDF 텍스트 추출이 허용되지 않습니다");
            int[] remaining = {maxCharacters};
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var text = new StringBuilder();
                stripper.writeText(pdf, new Writer() {
                    public void write(char[] chars, int offset, int length) throws IOException {
                        if (length > remaining[0]) throw new IOException("추출 텍스트 상한 초과");
                        remaining[0] -= length;
                        text.append(chars, offset, length);
                    }
                    public void flush() { }
                    public void close() { }
                });
                consumer.accept(page, text.toString());
            }
        }
    }
}

package com.bluesounds.nasquire.core;

import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

/** 검증 전용 자체 작성 문서. 외부 저작물을 사용하지 않는다. */
public class SampleDocuments {
    public static void pdf(Path file, String... texts) throws Exception {
        try (var document = new PDDocument()) {
            for (String text : texts) {
                var page = new PDPage(); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 720); stream.showText(text); stream.endText();
                }
            }
            document.save(file.toFile());
        }
    }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createDirectories(Path.of(args[0]));
        for (int i = 0; i < 20; i++) {
            Files.writeString(folder.resolve("notes-" + i + ".md"), ("# 지식 검색 실험 " + i + "\n한국어 문서 검색과 바다 탐사 기록입니다. Astronomy ocean knowledge search.\n").repeat(60));
            Path pdfPath = folder.resolve("paper-" + i + ".pdf");
            Files.deleteIfExists(pdfPath);
            pdf(pdfPath, "Original sample " + i + ": ocean exploration and astronomy.", "Second page: knowledge search and document indexing.");
        }
        System.out.println("Created 40 original sample documents (20 Markdown, 20 two-page PDF).");
    }
}

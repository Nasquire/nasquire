package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class FullSettingsOcrTest {
    @TempDir Path temp;

    static boolean full() { return ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent(); }

    private FullSettings settings(SearchIndex index) throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        return new FullSettings(data, new EmbeddingSettings(index, data, new MockEnvironment()));
    }

    private void installOcr(FullSettings settings, AtomicInteger calls) throws Exception {
        // app은 full 컴파일 의존성 없이 실제 OCR 추출기를 런타임에 로드한다. 렌더러 격리는 그대로 사용한다.
        Providers.OcrProvider fake = (png, language) -> {
            assertEquals("kor+eng", language);
            assertArrayEquals(new byte[] {(byte)137, 80, 78, 71}, Arrays.copyOf(png, 4));
            calls.incrementAndGet(); return "OCRTESTALPHA 스캔 베타";
        };
        var extractor = Class.forName("com.bluesounds.nasquire.full.OcrTextExtractor")
                .getConstructor(Providers.OcrProvider.class, int.class, Path.class)
                .newInstance(fake, 20, temp.resolve("data"));
        ReflectionTestUtils.setField(settings, "ocr", extractor);
    }

    private void scanPdf(Path target) throws Exception {
        var image = new java.awt.image.BufferedImage(200, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var doc = new PDDocument()) {
            var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 80)); doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.drawImage(JPEGFactory.createFromImage(doc, image), 0, 0, 200, 80);
            }
            doc.save(target.toFile());
        } finally { image.flush(); }
    }

    @Test @EnabledIf("full")
    void originalPdfNameSurvivesExtensionlessContentAndLegacyCalls() throws Exception {
        Path content = temp.resolve("content"); scanPdf(content);
        var calls = new AtomicInteger();
        try (var index = new SearchIndex(temp.resolve("index")); var settings = settings(index)) {
            installOcr(settings, calls);
            var extractor = settings.extractor();
            assertTrue(extractor.supports(Path.of("scan.pdf"))); assertFalse(extractor.supports(content));
            var locations = new ArrayList<Location>();
            extractor.extractLocated(Path.of("scan.pdf"), content, (p, l, t) -> {
                assertEquals(1, p); assertEquals("OCRTESTALPHA 스캔 베타", t); locations.add(l);
            }, 1000);
            assertEquals(1, locations.size()); assertEquals("ocr", locations.getFirst().type());
            assertEquals(1, locations.getFirst().page());
            Path pdf = temp.resolve("scan.pdf"); Files.copy(content, pdf);
            var texts = new ArrayList<String>();
            extractor.extract(pdf, (p, t) -> texts.add(t), 1000);
            assertEquals(List.of("OCRTESTALPHA 스캔 베타"), texts);
            Path png = temp.resolve("scan.png");
            var image = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
            try { javax.imageio.ImageIO.write(image, "png", png.toFile()); } finally { image.flush(); }
            extractor.extractLocated(png, (p, l, t) -> assertEquals("ocr", l.type()), 1000);
            assertEquals(3, calls.get());
        }
    }

    @Test @EnabledIf("full")
    void indexingThroughSettingsUsesPinnedPdfContentAndSearchesOcrEvidence() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source")); scanPdf(source.resolve("scan.pdf"));
        var calls = new AtomicInteger();
        try (var index = new SearchIndex(temp.resolve("index")); var settings = settings(index)) {
            installOcr(settings, calls);
            try (var indexing = new IndexingService(index, 32 * 1024 * 1024, 10000, 0,
                    List.of(settings.extractor(), new PdfTextExtractor()))) {
                indexing.scan(List.of(new Source("pdf", "PDF", source, List.of("**/*.pdf"), List.of())));
                assertEquals(1, indexing.status("pdf").indexed()); assertEquals(0, indexing.status("pdf").errors());
                assertTrue(indexing.status("pdf").recentFailures().isEmpty()); assertEquals(1, calls.get());
                for (String query : List.of("OCRTESTALPHA", "베타")) {
                    var hits = index.search(query, Set.of("pdf"), 10);
                    assertEquals(1, hits.size()); assertEquals("ocr", hits.getFirst().location().type());
                    assertEquals(1, hits.getFirst().location().page());
                }
            }
            try (var files = Files.list(temp.resolve("data/ocr-tmp"))) { assertEquals(0, files.count()); }
        }
    }

    @Test void disabledOcrFallsBackUsingOriginalNameAndReadsOnlyContent() throws Exception {
        Path content = temp.resolve("content");
        try (var doc = new PDDocument()) {
            var page = new PDPage(); doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(20, 700); stream.showText("existing text"); stream.endText();
            }
            doc.save(content.toFile());
        }
        try (var index = new SearchIndex(temp.resolve("index")); var settings = settings(index)) {
            var extractor = settings.extractor();
            assertFalse(extractor.supports(Path.of("scan.pdf")));
            var texts = new ArrayList<String>();
            extractor.extractLocated(Path.of("scan.pdf"), content, (p, l, t) -> {
                assertEquals("page", l.type()); texts.add(t);
            }, 1000);
            assertEquals(1, texts.size()); assertTrue(texts.getFirst().contains("existing text"));
            assertThrows(IOException.class, () -> extractor.extractLocated(Path.of("scan.png"), content, (p, l, t) -> {}, 1000));
        }
    }
}

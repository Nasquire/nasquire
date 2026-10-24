package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FullProviderTest {
    @TempDir Path temp;
    private List<SearchIndex.Hit> hits() {
        return List.of(new SearchIndex.Hit("s", "S", "s:a", 2, 0, "alpha", 1, 10),
                new SearchIndex.Hit("s", "S", "s:b", 3, 1200, "beta", 2, 9),
                new SearchIndex.Hit("s", "S", "s:c", 4, 2400, "gamma", 3, 8));
    }
    @Test void remoteOrderEvidenceTopNInvalidAndBoundedFallback() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<>("{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}");
        var received = new AtomicReference<String>(); var auth = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        server.createContext("/v1/rerank", e -> {
            received.set(new String(e.getRequestBody().readAllBytes())); auth.set(e.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.get().getBytes(); e.sendResponseHeaders(status.get(), bytes.length);
            try { e.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { } finally { e.close(); }
        }); server.start();
        try (var provider = new RemoteRerankerProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", "test-key", 2, 1)) {
            var input = hits(); var ranked = provider.rerank("query", input);
            assertEquals(List.of(input.get(1), input.get(0), input.get(2)), ranked);
            assertEquals(1200, ranked.getFirst().offset()); assertEquals(3, ranked.getFirst().location().page());
            assertTrue(received.get().contains("test-model")); assertFalse(received.get().contains("gamma")); assertEquals("Bearer test-key", auth.get());
            for (String bad : List.of("{}", "{\"results\":[]}",
                    "{\"results\":[{\"index\":0,\"relevance_score\":0},{\"index\":0,\"relevance_score\":1}]}",
                    "{\"results\":[{\"index\":0.5,\"relevance_score\":0},{\"index\":1,\"relevance_score\":1}]}",
                    "{\"results\":[{\"index\":0,\"relevance_score\":\"NaN\"},{\"index\":1,\"relevance_score\":1}]}", "x".repeat(70000))) {
                body.set(bad); assertSame(input, provider.rerank("query", input));
            }
            status.set(503); assertSame(input, provider.rerank("query", input));
        } finally { server.stop(0); }
    }
    @Test void timeoutCoversBodyAndConcurrentRequestFallsBackImmediately() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/rerank", e -> {
            e.getRequestBody().readAllBytes(); e.sendResponseHeaders(200, 2); entered.countDown();
            try { Thread.sleep(1800); e.getResponseBody().write("{}".getBytes()); }
            catch (Exception ignored) { } finally { e.close(); }
        }); server.start();
        try (var p = new RemoteRerankerProvider("http://127.0.0.1:" + server.getAddress().getPort(), "test", "", 2, 1)) {
            var input = hits(); long start = System.nanoTime();
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = pool.submit(() -> p.rerank("q", input));
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                long concurrent = System.nanoTime(); assertSame(input, p.rerank("q", input));
                assertTrue(System.nanoTime() - concurrent < 200_000_000L);
                assertSame(input, first.get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(System.nanoTime() - start < 1_700_000_000L);
            }
        } finally { server.stop(0); }
    }
    @Test void tesseractArgumentsLimitsFailureTimeoutAndCleanup() throws Exception {
        Path fake = temp.resolve("fake-ocr");
        Files.writeString(fake, "#!/bin/sh\n[ \"$3\" = '-l' ] && [ \"$4\" = 'kor+eng' ] && [ \"$5\" = '--psm' ] && [ \"$6\" = '3' ] || exit 3\n[ \"$(ulimit -v)\" = '131072' ] && [ \"$(ulimit -t)\" = '1' ] || exit 4\nprintf '인식 OCR' > \"$2.txt\"\n");
        assertTrue(fake.toFile().setExecutable(true));
        var p = new TesseractOcrProvider(temp, 1, 128, fake.toString());
        assertEquals("인식 OCR", p.recognize(new byte[] {1}, "kor+eng"));
        assertThrows(IllegalArgumentException.class, () -> p.recognize(new byte[] {1}, "eng"));
        Files.writeString(fake, "#!/bin/sh\nexit 1\n"); assertThrows(IllegalStateException.class, () -> p.recognize(new byte[] {1}, "kor+eng"));
        Files.writeString(fake, "#!/bin/sh\nexec /usr/bin/head -c 2097152 /dev/zero > \"$2.txt\"\n");
        assertThrows(IllegalStateException.class, () -> p.recognize(new byte[] {1}, "kor+eng"));
        Files.writeString(fake, "#!/bin/sh\nexec /bin/sleep 10\n"); long start = System.nanoTime();
        assertThrows(IllegalStateException.class, () -> p.recognize(new byte[] {1}, "kor+eng")); assertTrue(System.nanoTime() - start < 3_000_000_000L);
        try (var files = Files.list(temp.resolve("ocr-tmp"))) { assertEquals(0, files.count()); }
    }
    @Test void ocrImagesPdfPageEvidenceAndCharacterPagePixelLimits() throws Exception {
        var calls = new AtomicInteger();
        var extractor = new OcrTextExtractor((image, language) -> { assertEquals("kor+eng", language); calls.incrementAndGet(); return "인식 OCR"; }, 1, temp);
        Path image = temp.resolve("scan.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        var locations = new ArrayList<Location>();
        extractor.extractLocated(image, (p, l, t) -> { locations.add(l); assertEquals("인식 OCR", t); }, 100);
        assertEquals("ocr", locations.getFirst().type()); assertEquals(1, locations.getFirst().page());
        byte[] original = Files.readAllBytes(image);
        try (var index = new SearchIndex(temp.resolve("image-index"));
             var indexing = new IndexingService(index, 32 * 1024 * 1024, 10000, 0, List.of(extractor))) {
            indexing.scan(List.of(new Source("image", "Image", temp, List.of("**/*.png"), List.of())));
            assertEquals(1, indexing.status("image").indexed()); assertEquals(0, indexing.status("image").errors());
            var result = index.search("OCR", Set.of("image"), 10); assertEquals(1, result.size()); assertEquals("ocr", result.getFirst().location().type());
        }
        assertArrayEquals(original, Files.readAllBytes(image));
        assertThrows(java.io.IOException.class, () -> extractor.extract(image, (p, t) -> {}, 1));
        Path pdf = temp.resolve("scan.pdf");
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < 2; i++) {
                var page = new org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page);
                try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromFileByContent(image.toFile(), doc), 20, 20);
                }
            }
            doc.save(pdf.toFile());
        }
        assertThrows(java.io.IOException.class, () -> extractor.extract(pdf, (p, t) -> {}, 100));
        var pdfExtractor = new OcrTextExtractor((bytes, language) -> "스캔 결과", 2, temp); locations.clear();
        pdfExtractor.extractLocated(pdf, (p, l, t) -> locations.add(l), 100);
        assertEquals(List.of(1, 2), locations.stream().map(Location::page).toList()); assertTrue(locations.stream().allMatch(l -> l.type().equals("ocr")));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var chunks = new ArrayList<SearchIndex.Chunk>();
            pdfExtractor.extractLocated(pdf, (p, l, t) -> chunks.add(new SearchIndex.Chunk(p, (p - 1) * 5, t, l)), 100);
            index.replace(new Source("s", "S", temp, List.of(), List.of()), "scan.pdf", 1, chunks); index.refresh();
            var found = index.search("스캔", Set.of("s"), 10); assertEquals(2, found.size()); assertEquals("ocr", found.getFirst().location().type());
        }
        // 텍스트가 있는 PDF는 OCR 공급자를 호출하지 않는다.
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700); content.showText("existing text"); content.endText();
            }
            doc.save(pdf.toFile());
        }
        int before = calls.get(); locations.clear();
        extractor.extractLocated(pdf, (p, l, t) -> { assertTrue(t.contains("existing text")); locations.add(l); }, 100);
        assertEquals(before, calls.get()); assertEquals("page", locations.getFirst().type());
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(9000, 9000))); doc.save(pdf.toFile());
        }
        assertThrows(java.io.IOException.class, () -> extractor.extract(pdf, (p, t) -> {}, 100));
    }
}

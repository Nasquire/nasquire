package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.layout.*;
import com.bluesounds.nasquire.core.ocr.OcrTextExtractor;
import com.bluesounds.nasquire.core.remote.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 2차 뒷부분: 표·그림 의심 쪽 판단(벡터 격자·열 정렬·큰 이미지·래스터 격자)과 의심 쪽만 Docling 변환·실패 유지·재시도 상한. */
class DoclingPagesTest {
    @TempDir Path temp;
    private HttpServer server;
    /** 받은 page_range 시작 쪽(쪽 범위가 없으면 0). */
    private final List<Integer> ranges = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/file", e -> {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            calls.incrementAndGet();
            var m = Pattern.compile("name=\"page_range\"\r\n\r\n(\\d+)").matcher(body);
            int page = m.find() ? Integer.parseInt(m.group(1)) : 0;
            ranges.add(page);
            if (status != 200) { e.sendResponseHeaders(status, -1); e.close(); return; }
            String md = "| docling | doclingpage" + page + " |\\n|---|---|\\n| " + "converted table cell text ".repeat(8) + "|\\n\\n<!-- image -->";
            byte[] bytes = ("{\"document\":{\"md_content\":\"" + md + "\"},\"status\":\"success\",\"errors\":[]}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private RemoteDoclingClient client() {
        return new RemoteDoclingClient(new RemotePool("docling", List.of(new RemoteServer("a", "http://127.0.0.1:" + server.getAddress().getPort(), "", 10))), 20, 8L << 20);
    }

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static void text(PDPageContentStream s, float x, float y, String value) throws IOException {
        s.beginText(); s.setFont(FONT, 12); s.newLineAtOffset(x, y); s.showText(value); s.endText();
    }
    /** 1 표 격자, 2 일반 글, 3 큰 그림, 4 선 없는 열 정렬 표, 5 쪽 전체 배경 이미지 위 글(스캔 검색 PDF처럼). */
    private Path layoutPdf() throws IOException {
        Path file = Files.createDirectories(temp.resolve("docs")).resolve("layout.pdf");
        try (var doc = new PDDocument()) {
            var grid = new PDPage(PDRectangle.LETTER); doc.addPage(grid);
            try (var s = new PDPageContentStream(doc, grid)) {
                text(s, 72, 740, "Quarterly table");
                for (int i = 0; i < 5; i++) { s.moveTo(72, 700 - i * 50); s.lineTo(472, 700 - i * 50); s.stroke(); }
                for (int i = 0; i < 4; i++) { s.moveTo(72 + i * 133.3f, 700); s.lineTo(72 + i * 133.3f, 500); s.stroke(); }
                for (int r = 0; r < 4; r++) for (int c = 0; c < 3; c++) text(s, 80 + c * 133.3f, 670 - r * 50, "cell" + r + c);
            }
            var prose = new PDPage(PDRectangle.LETTER); doc.addPage(prose);
            try (var s = new PDPageContentStream(doc, prose)) {
                for (int i = 0; i < 30; i++) text(s, 72, 720 - i * 16, "This is an ordinary paragraph line number " + i + " with plain prose words.");
                s.moveTo(72, 750); s.lineTo(540, 750); s.stroke();
            }
            var figure = new PDPage(PDRectangle.LETTER); doc.addPage(figure);
            var picture = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
            var image = LosslessFactory.createFromImage(doc, picture);
            try (var s = new PDPageContentStream(doc, figure)) {
                text(s, 72, 740, "Figure 1 shows the chart");
                s.drawImage(image, 66, 200, 480, 480);
            }
            var columns = new PDPage(PDRectangle.LETTER); doc.addPage(columns);
            try (var s = new PDPageContentStream(doc, columns)) {
                for (int r = 0; r < 6; r++) { text(s, 72, 700 - r * 20, "Item " + r); text(s, 250, 700 - r * 20, "Amount " + r * 10); text(s, 430, 700 - r * 20, "Note" + r); }
            }
            var background = new PDPage(PDRectangle.LETTER); doc.addPage(background);
            try (var s = new PDPageContentStream(doc, background)) {
                s.drawImage(image, 0, 0, 612, 792);
                text(s, 72, 700, "Searchable scanned text layer");
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test void textLayerPagesAreJudgedByGridColumnsAndImages() throws Exception {
        Path pdf = layoutPdf();
        var check = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, false);
        var texts = new ArrayList<String>();
        new PdfTextExtractor().extractLocated(pdf, pdf, (p, l, t) -> texts.add(t), 100_000, check);
        assertEquals(5, texts.size()); assertEquals(5, check.inspected());
        var suspects = check.suspects();
        assertEquals(Set.of(1, 3, 4), suspects.keySet(), suspects.toString());
        assertTrue(suspects.get(1).startsWith("grid h5 v4"), suspects.get(1));
        assertTrue(suspects.get(3).startsWith("image "), suspects.get(3));
        assertTrue(suspects.get(4).startsWith("columns 3"), suspects.get(4));
        // 기준값을 높이면 의심하지 않는다. 이미지 0%는 끔.
        var strict = new LayoutCheck(new LayoutCheck.Thresholds(6, 4, 4, 0, 10), false);
        new PdfTextExtractor().extractLocated(pdf, pdf, (p, l, t) -> { }, 100_000, strict);
        assertTrue(strict.suspects().isEmpty(), strict.suspects().toString());
        // 항상 Docling: 신호 없이 모든 쪽.
        var always = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, true);
        new PdfTextExtractor().extractLocated(pdf, pdf, (p, l, t) -> { }, 100_000, always);
        assertEquals(Set.of(1, 2, 3, 4, 5), always.suspects().keySet());
        assertTrue(always.suspects().values().stream().allMatch("always"::equals));
        // 판단 함수: 2단 편집(열 2개)·머리말 선 하나는 의심하지 않는다.
        assertNull(LayoutCheck.decide(new PageSignals(1, 0, 2, 40, 0, true), LayoutCheck.Thresholds.DEFAULT));
        assertNull(LayoutCheck.decide(new PageSignals(0, 0, 0, 0, 95, true), LayoutCheck.Thresholds.DEFAULT), "쪽 전체 이미지는 배경");
        assertNull(LayoutCheck.decide(new PageSignals(0, 0, 0, 0, 50, false), LayoutCheck.Thresholds.DEFAULT), "글자 없는 쪽은 래스터로 판단");
    }

    private static BufferedImage raster(boolean grid) {
        var image = new BufferedImage(1200, 1600, BufferedImage.TYPE_BYTE_GRAY);
        var g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1200, 1600); g.setColor(Color.BLACK);
        if (grid) {
            for (int i = 0; i < 5; i++) g.fillRect(100, 300 + i * 150, 1000, 3);
            for (int i = 0; i < 4; i++) g.fillRect(100 + i * 333, 300, 3, 603);
        }
        // 글자 획처럼 짧은 선과 밑줄 하나는 격자가 아니다.
        var random = new Random(7);
        for (int i = 0; i < 400; i++) g.fillRect(100 + random.nextInt(1000), 1000 + random.nextInt(500), 2 + random.nextInt(6), 2 + random.nextInt(20));
        g.fillRect(100, 1550, 600, 2);
        g.dispose();
        return image;
    }

    @Test void scannedRastersAreJudgedByRuledLinesOnly() throws Exception {
        int[] lines = RasterGrid.lines(raster(true));
        assertEquals(6, lines[0], "격자 가로선 5 + 밑줄 1"); assertEquals(4, lines[1]);
        int[] plain = RasterGrid.lines(raster(false));
        assertTrue(plain[0] <= 1 && plain[1] == 0, Arrays.toString(plain));
        // 실제 OCR 경로: 격리 자식이 렌더링한 래스터에서 직선 수를 재며 단어 위치는 쓰지 않는다.
        Path pdf = Files.createDirectories(temp.resolve("scan")).resolve("scan.pdf");
        try (var doc = new PDDocument()) {
            for (boolean grid : new boolean[] {true, false}) {
                var page = new PDPage(PDRectangle.LETTER); doc.addPage(page);
                try (var s = new PDPageContentStream(doc, page)) { s.drawImage(LosslessFactory.createFromImage(doc, raster(grid)), 0, 0, 612, 792); }
            }
            doc.save(pdf.toFile());
        }
        var ocr = new OcrTextExtractor((png, language) -> "인식 텍스트", 5, temp);
        var check = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, false);
        var locations = new ArrayList<Location>();
        ocr.extractLocated(pdf, pdf, (p, l, t) -> locations.add(l), 10_000, check);
        assertEquals(List.of("ocr", "ocr"), locations.stream().map(Location::type).toList());
        assertEquals(Set.of(1), check.suspects().keySet(), check.suspects().toString());
        assertTrue(check.suspects().get(1).startsWith("raster h"), check.suspects().get(1));
        // 단일 이미지 파일도 같은 방식이다. 판단 객체가 없으면(미연결) 격자를 재지 않는다.
        Path png = temp.resolve("scan").resolve("table.png");
        javax.imageio.ImageIO.write(raster(true), "png", png.toFile());
        var imageCheck = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, false);
        ocr.extractLocated(png, png, (p, l, t) -> { }, 10_000, imageCheck);
        assertEquals(Set.of(1), imageCheck.suspects().keySet());
        ocr.extractLocated(png, png, (p, l, t) -> { }, 10_000, null);
    }

    private record Run(IndexingService service, SearchIndex index, Source source) implements AutoCloseable {
        public void close() throws Exception { service.close(); index.close(); }
    }
    private Run run(DoclingPages pages, boolean always, TextExtractor extractor) throws Exception {
        var index = new SearchIndex(temp.resolve("index-" + System.nanoTime()));
        var service = new IndexingService(index, 8L << 20, 200_000, 0, List.of(extractor));
        service.docling(pages);
        return new Run(service, index, new Source("s", "docs", temp.resolve("docs"), List.of(), List.of(), "directory", always));
    }
    private static Map<Integer, String> types(SearchIndex index) throws IOException {
        var result = new TreeMap<Integer, String>();
        for (var hit : index.document("s:layout.pdf", Set.of("s"))) result.merge(hit.page(), hit.location().type(), (a, b) -> a.equals(b) ? a : a + "+" + b);
        return result;
    }

    @Test void onlySuspectPagesGoToDoclingAndReplaceFirstPass() throws Exception {
        layoutPdf();
        var pages = new DoclingPages(() -> Optional.of(client()), () -> LayoutCheck.Thresholds.DEFAULT, () -> 3, 0);
        try (var run = run(pages, false, new PdfTextExtractor())) {
            run.service().scan(List.of(run.source()));
            assertEquals(List.of(1, 3, 4), ranges, "의심 쪽만 한 쪽씩 page_range로 보냄");
            assertEquals(Map.of(1, "docling", 2, "page", 3, "docling", 4, "docling", 5, "page"), types(run.index()));
            var hits = run.index().document("s:layout.pdf", Set.of("s"));
            assertTrue(hits.stream().anyMatch(h -> h.page() == 1 && h.text().contains("doclingpage1")));
            assertTrue(hits.stream().noneMatch(h -> h.text().contains("<!-- image -->")), "그림 자리표시 제거");
            assertTrue(hits.stream().anyMatch(h -> h.page() == 2 && h.text().contains("ordinary paragraph")), "일반 쪽은 1차 결과");
            var stats = pages.stats();
            assertEquals(3, stats.converted()); assertEquals(0, stats.retryWaiting());
            assertEquals("converted", stats.recent().getLast().result()); assertEquals("layout.pdf", stats.recent().getLast().path());
        }
    }

    @Test void failureKeepsFirstPassAndRetriesUpToLimit() throws Exception {
        layoutPdf();
        status = 503;
        var pages = new DoclingPages(() -> Optional.of(client()), () -> LayoutCheck.Thresholds.DEFAULT, () -> 2, 0);
        try (var run = run(pages, false, new PdfTextExtractor())) {
            run.service().scan(List.of(run.source()));
            assertEquals(List.of(1), ranges, "혼잡이면 같은 파일의 남은 쪽은 보내지 않음");
            assertEquals(Map.of(1, "page", 2, "page", 3, "page", 4, "page", 5, "page"), types(run.index()), "1차 결과로 색인 계속");
            assertTrue(run.index().document("s:layout.pdf", Set.of("s")).stream().anyMatch(h -> h.text().contains("cell00")));
            assertEquals(1, pages.stats().retryWaiting());
            assertEquals("ready", run.service().status("s").state(), "Docling 실패는 추출 오류가 아님");
            // 재시도 1·2회(상한 2) 뒤에는 대기를 지우고 더 보내지 않는다. 서킷이 열려 요청이 없더라도 시도로 센다.
            run.service().scan(List.of(run.source()));
            assertEquals(1, pages.stats().retryWaiting());
            run.service().scan(List.of(run.source()));
            assertEquals(0, pages.stats().retryWaiting(), "재시도 상한");
            int sent = calls.get();
            run.service().scan(List.of(run.source()));
            assertEquals(sent, calls.get(), "상한 뒤에는 다시 보내지 않음");
            assertTrue(pages.stats().failed() >= 3);
        }
        // 다시 정상이 되면 다음 재시도에서 반영한다.
        status = 200; ranges.clear();
        var recovering = new DoclingPages(() -> Optional.of(client()), () -> LayoutCheck.Thresholds.DEFAULT, () -> 3, 0);
        try (var run = run(recovering, false, new PdfTextExtractor())) {
            status = 429;
            run.service().scan(List.of(run.source()));
            assertEquals(1, recovering.stats().retryWaiting());
            status = 200; ranges.clear();
            run.service().scan(List.of(run.source()));
            assertEquals(List.of(1, 3, 4), ranges);
            assertEquals("docling", types(run.index()).get(1));
            assertEquals(0, recovering.stats().retryWaiting());
            int sent = calls.get();
            run.service().scan(List.of(run.source()));
            assertEquals(sent, calls.get(), "반영 뒤 변경 없는 검사는 보내지 않음");
        }
    }

    @Test void disconnectedDoclingRunsNoJudgmentAndAlwaysOptionSendsEveryPage() throws Exception {
        layoutPdf();
        var checks = new ArrayList<Object>();
        var recording = new TextExtractor() {
            public boolean supports(Path path) { return path.toString().endsWith(".pdf"); }
            public void extract(Path path, PageConsumer consumer, int max) throws IOException { new PdfTextExtractor().extract(path, consumer, max); }
            @Override public void extractLocated(Path name, Path content, LocatedConsumer consumer, int max, LayoutCheck check) throws IOException {
                checks.add(check == null ? "none" : check);
                new PdfTextExtractor().extractLocated(name, content, consumer, max, check);
            }
        };
        var off = new DoclingPages(Optional::empty, () -> LayoutCheck.Thresholds.DEFAULT, () -> 3, 0);
        try (var run = run(off, true, recording)) {
            run.service().scan(List.of(run.source()));
            assertEquals(List.of("none"), checks, "미연결이면 판단 객체 없음(판단 코드 미실행)");
        }
        checks.clear();
        try (var run = run(null, true, recording)) {
            run.service().scan(List.of(run.source()));
            assertEquals(List.of("none"), checks);
        }
        assertEquals(0, calls.get());
        assertNull(off.check(Path.of("a.pdf"), false));
        // HWP/HWPX·Office·텍스트는 Docling 대상이 아니다.
        for (String name : List.of("a.hwp", "a.hwpx", "a.docx", "a.xlsx", "a.pptx", "a.txt", "a.md", "a.gif")) assertFalse(DoclingPages.eligible(Path.of(name)), name);
        assertTrue(DoclingPages.eligible(Path.of("A.PDF"))); assertTrue(DoclingPages.eligible(Path.of("scan.tiff")));
        // 소스별 항상 Docling: 판단 없이 모든 쪽(파일당 쪽 상한 안).
        var pages = new DoclingPages(() -> Optional.of(client()), () -> new LayoutCheck.Thresholds(3, 3, 4, 40, 4), () -> 3, 0);
        try (var run = run(pages, true, new PdfTextExtractor())) {
            run.service().scan(List.of(run.source()));
            assertEquals(List.of(1, 2, 3, 4), ranges, "쪽 상한 4");
            assertEquals("page", types(run.index()).get(5));
            assertEquals(1, pages.stats().skipped());
            // 옵션을 끄면 범위는 같으므로 기존 결과를 지우지 않고 재색인한다.
            ranges.clear();
            var normal = new Source("s", "docs", temp.resolve("docs"), List.of(), List.of(), "directory", false);
            assertTrue(normal.sameScope(run.source()));
            run.service().scan(List.of(normal));
            assertEquals(List.of(1, 3, 4), ranges);
        }
    }

    @Test void supplementWhenDoclingTextIsMuchShorter() throws Exception {
        var check = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, true);
        check.always(1);
        Path pdf = layoutPdf();
        var pages = new DoclingPages(() -> Optional.of(client()), () -> LayoutCheck.Thresholds.DEFAULT, () -> 3, 0);
        String longText = "first pass text ".repeat(100);
        var outcome = pages.refine("layout.pdf", pdf, pdf, check, List.of(new DoclingPages.Part(1, Location.page(1), longText)), 100_000);
        assertEquals(List.of("page", "docling"), outcome.parts().stream().map(p -> p.location().type()).toList(), "짧은 Docling 결과는 보강");
        assertEquals(longText, outcome.parts().getFirst().text());
        assertEquals("supplemented", pages.stats().recent().getLast().result());
        // 문자 상한을 넘기면 1차 결과 유지.
        var kept = pages.refine("layout.pdf", pdf, pdf, check, List.of(new DoclingPages.Part(1, Location.page(1), "x")), 100);
        assertEquals(List.of("page"), kept.parts().stream().map(p -> p.location().type()).toList());
        assertEquals("| a |", DoclingPages.clean("\n<!-- image -->\n\n\n\n| a |\n"));
    }
}

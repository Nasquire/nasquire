package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.OfficeSamples;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 3차 뒷부분: ML 서버 공통 문서 처리 파이프라인(/v1/extract·PUT /process·/v1/ocr 호환)과 layout=auto|off|force. */
class MlPipelineTest {
    static final String TOKEN = MlGatewayTest.TOKEN;
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<AutoCloseable> open = new ArrayList<>();
    @AfterEach void closeAll() throws Exception { for (var c : open.reversed()) c.close(); }

    /** 가짜 원격 OCR(/v1/ocr)·docling-serve(/version·/v1/convert/file). 받은 page_range 시작 쪽을 기록한다. */
    static final class Fake implements AutoCloseable {
        final HttpServer http;
        final List<Integer> ranges = new CopyOnWriteArrayList<>();
        volatile int doclingStatus = 200;
        /** 설정하면 OCR 요청이 이 신호를 기다린다(포화 시험). */
        volatile CountDownLatch ocrGate;
        final CountDownLatch ocrEntered = new CountDownLatch(1);
        Fake() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(Executors.newFixedThreadPool(4));
            http.createContext("/", this::handle); http.start();
        }
        String root() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        private void handle(HttpExchange ex) throws IOException {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                var out = new JsonObject(); int status = 200;
                switch (path) {
                    case "/v1/ocr" -> {
                        var gate = ocrGate;
                        if (gate != null) { ocrEntered.countDown(); try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                        out.addProperty("text", "가짜 인식 결과");
                    }
                    case "/version" -> out.addProperty("docling-serve", "1.38.2");
                    case "/v1/convert/file" -> {
                        var m = Pattern.compile("name=\"page_range\"\r\n\r\n(\\d+)").matcher(body);
                        int page = m.find() ? Integer.parseInt(m.group(1)) : 0;
                        if (page > 0) ranges.add(page);
                        if (page > 0 && doclingStatus != 200) status = doclingStatus;
                        else {
                            var document = new JsonObject();
                            document.addProperty("md_content", "| docling | doclingpage" + page + " |\n|---|---|\n| " + "converted table cell text ".repeat(8) + "|\n\n<!-- image -->");
                            out.add("document", document); out.addProperty("status", "success"); out.add("errors", new JsonArray());
                        }
                    }
                    default -> status = 404;
                }
                byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes);
            }
        }
        public void close() { http.stop(0); ((ExecutorService)http.getExecutor()).shutdownNow(); }
    }

    private Fake fake() throws IOException { var f = new Fake(); open.add(f); return f; }
    private MlServer start(Fake fake, boolean docling) throws Exception {
        var env = new HashMap<String, String>();
        env.put("NASQUIRE_ML_TOKEN", TOKEN); env.put("NASQUIRE_DATA", temp.resolve("data").toString());
        env.put("SERVER_PORT", "0"); env.put("SERVER_ADDRESS", "127.0.0.1");
        env.put("NASQUIRE_ML_OCR", "remote"); env.put("NASQUIRE_ML_OCR_SERVERS", "o"); env.put("NASQUIRE_ML_OCR_SERVER_O_URL", fake.root() + "/v1");
        if (docling) {
            env.put("NASQUIRE_ML_DOCLING", "remote"); env.put("NASQUIRE_ML_DOCLING_SERVERS", "d"); env.put("NASQUIRE_ML_DOCLING_SERVER_D_URL", fake.root());
        }
        var server = MlServer.start(MlConfig.load(new String[0], env)); open.add(server); return server;
    }
    private HttpResponse<String> call(MlServer server, String method, String path, String auth, String type, String filename, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) request.header("Authorization", auth);
        if (type != null) request.header("Content-Type", type);
        if (filename != null) request.header("X-Filename", filename);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> extract(MlServer server, String query, String type, String filename, byte[] body) throws Exception {
        return call(server, "POST", "/v1/extract" + (query == null ? "" : "?" + query), "Bearer " + TOKEN, type, filename, body);
    }
    private static JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static List<String> sources(JsonObject result) {
        return result.getAsJsonArray("pages").asList().stream().map(p -> p.getAsJsonObject().get("source").getAsString()).toList();
    }
    private static String text(JsonObject result, int index) {
        return result.getAsJsonArray("pages").get(index).getAsJsonObject().get("text").getAsString();
    }

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static void text(PDPageContentStream s, float x, float y, String value) throws IOException {
        s.beginText(); s.setFont(FONT, 12); s.newLineAtOffset(x, y); s.showText(value); s.endText();
    }
    /** 1 글자 층 일반 글, 2 글자 층 표 격자, 3 이미지만 있는 쪽(스캔). */
    private byte[] pdf() throws IOException {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var prose = new PDPage(PDRectangle.LETTER); doc.addPage(prose);
            try (var s = new PDPageContentStream(doc, prose)) {
                for (int i = 0; i < 20; i++) text(s, 72, 720 - i * 16, "Ordinary paragraph line " + i + " with plain prose words.");
            }
            var grid = new PDPage(PDRectangle.LETTER); doc.addPage(grid);
            try (var s = new PDPageContentStream(doc, grid)) {
                text(s, 72, 740, "Quarterly table");
                for (int i = 0; i < 5; i++) { s.moveTo(72, 700 - i * 50); s.lineTo(472, 700 - i * 50); s.stroke(); }
                for (int i = 0; i < 4; i++) { s.moveTo(72 + i * 133.3f, 700); s.lineTo(72 + i * 133.3f, 500); s.stroke(); }
                for (int r = 0; r < 4; r++) for (int c = 0; c < 3; c++) text(s, 80 + c * 133.3f, 670 - r * 50, "cell" + r + c);
            }
            var scan = new PDPage(PDRectangle.LETTER); doc.addPage(scan);
            var picture = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
            var g = picture.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 300, 400); g.setColor(Color.BLACK); g.fillRect(40, 60, 120, 8); g.dispose();
            try (var s = new PDPageContentStream(doc, scan)) { s.drawImage(LosslessFactory.createFromImage(doc, picture), 0, 0, 612, 792); }
            doc.save(out);
            return out.toByteArray();
        }
    }
    private static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test void everyFormatTakesItsOwnPathAndOcrStaysCompatible() throws Exception {
        var fake = fake();
        var server = start(fake, false);
        // 생성 PDF: 글자 층 → pdfbox, 이미지만 있는 쪽 → OCR(격리 자식 렌더링). Docling 미연결이라 off.
        var pdf = json(extract(server, null, "application/pdf", "%EB%AC%B8%EC%84%9C.pdf", pdf()));
        assertEquals(List.of("pdfbox", "pdfbox", "ocr"), sources(pdf));
        assertTrue(text(pdf, 0).contains("Ordinary paragraph line 0")); assertTrue(text(pdf, 1).contains("cell00"));
        assertEquals("가짜 인식 결과", text(pdf, 2));
        var metadata = pdf.getAsJsonObject("metadata");
        assertEquals("pdf", metadata.get("type").getAsString()); assertEquals(3, metadata.get("pages").getAsInt());
        assertEquals("off", metadata.get("docling").getAsString()); assertEquals("auto", metadata.get("layout").getAsString());
        assertEquals("문서.pdf", metadata.get("filename").getAsString(), "X-Filename 퍼센트 인코딩 한글");
        assertEquals("o", extract(server, null, "application/pdf", null, pdf()).headers().firstValue("X-Nasquire-Ocr-Server").orElse(null));
        // 파일 이름이 없으면 Content-Type으로 형식을 정한다.
        var image = json(extract(server, null, "image/png", null, png()));
        assertEquals(List.of("ocr"), sources(image)); assertEquals("png", image.getAsJsonObject("metadata").get("type").getAsString());
        // Office·HWP·텍스트는 기존 추출기(원본 표 구조).
        Path samples = temp.resolve("samples"); OfficeSamples.generate(samples);
        var docx = json(extract(server, null, "application/octet-stream", "sample.docx", Files.readAllBytes(samples.resolve("sample.docx"))));
        assertEquals(List.of("extractor"), sources(docx)); assertTrue(text(docx, 0).contains("formatdocx"), text(docx, 0));
        var hwpx = json(extract(server, null, "application/hwp+zip", null, Files.readAllBytes(samples.resolve("sample.hwpx"))));
        assertEquals(List.of("extractor"), sources(hwpx)); assertTrue(text(hwpx, 0).contains("formathwpx"), text(hwpx, 0));
        var hwp = json(extract(server, null, null, "sample.hwp", Files.readAllBytes(samples.resolve("sample.hwp"))));
        assertTrue(text(hwp, 0).contains("formathwp"));
        var pptx = json(extract(server, "layout=off", null, "sample.pptx", Files.readAllBytes(samples.resolve("sample.pptx"))));
        assertEquals(2, pptx.getAsJsonArray("pages").size(), "슬라이드별"); assertTrue(text(pptx, 1).contains("formatpptx"));
        var txt = json(extract(server, null, "text/plain; charset=utf-8", null, "한글 텍스트 본문".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("extractor"), sources(txt)); assertEquals("한글 텍스트 본문", text(txt, 0));
        // 거절: Docling 미연결 force, 잘못된 layout·쿼리, 미지원 형식, 빈 본문, 손상 문서, 잘못된 인코딩 이름.
        assertEquals(400, extract(server, "layout=force", "application/pdf", null, pdf()).statusCode());
        assertEquals(400, extract(server, "layout=always", "application/pdf", null, pdf()).statusCode());
        assertEquals(400, extract(server, "layout=auto&x=1", "application/pdf", null, pdf()).statusCode());
        assertEquals(415, extract(server, null, "application/zip", "a.zip", new byte[] {1, 2}).statusCode());
        assertEquals(400, extract(server, null, "application/pdf", null, new byte[0]).statusCode());
        assertEquals(400, extract(server, null, "application/pdf", null, "not a pdf".getBytes()).statusCode());
        assertEquals(400, extract(server, null, "application/pdf", "%E0%A4.pdf", pdf()).statusCode());
        assertEquals(400, call(server, "POST", "/v1/ocr?layout=off", "Bearer " + TOKEN, "image/png", null, png()).statusCode(), "다른 경로는 쿼리 불가");
        assertEquals(405, call(server, "GET", "/v1/extract", "Bearer " + TOKEN, null, null, null).statusCode());
        // 기존 /v1/ocr(이미지 → {"text"}) 호환.
        var ocr = call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", null, png());
        assertEquals(200, ocr.statusCode(), ocr.body());
        assertEquals(Set.of("text"), JsonParser.parseString(ocr.body()).getAsJsonObject().keySet());
        assertTrue(ocr.body().contains("가짜 인식 결과"));
        assertEquals(400, call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", null, "not an image".getBytes()).statusCode());
        assertTrue(fake.ranges.isEmpty(), "Docling 미연결");
    }

    @Test void layoutAutoOffForceAndDoclingFailureKeepsFirstPass() throws Exception {
        var fake = fake();
        var server = start(fake, true);
        // auto: 표 격자 쪽(2)만 Docling. 일반 글·스캔 쪽은 보내지 않는다.
        var auto = extract(server, "layout=auto", "application/pdf", "report.pdf", pdf());
        var result = json(auto);
        assertEquals(List.of("pdfbox", "docling", "ocr"), sources(result));
        assertTrue(text(result, 1).contains("doclingpage2") && !text(result, 1).contains("<!-- image -->"), text(result, 1));
        assertEquals("converted", result.getAsJsonObject("metadata").get("docling").getAsString());
        assertEquals("converted", result.getAsJsonObject("metadata").getAsJsonObject("doclingPages").get("2").getAsString());
        assertEquals(List.of(2), fake.ranges);
        assertEquals("d", auto.headers().firstValue("X-Nasquire-Docling-Server").orElse(null));
        assertFalse(auto.body().contains(fake.root()) || auto.body().contains("127.0.0.1"));
        // 쿼리가 없으면 auto.
        assertEquals(List.of("pdfbox", "docling", "ocr"), sources(json(extract(server, null, "application/pdf", null, pdf()))));
        fake.ranges.clear();
        // off: Docling을 쓰지 않는다.
        var off = json(extract(server, "layout=off", "application/pdf", null, pdf()));
        assertEquals(List.of("pdfbox", "pdfbox", "ocr"), sources(off));
        assertEquals("off", off.getAsJsonObject("metadata").get("docling").getAsString());
        assertTrue(fake.ranges.isEmpty());
        // force: 모든 PDF 쪽.
        var force = json(extract(server, "layout=force", "application/pdf", null, pdf()));
        assertEquals(List.of("docling", "docling", "docling"), sources(force));
        assertEquals(List.of(1, 2, 3), fake.ranges);
        assertEquals("force", force.getAsJsonObject("metadata").get("layout").getAsString());
        // Office는 force여도 기존 추출기(원본 표 구조), Docling 미사용.
        Path samples = temp.resolve("samples"); OfficeSamples.generate(samples);
        fake.ranges.clear();
        var docx = json(extract(server, "layout=force", null, "sample.docx", Files.readAllBytes(samples.resolve("sample.docx"))));
        assertEquals(List.of("extractor"), sources(docx)); assertEquals("off", docx.getAsJsonObject("metadata").get("docling").getAsString());
        assertTrue(fake.ranges.isEmpty());
        // Docling 혼잡·실패: 요청 단위 재시도 없이 1차 결과로 응답하고 메타데이터에 표시한다.
        fake.doclingStatus = 503;
        var failed = json(extract(server, "layout=auto", "application/pdf", null, pdf()));
        assertEquals(List.of("pdfbox", "pdfbox", "ocr"), sources(failed));
        assertTrue(text(failed, 1).contains("cell00"));
        assertEquals("unavailable", failed.getAsJsonObject("metadata").get("docling").getAsString());
        assertEquals("retry", failed.getAsJsonObject("metadata").getAsJsonObject("doclingPages").get("2").getAsString());
        fake.doclingStatus = 400;
        fake.ranges.clear();
        var rejected = json(extract(server, "layout=force", "application/pdf", null, pdf()));
        assertEquals(List.of("pdfbox", "pdfbox", "ocr"), sources(rejected));
        assertEquals("failed", rejected.getAsJsonObject("metadata").get("docling").getAsString());
        assertEquals(1, fake.ranges.size(), "실패한 서버에 남은 쪽을 계속 보내지 않는다");
        // 상태에 Docling 쪽 누적이 보인다.
        var status = JsonParser.parseString(call(server, "GET", "/v1/status", "Bearer " + TOKEN, null, null, null).body()).getAsJsonObject();
        var pages = status.getAsJsonObject("features").getAsJsonObject("docling").getAsJsonObject("pages");
        assertTrue(pages.get("converted").getAsLong() >= 5 && pages.get("failed").getAsLong() >= 2, pages.toString());
    }

    @Test void openWebUiExternalProcessFormatAndAuthentication() throws Exception {
        var fake = fake();
        var server = start(fake, true);
        // Open WebUI는 urllib.parse.quote로 이름을 인코딩한다(공백 %20, '+' %2B).
        String name = "보고서 2026+최종.pdf";
        String quoted = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        var response = call(server, "PUT", "/process", "Bearer " + TOKEN, "application/pdf", quoted, pdf());
        assertEquals(200, response.statusCode(), response.body());
        var list = JsonParser.parseString(response.body()).getAsJsonArray();
        assertEquals(3, list.size());
        for (int i = 0; i < 3; i++) {
            var item = list.get(i).getAsJsonObject();
            assertEquals(Set.of("page_content", "metadata"), item.keySet());
            var m = item.getAsJsonObject("metadata");
            assertEquals(name, m.get("source").getAsString());
            assertEquals(i, m.get("page").getAsInt(), "Open WebUI 관례: 0부터");
            assertEquals(i + 1, m.get("page_number").getAsInt());
            assertEquals(3, m.get("total_pages").getAsInt());
            for (var e : m.entrySet()) assertTrue(e.getValue().isJsonPrimitive(), "벡터 DB 메타데이터는 단순 값만: " + e.getKey());
        }
        assertEquals(List.of("pdfbox", "docling", "ocr"), list.asList().stream().map(x -> x.getAsJsonObject().getAsJsonObject("metadata").get("extraction").getAsString()).toList());
        assertTrue(list.get(1).getAsJsonObject().get("page_content").getAsString().contains("doclingpage2"), "layout=auto");
        // 쪽 없는 형식(DOCX)은 page 없이 하나.
        Path samples = temp.resolve("samples"); OfficeSamples.generate(samples);
        var docx = call(server, "PUT", "/process", "Bearer " + TOKEN, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "%ED%9A%8C%EC%9D%98%EB%A1%9D.docx", Files.readAllBytes(samples.resolve("sample.docx")));
        var docs = JsonParser.parseString(docx.body()).getAsJsonArray();
        assertEquals(1, docs.size());
        var m = docs.get(0).getAsJsonObject().getAsJsonObject("metadata");
        assertEquals("회의록.docx", m.get("source").getAsString()); assertFalse(m.has("page")); assertEquals("extractor", m.get("extraction").getAsString());
        // 경로 부분은 버린다.
        var path = call(server, "PUT", "/process", "Bearer " + TOKEN, "text/plain", "..%2F..%2Fetc%2Fnotes.txt", "본문".getBytes(StandardCharsets.UTF_8));
        assertEquals("notes.txt", JsonParser.parseString(path.body()).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("metadata").get("source").getAsString());
        // 인증: 토큰 없음·다른 토큰·Basic은 401. 방법·쿼리 제한.
        for (String auth : Arrays.asList(null, "Bearer wrong-token-value-0123456789abcdefghij", "Basic " + TOKEN)) {
            var denied = call(server, "PUT", "/process", auth, "application/pdf", "a.pdf", pdf());
            assertEquals(401, denied.statusCode()); assertEquals("Bearer", denied.headers().firstValue("WWW-Authenticate").orElse(null));
        }
        assertEquals(405, call(server, "POST", "/process", "Bearer " + TOKEN, "application/pdf", "a.pdf", pdf()).statusCode());
        assertEquals(400, call(server, "PUT", "/process?layout=off", "Bearer " + TOKEN, "application/pdf", "a.pdf", pdf()).statusCode());
        assertEquals(400, call(server, "PUT", "/process", "Bearer " + TOKEN, "application/pdf", "%ZZ.pdf", pdf()).statusCode());
        assertEquals(415, call(server, "PUT", "/process", "Bearer " + TOKEN, "application/x-unknown", "a.bin", new byte[] {1}).statusCode());
    }

    @Test void sizeLimitsAndSaturationAreShared() throws Exception {
        var fake = fake();
        var server = start(fake, false);
        // 형식별 크기 상한(Office/HWP 8 MiB, 이미지 16 MiB)은 본문을 받기 전에 413.
        assertEquals(413, MlServerTest.declaredOnly(server.port(), TOKEN, "POST", "/v1/extract", null, "big.docx", MlPipeline.MAX_OFFICE + 1));
        assertEquals(413, MlServerTest.declaredOnly(server.port(), TOKEN, "PUT", "/process", "image/png", null, MlPipeline.MAX_IMAGE + 1));
        assertEquals(413, MlServerTest.declaredOnly(server.port(), TOKEN, "POST", "/v1/ocr", "image/png", null, MlServer.MAX_IMAGE + 1L));
        // 포화: OCR 중인 문서가 있으면 /v1/extract·/process·/v1/ocr 모두 기다리지 않고 503.
        fake.ocrGate = new CountDownLatch(1);
        var first = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/extract"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(png())).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(fake.ocrEntered.await(30, TimeUnit.SECONDS), "첫 요청이 OCR 중");
        for (var busy : List.of(extract(server, null, "text/plain", null, "x".getBytes()),
                call(server, "PUT", "/process", "Bearer " + TOKEN, "text/plain", "a.txt", "x".getBytes()),
                call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", null, png()))) {
            assertEquals(503, busy.statusCode(), busy.body()); assertEquals("1", busy.headers().firstValue("Retry-After").orElse(null));
        }
        fake.ocrGate.countDown();
        assertEquals(200, first.get(60, TimeUnit.SECONDS).statusCode());
        assertEquals(200, extract(server, null, "text/plain", null, "x".getBytes()).statusCode(), "슬롯 반환");
        // 요청 임시 파일은 처리 뒤 남지 않는다.
        try (var left = Files.list(temp.resolve("data/ml-tmp"))) { assertEquals(0, left.count()); }
    }

    @Test void filenameAndTypeParsing() {
        assertEquals("보고서 2026+최종.pdf", MlPipeline.filename("%EB%B3%B4%EA%B3%A0%EC%84%9C%202026%2B%EC%B5%9C%EC%A2%85.pdf"));
        assertEquals("a+b.pdf", MlPipeline.filename("a+b.pdf"), "'+'는 공백이 아니다");
        assertEquals("x.pdf", MlPipeline.filename("dir%5Cx.pdf"));
        assertEquals("a?b.txt", MlPipeline.filename("a%0Ab.txt"));
        assertNull(MlPipeline.filename(null)); assertNull(MlPipeline.filename(" "));
        assertThrows(MlPipeline.Rejected.class, () -> MlPipeline.filename("%"));
        assertThrows(MlPipeline.Rejected.class, () -> MlPipeline.filename("%C3%28.pdf"));
        assertThrows(MlPipeline.Rejected.class, () -> MlPipeline.filename("한글.pdf"), "인코딩 안 된 비ASCII");
        assertEquals("pdf", MlPipeline.type("x.PDF", "image/png"));
        assertEquals("hwpx", MlPipeline.type("upload", "application/hwp+zip"));
        assertEquals("docx", MlPipeline.type(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("md", MlPipeline.type("x.unknown", "text/markdown; charset=utf-8"));
        assertThrows(MlPipeline.Rejected.class, () -> MlPipeline.type("x.exe", "application/octet-stream"));
        assertEquals(MlPipeline.Layout.AUTO, MlServer.layout(null));
        assertEquals(MlPipeline.Layout.FORCE, MlServer.layout("layout=force"));
        assertThrows(MlPipeline.Rejected.class, () -> MlServer.layout("mode=off"));
    }
}

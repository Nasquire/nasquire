package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.Providers;
import com.bluesounds.nasquire.core.ocr.OcrTextExtractor;
import com.bluesounds.nasquire.core.ocr.SandboxFixtures;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 교차 검토 반영: 문서 전체 마감(504·격리 자식 종료·슬롯 반환·Docling 남은 시간), /v1/ocr 본문 전 슬롯, /v1/ocr 상한 413. */
class MlCrossReviewTest {
    static final String TOKEN = MlGatewayTest.TOKEN;
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();

    private MlServer server(Providers.OcrProvider provider, int documentSeconds, boolean hanging) throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        var ocr = new OcrTextExtractor(provider, 5, data);
        return new MlServer(new InetSocketAddress("127.0.0.1", 0), MlServer.sha256(TOKEN), null, null, null, null, 5, ocr, null, MlGateway.NONE, 4,
                data, 10, false, documentSeconds, hanging ? SandboxFixtures.hanging(data, ocr, 60_000) : null);
    }
    private HttpResponse<String> call(MlServer server, String path, String type, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static byte[] png(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "png", out); return out.toByteArray();
    }
    private static byte[] pdf() throws IOException {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var s = new PDPageContentStream(doc, page)) {
                s.beginText(); s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); s.newLineAtOffset(72, 700); s.showText("Plain text layer"); s.endText();
            }
            doc.save(out); return out.toByteArray();
        }
    }

    @Test void documentDeadlineKillsIsolatedChildAnswers504AndReturnsSlots() throws Exception {
        try (var server = server((image, language) -> "ocr", 2, true)) {
            var seen = ConcurrentHashMap.<ProcessHandle>newKeySet();
            var watching = new java.util.concurrent.atomic.AtomicBoolean(true);
            var watcher = new Thread(() -> { while (watching.get()) { ProcessHandle.current().descendants().forEach(seen::add); Thread.onSpinWait(); } });
            watcher.start();
            long start = System.nanoTime();
            HttpResponse<String> response;
            try { response = call(server, "/v1/extract", "application/pdf", pdf()); }
            finally { watching.set(false); watcher.join(); }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(504, response.statusCode(), response.body());
            assertTrue(millis >= 1_500 && millis < 12_000, "문서 마감(2초)에 끝남(샌드박스 자체 상한 60초가 아님): " + millis + "ms");
            assertTrue(seen.size() >= 2, "격리 자식과 손자를 관찰: " + seen.size());
            for (var process : seen) { process.onExit().get(5, TimeUnit.SECONDS); assertFalse(process.isAlive(), "자식 잔존 없음"); }
            assertEquals(0, ProcessHandle.current().descendants().count());
            assertEquals(1, SandboxFixtures.availableSlots(), "격리 자식 슬롯 반환");
            // Office도 같은 격리 자식·마감을 탄다.
            assertEquals(504, call(server, "/v1/extract", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {'P', 'K', 3, 4}).statusCode());
            // 문서 슬롯도 반환되어 다음 요청을 처리한다(텍스트는 자식 없이 처리).
            var next = call(server, "/v1/extract", "text/plain", "다음 요청".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, next.statusCode(), next.body());
            try (var left = Files.list(temp.resolve("data/ml-tmp"))) { assertEquals(0, left.count(), "요청 임시 파일 삭제"); }
            try (var left = Files.list(temp.resolve("data/ocr-tmp"))) { assertEquals(0, left.count(), "격리 작업 폴더 삭제"); }
        }
    }

    @Test void ocrIsRejectedBeforeReadingTheBodyAndReleasesTheSlot() throws Exception {
        var gate = new CountDownLatch(1); var entered = new CountDownLatch(1);
        Providers.OcrProvider provider = (image, language) -> {
            entered.countDown();
            try { gate.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "인식";
        };
        try (var server = server(provider, 60, false)) {
            var first = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/ocr")).header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png(20, 20))).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(30, TimeUnit.SECONDS), "첫 요청이 OCR 중");
            // 본문을 일부만 보내고 멈춘 요청: 본문을 기다리지 않고 503.
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5_000);
                var out = socket.getOutputStream();
                out.write(("POST /v1/ocr HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + TOKEN + "\r\nContent-Type: image/png\r\nContent-Length: 1000000\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(new byte[16]); out.flush();
                String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertEquals("HTTP/1.1 503 Service Unavailable", status);
            }
            gate.countDown();
            assertEquals(200, first.get(30, TimeUnit.SECONDS).statusCode());
            // 본문 수신 중 연결이 끊겨도 슬롯을 돌려준다.
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.getOutputStream().write(("POST /v1/ocr HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + TOKEN + "\r\nContent-Type: image/png\r\nContent-Length: 1000\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[10]); socket.getOutputStream().flush();
                Thread.sleep(300);
            }
            HttpResponse<String> after = null;
            for (int i = 0; i < 50; i++) {
                after = call(server, "/v1/ocr", "image/png", png(20, 20));
                if (after.statusCode() != 503) break;
                Thread.sleep(100);
            }
            assertEquals(200, after.statusCode(), "본문 수신 실패 뒤 슬롯 반환: " + after.body());
            // 항목 4: 격리 자식의 이미지 상한 위반은 413(상한 종류 보존), 그 밖의 입력 오류는 400.
            assertEquals(413, call(server, "/v1/ocr", "image/png", png(17_000, 4)).statusCode());
            assertEquals(400, call(server, "/v1/ocr", "image/png", "not an image".getBytes()).statusCode());
            assertEquals(413, call(server, "/v1/extract", "image/png", png(17_000, 4)).statusCode());
        }
    }

    @Test void doclingGetsOnlyTheRemainingDocumentTime() throws Exception {
        var timeouts = new CopyOnWriteArrayList<Integer>();
        var docling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        docling.createContext("/", e -> {
            try (e) {
                String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                var out = new JsonObject();
                if (e.getRequestURI().getPath().equals("/version")) out.addProperty("docling-serve", "1.38.2");
                else {
                    var timeout = Pattern.compile("name=\"document_timeout\"\r\n\r\n(\\d+)").matcher(body);
                    if (body.contains("name=\"page_range\"") && timeout.find()) timeouts.add(Integer.parseInt(timeout.group(1)));
                    var document = new JsonObject(); document.addProperty("md_content", "| a | b |\n|---|---|\n| converted table text here |");
                    out.add("document", document); out.addProperty("status", "success"); out.add("errors", new JsonArray());
                }
                byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes);
            }
        });
        docling.start();
        try {
            var env = new HashMap<String, String>();
            env.put("NASQUIRE_ML_TOKEN", TOKEN); env.put("NASQUIRE_DATA", temp.resolve("ml").toString());
            env.put("SERVER_PORT", "0"); env.put("SERVER_ADDRESS", "127.0.0.1");
            env.put("NASQUIRE_ML_DOCLING", "remote"); env.put("NASQUIRE_ML_DOCLING_SERVERS", "d");
            env.put("NASQUIRE_ML_DOCLING_SERVER_D_URL", "http://127.0.0.1:" + docling.getAddress().getPort());
            assertEquals(MlConfig.DEFAULT_DOCUMENT_TIMEOUT_SECONDS, MlConfig.load(new String[0], env).documentTimeoutSeconds());
            env.put("NASQUIRE_ML_DOCUMENT_TIMEOUT_SECONDS", "601");
            assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], env), "최대 600초");
            env.put("NASQUIRE_ML_DOCUMENT_TIMEOUT_SECONDS", "30");
            try (var server = MlServer.start(MlConfig.load(new String[0], env))) {
                var response = call(server, "/v1/extract?layout=force", "application/pdf", pdf());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(1, timeouts.size());
                // Docling 서버당 기본 300초가 아니라 문서 마감(30초)까지 남은 시간만 넘긴다.
                assertTrue(timeouts.getFirst() <= 30 && timeouts.getFirst() >= 20, "남은 시간: " + timeouts);
            }
        } finally { docling.stop(0); }
    }
}

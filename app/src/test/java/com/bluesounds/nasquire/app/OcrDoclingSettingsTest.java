package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 2차: OCR·Docling 일반 설정(lite 포함), 이전 full OCR 선택 이관, 키 암호화, 평문 http 규칙, 상태. */
class OcrDoclingSettingsTest {
    @TempDir Path temp;
    private HttpServer server;
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", e -> send(e, "{\"data\":[{\"id\":\"tesseract\"}]}"));
        server.createContext("/v1/ocr", e -> {
            e.getRequestBody().readAllBytes();
            headers.put("ocr", String.valueOf(e.getRequestHeaders().getFirst("Authorization")));
            send(e, "{\"text\":\"원격 인식\"}");
        });
        server.createContext("/version", e -> send(e, "{\"docling-serve\":\"1.38.2\"}"));
        server.createContext("/v1/convert/file", e -> {
            e.getRequestBody().readAllBytes();
            headers.put("docling", String.valueOf(e.getRequestHeaders().getFirst("X-Api-Key")));
            send(e, "{\"document\":{\"md_content\":\"# ok\"},\"status\":\"success\",\"errors\":[]}");
        });
        server.start();
    }
    private static void send(com.sun.net.httpserver.HttpExchange e, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    @AfterEach void stop() { server.stop(0); }
    private String root() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static Properties stored(Path file) throws Exception { var p = new Properties(); try (var in = Files.newInputStream(file)) { p.load(in); } return p; }
    private static boolean full() { return FullSettings.available(); }

    @Test void remoteOcrWorksInLiteWithEncryptedKeysAndHealth() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            try (var ocr = new OcrSettings(data, crypto, new FullSettings(data))) {
                assertEquals("off", ocr.view().get("ocr")); assertFalse(ocr.enabled());
                var input = new HashMap<String, Object>(Map.of("ocr", "remote", "maxPages", "5"));
                input.put("servers", List.of(Map.of("name", "ml-a", "baseUrl", root() + "/v1", "apiKey", "ocr-secret-value")));
                assertTrue(ocr.saveSettings(input), "공급자가 바뀌면 재색인");
                assertTrue(ocr.enabled());
                assertEquals("ok", ocr.test("ml-a").get("result"));
                assertEquals("Bearer ocr-secret-value", headers.get("ocr"));
                var view = ocr.view();
                assertEquals(true, view.get("hasKey")); assertFalse(view.toString().contains("ocr-secret-value"));
                var health = ocr.health();
                assertEquals("remote", health.get("mode")); assertFalse(health.toString().contains("127.0.0.1"));
                String file = Files.readString(data.resolve("ocr.properties"));
                assertFalse(file.contains("ocr-secret-value")); assertTrue(stored(data.resolve("ocr.properties")).getProperty("server.0.encryptedKey").startsWith("v2:"));
                // 서버 목록만 바뀌면 재색인하지 않는다.
                assertFalse(ocr.saveSettings(Map.of("servers", List.of(Map.of("name", "ml-a", "baseUrl", root() + "/v1"), Map.of("name", "ml-b", "baseUrl", root() + "/v1")))));
                assertTrue(stored(data.resolve("ocr.properties")).getProperty("server.0.encryptedKey").startsWith("v2:"), "같은 이름·주소는 키 유지");
                // 추출기는 이미지 형식을 원격 OCR로 처리한다(렌더링은 core 자식 프로세스).
                assertTrue(ocr.extractor().supports(Path.of("scan.png")));
                Path png = temp.resolve("scan.png");
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_BYTE_GRAY), "png", png.toFile());
                var texts = new ArrayList<String>();
                ocr.extractor().extractLocated(png, (p, l, t) -> { assertEquals("ocr", l.type()); texts.add(t); }, 1000);
                assertEquals(List.of("원격 인식"), texts);
            }
            // 평문 http(비루프백)는 명시 허용 없이 거부한다.
            try (var ocr = new OcrSettings(data, crypto, new FullSettings(data))) {
                assertEquals("remote", ocr.view().get("ocr")); assertEquals(true, ocr.view().get("hasKey"), "재시작 뒤 키 유지");
                assertThrows(IllegalArgumentException.class, () -> ocr.saveSettings(Map.of("servers", List.of(Map.of("name", "far", "baseUrl", "http://ocr.example.com/v1")))));
                assertThrows(IllegalArgumentException.class, () -> ocr.saveSettings(Map.of("ocr", "unknown")));
                ocr.clearKey(null); assertEquals(false, ocr.view().get("hasKey"));
            }
        }
    }

    @Test void legacyFullOcrSelectionMigratesAndTesseractNeedsFull() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Files.writeString(data.resolve("full.properties"), "ocr=tesseract\nocrMaxPages=9\nocrTimeoutSeconds=12\nocrMemoryMiB=256\n");
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            var full = new FullSettings(data);
            assertEquals("12", full.view().get("ocrTimeoutSeconds"));
            if (full()) {
                try (var ocr = new OcrSettings(data, crypto, full)) {
                    assertEquals("tesseract", ocr.view().get("ocr")); assertEquals("9", ocr.view().get("maxPages")); assertTrue(ocr.enabled());
                    assertTrue(ocr.tesseractChanged(), "Tesseract 제한 변경은 재색인");
                }
            } else {
                // lite에서는 이전 full 설정의 Tesseract를 대체 없이 시작 거부한다(설정 off 뒤 전환).
                assertThrows(IllegalStateException.class, () -> new OcrSettings(data, crypto, full));
                var props = stored(data.resolve("ocr.properties"));
                assertEquals("tesseract", props.getProperty("ocr")); assertEquals("9", props.getProperty("maxPages"));
                Files.writeString(data.resolve("ocr.properties"), "ocr=off\nmaxPages=9\n");
                try (var ocr = new OcrSettings(data, crypto, full)) {
                    assertEquals("off", ocr.view().get("ocr"));
                    assertThrows(IllegalStateException.class, () -> ocr.saveSettings(Map.of("ocr", "tesseract")), "lite는 off|remote");
                    assertEquals("off", ocr.view().get("ocr"));
                }
            }
        }
    }

    @Test void doclingSettingsUseApiKeyHeaderAndDoNothingWhenOff() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            try (var docling = new DoclingSettings(data, crypto)) {
                assertEquals("off", docling.view().get("docling")); assertTrue(docling.client().isEmpty(), "off면 클라이언트 없음");
                assertEquals("300", docling.view().get("timeoutSeconds")); assertEquals("32", docling.view().get("maxFileMiB"));
                assertTrue(headers.isEmpty(), "off에서는 원격 호출 없음");
                assertThrows(IllegalArgumentException.class, () -> docling.save(Map.of("maxFileMiB", "65")), "docling-serve 기본 64 MiB 초과 거부");
                var input = new HashMap<String, Object>(Map.of("docling", "remote"));
                input.put("servers", List.of(Map.of("name", "docling-a", "baseUrl", root(), "apiKey", "docling-secret-value")));
                docling.save(input);
                assertEquals("docling-secret-value", headers.get("docling"), "저장 시 연결 확인(X-Api-Key)");
                assertEquals("ok", docling.test("docling-a").get("result"));
                assertEquals("1.38.2", docling.test("docling-a").get("detail"));
                var client = docling.client().orElseThrow();
                assertEquals("# ok", client.convert("x".getBytes(), "a.pdf", RemoteDoclingClient.PageRange.single(2), false).markdown());
                assertFalse(docling.view().toString().contains("docling-secret-value"));
                assertFalse(docling.health().toString().contains("127.0.0.1"));
                assertFalse(Files.readString(data.resolve("docling.properties")).contains("docling-secret-value"));
                assertThrows(IllegalArgumentException.class, () -> docling.save(Map.of("servers", List.of(Map.of("name", "far", "baseUrl", "http://docling.example.com")))));
                docling.save(Map.of("allowInsecureHttp", "true", "servers", List.of(Map.of("name", "far", "baseUrl", "http://192.0.2.1", "timeoutSeconds", "1"))));
                assertEquals(true, docling.view().get("insecureHttp"), "명시 허용 시 경고 표시");
                docling.save(Map.of("docling", "off"));
                assertTrue(docling.client().isEmpty());
            }
        }
    }
}

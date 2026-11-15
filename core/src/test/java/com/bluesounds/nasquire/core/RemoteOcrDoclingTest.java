package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 2차: 원격 OCR(Nasquire ML /ocr)·원격 Docling(docling-serve /v1/convert/file) 클라이언트. 가짜 HTTP 서버로 확인한다. */
class RemoteOcrDoclingTest {
    private HttpServer server;
    /** 서버 이름별 응답 상태. 200 정상, -1 응답 지연. */
    private final Map<String, Integer> status = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    /** 마지막 요청의 헤더·본문(서버 이름별). */
    private final Map<String, String> contentType = new ConcurrentHashMap<>(), authorization = new ConcurrentHashMap<>(), apiKey = new ConcurrentHashMap<>();
    private final Map<String, byte[]> body = new ConcurrentHashMap<>();
    private volatile long delayMillis = 1500;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String[] parts = e.getRequestURI().getPath().split("/");
            String name = parts[1], op = parts[parts.length - 1];
            byte[] request = e.getRequestBody().readAllBytes();
            String response;
            if (op.equals("version")) response = "{\"docling-serve\":\"1.38.2\"}";
            else if (op.equals("models")) response = "{\"data\":[{\"id\":\"tesseract\"}]}";
            else {
                calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                body.put(name, request);
                Optional.ofNullable(e.getRequestHeaders().getFirst("Content-Type")).ifPresent(v -> contentType.put(name, v));
                Optional.ofNullable(e.getRequestHeaders().getFirst("Authorization")).ifPresent(v -> authorization.put(name, v));
                Optional.ofNullable(e.getRequestHeaders().getFirst("X-Api-Key")).ifPresent(v -> apiKey.put(name, v));
                int code = status.getOrDefault(name, 200);
                if (code == -1) { try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { } code = 200; }
                if (code != 200) { e.sendResponseHeaders(code, -1); e.close(); return; }
                response = op.equals("ocr") ? "{\"text\":\"인식 " + name + "\"}"
                        : "{\"document\":{\"filename\":\"document.pdf\",\"md_content\":\"| a | b |\\n|---|---|\",\"json_content\":{\"schema_name\":\"DoclingDocument\"}},"
                          + "\"status\":\"success\",\"errors\":[],\"processing_time\":0.1}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            try { e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { } finally { e.close(); }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private RemoteServer remote(String name, String suffix, String key, int timeout) {
        return new RemoteServer(name, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + suffix, key, timeout);
    }
    private int calls(String name) { return calls.getOrDefault(name, new AtomicInteger()).get(); }

    @Test void remoteOcrSendsImageBytesWithBearerAndFailsOver() {
        byte[] png = RemoteOcrProvider.SAMPLE;
        for (int code : new int[] {500, 503, 429, -1}) {
            status.clear(); calls.clear(); status.put("a", code);
            try (var ocr = new RemoteOcrProvider(new RemotePool("ocr", List.of(remote("a", "/v1", "k-a", 1), remote("b", "/v1", "k-b", 1))), "tesseract", 10)) {
                assertEquals("인식 b", ocr.recognize(png, "kor+eng"), "code " + code);
                assertEquals(1, calls("a"), "서버당 1회"); assertEquals(1, calls("b"));
                assertEquals("down", ocr.pool().status().getFirst().state());
            }
        }
        assertEquals("image/png", contentType.get("b")); assertEquals("Bearer k-b", authorization.get("b"));
        assertArrayEquals(png, body.get("b"), "본문은 이미지 바이트 그대로");
        assertEquals("image/jpeg", RemoteOcrProvider.contentType(new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF, 0}));
        assertEquals("application/octet-stream", RemoteOcrProvider.contentType(new byte[] {1, 2}));
    }

    @Test void remoteOcrClientErrorFailsImmediatelyWithoutLocalFallback() {
        status.put("a", 400);
        try (var ocr = new RemoteOcrProvider(new RemotePool("ocr", List.of(remote("a", "/v1", "", 1), remote("b", "/v1", "", 1))), "tesseract", 10)) {
            var error = assertThrows(RemoteException.class, () -> ocr.recognize(RemoteOcrProvider.SAMPLE, "kor+eng"));
            assertEquals(RemoteException.Kind.CLIENT_ERROR, error.kind()); assertEquals(0, calls("b"), "4xx는 다음 서버로 넘기지 않음");
            assertFalse(error.getMessage().contains("127.0.0.1"));
            assertNull(authorization.get("a"), "키가 없으면 인증 헤더 없음");
        }
        // 모든 서버가 실패하면 실패를 그대로 돌려준다.
        status.put("a", 500); status.put("b", 502);
        try (var ocr = new RemoteOcrProvider(new RemotePool("ocr", List.of(remote("a", "/v1", "", 1), remote("b", "/v1", "", 1))), "tesseract", 10)) {
            assertEquals(RemoteException.Kind.SERVER_ERROR, assertThrows(RemoteException.class, () -> ocr.recognize(RemoteOcrProvider.SAMPLE, "kor+eng")).kind());
        }
        status.clear();
        try (var ocr = new RemoteOcrProvider(new RemotePool("ocr", List.of(remote("a", "/v1", "", 1))), "other-engine", 10)) {
            assertEquals("mismatch", ocr.verify("a", Duration.ofSeconds(2)).result(), "엔진 목록 불일치");
        }
        try (var ocr = new RemoteOcrProvider(new RemotePool("ocr", List.of(remote("a", "/v1", "", 1))), "tesseract", 10)) {
            assertEquals("ok", ocr.verify("a", Duration.ofSeconds(2)).result());
        }
    }

    /** multipart 본문을 이름별 값 목록으로 나눈다. 파일 부분은 "filename|Content-Type|내용"으로 기록한다. */
    private static Map<String, List<String>> parts(String type, byte[] raw) {
        String boundary = type.substring(type.indexOf("boundary=") + 9);
        var result = new LinkedHashMap<String, List<String>>();
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        assertTrue(text.endsWith("--" + boundary + "--\r\n"), "마지막 경계");
        for (String part : text.split("--" + boundary)) {
            if (part.isEmpty() || part.startsWith("--")) continue;
            int split = part.indexOf("\r\n\r\n");
            String head = part.substring(2, split), value = part.substring(split + 4, part.length() - 2);
            String name = head.replaceAll("(?s).*?; name=\"([^\"]*)\".*", "$1");
            if (head.contains("filename=")) {
                String file = head.replaceAll("(?s).*filename=\"([^\"]*)\".*", "$1"), content = head.replaceAll("(?s).*Content-Type: ([^\r\n]*).*", "$1");
                value = file + "|" + content + "|" + value;
            }
            result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    @Test void doclingSendsMultipartWithApiKeyAndPageRange() {
        byte[] pdf = "%PDF-1.7 test".getBytes(StandardCharsets.ISO_8859_1);
        try (var docling = new RemoteDoclingClient(new RemotePool("docling", List.of(remote("d", "", "docling-key", 30))), 60, 1024)) {
            var result = docling.convert(pdf, "보고서 2026.PDF", new RemoteDoclingClient.PageRange(3, 4), true);
            assertTrue(result.converted()); assertEquals("d", result.server()); assertFalse(result.failover());
            assertEquals("| a | b |\n|---|---|", result.markdown()); assertEquals("DoclingDocument", result.json().get("schema_name").getAsString());
            assertEquals("docling-key", apiKey.get("d"), "인증은 X-Api-Key"); assertNull(authorization.get("d"), "Bearer는 보내지 않음");
            assertTrue(contentType.get("d").startsWith("multipart/form-data; boundary="));
            var form = parts(contentType.get("d"), body.get("d"));
            assertEquals(List.of("document.pdf|application/octet-stream|%PDF-1.7 test"), form.get("files"), "원본 이름 대신 확장자만");
            assertEquals(List.of("md", "json"), form.get("to_formats"));
            assertEquals(List.of("3", "4"), form.get("page_range"));
            assertEquals(List.of("inbody"), form.get("target_type")); assertEquals(List.of("placeholder"), form.get("image_export_mode"));
            assertEquals(List.of("false"), form.get("include_images"));
            assertEquals(List.of("30"), form.get("document_timeout"), "서버도 요청 시간 안에서 멈춤");
            // 전체 변환은 쪽 범위·json을 보내지 않는다.
            var whole = docling.convert(pdf, "a.pdf");
            assertNull(whole.json());
            form = parts(contentType.get("d"), body.get("d"));
            assertNull(form.get("page_range")); assertEquals(List.of("md"), form.get("to_formats"));
            assertEquals("ok", docling.verify("d", Duration.ofSeconds(5)).result());
            // 크기 상한·형식 확인은 보내기 전에 거부한다.
            int before = calls("d");
            assertThrows(IllegalArgumentException.class, () -> docling.convert(new byte[1025], "a.pdf"));
            assertThrows(IllegalArgumentException.class, () -> docling.convert(pdf, "확장자없음"));
            assertThrows(IllegalArgumentException.class, () -> RemoteDoclingClient.PageRange.single(0));
            assertEquals(before, calls("d"));
        }
    }

    @Test void doclingTimesOutFailsOverAndRejectsClientErrors() {
        delayMillis = 2500; status.put("slow", -1);
        try (var docling = new RemoteDoclingClient(new RemotePool("docling", List.of(remote("slow", "", "", 1))), 2, 1024)) {
            long start = System.nanoTime();
            var error = assertThrows(RemoteException.class, () -> docling.convert(new byte[] {1}, "a.pdf"));
            assertEquals(RemoteException.Kind.TIMEOUT, error.kind());
            assertTrue(System.nanoTime() - start < 2_400_000_000L, "클라이언트 시간 상한에서 멈춤");
        }
        try (var docling = new RemoteDoclingClient(new RemotePool("docling", List.of(remote("slow", "", "", 1), remote("ok", "", "", 5))), 10, 1024)) {
            var result = docling.convert(new byte[] {1}, "a.docx");
            assertEquals("ok", result.server()); assertTrue(result.failover());
        }
        status.put("bad", 422);
        try (var docling = new RemoteDoclingClient(new RemotePool("docling", List.of(remote("bad", "", "", 5), remote("ok", "", "", 5))), 10, 1024)) {
            int before = calls("ok");
            assertEquals(RemoteException.Kind.CLIENT_ERROR, assertThrows(RemoteException.class, () -> docling.convert(new byte[] {1}, "a.pdf")).kind());
            assertEquals(before, calls("ok"));
        }
        assertThrows(IllegalArgumentException.class, () -> new RemoteDoclingClient(new RemotePool("docling", List.of(remote("ok", "", "", 5))), 10, RemoteDoclingClient.MAX_FILE_BYTES + 1));
    }
}

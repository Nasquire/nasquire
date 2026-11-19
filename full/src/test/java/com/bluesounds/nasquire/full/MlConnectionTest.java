package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.Providers;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 본문 수신 전 거절은 연결 종료를 알리고, 끝까지 읽은 요청은 같은 연결에서 계속 처리한다. */
class MlConnectionTest {
    private static final String TOKEN = MlServerTest.TOKEN;
    private static final String AUTH = "Authorization: Bearer " + TOKEN + "\r\n";
    private MlServer server(int threads, Providers.EmbeddingProvider embedding) throws IOException {
        return new MlServer(new InetSocketAddress("127.0.0.1", 0), MlServer.sha256(TOKEN), embedding, "stub", null, null, 5, null, threads);
    }
    private Providers.EmbeddingProvider embedding() {
        return new Providers.EmbeddingProvider() {
            public String modelId() { return "stub"; }
            public int dimensions() { return 1; }
            public float[] embed(String text) { return new float[] {1}; }
        };
    }
    private Socket socket(MlServer server) throws IOException {
        var socket = new Socket("127.0.0.1", server.port()); socket.setSoTimeout(5_000); return socket;
    }
    private static void write(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
    }
    private record Response(int status, Map<String, String> headers, byte[] body) { }
    private static String line(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream(); int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return out.toString(StandardCharsets.US_ASCII).stripTrailing();
            out.write(b);
        }
        throw new EOFException("응답 헤더를 받기 전에 연결 종료");
    }
    private static Response response(Socket socket) throws IOException {
        var in = socket.getInputStream(); int status = Integer.parseInt(line(in).split(" ")[1]);
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER); String line;
        while (!(line = line(in)).isEmpty()) {
            int colon = line.indexOf(':'); headers.put(line.substring(0, colon), line.substring(colon + 1).strip());
        }
        int length = Integer.parseInt(headers.get("Content-Length")); byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, "응답 본문도 끝까지 전달");
        return new Response(status, headers, body);
    }

    @Test void unreadBodiesAnnounceCloseWithoutWaitingForTheUpload() throws Exception {
        try (var server = server(2, embedding())) {
            var requests = List.of(
                    "POST /v1/embeddings HTTP/1.1\r\n",
                    "POST /missing HTTP/1.1\r\n" + AUTH,
                    "GET /v1/embeddings HTTP/1.1\r\n" + AUTH,
                    "POST /v1/embeddings HTTP/1.1\r\n" + AUTH,
                    "POST /v1/rerank HTTP/1.1\r\n" + AUTH,
                    "POST /v1/embeddings HTTP/1.1\r\n" + AUTH + "Content-Type: application/json\r\n");
            int[] statuses = {401, 404, 405, 400, 404, 413};
            for (int i = 0; i < requests.size(); i++) {
                try (var socket = socket(server)) {
                    // 본문 대신 일부만 보낸다. JDK의 자동 비우기 상한보다 큰 요청을 끝까지 기다려서는 안 된다.
                    write(socket, requests.get(i) + "Host: localhost\r\nContent-Length: " + (MlServer.MAX_JSON + 1) + "\r\n\r\nx");
                    var response = response(socket);
                    assertEquals(statuses[i], response.status());
                    assertEquals("close", response.headers().get("Connection"), "미수신 본문이 남은 " + statuses[i]);
                }
            }
        }
    }

    @Test void consumedFixedAndChunkedBodiesKeepTheConnectionReusableEvenOnInputErrors() throws Exception {
        try (var server = server(2, embedding()); var socket = socket(server)) {
            String json = "{\"input\":\"x\"}";
            var requests = List.of(
                    "Content-Length: " + json.length() + "\r\n\r\n" + json,
                    "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(json.length()) + "\r\n" + json + "\r\n0\r\n\r\n",
                    "Content-Length: 1\r\n\r\n[");
            int[] statuses = {200, 200, 400};
            for (int i = 0; i < requests.size(); i++) {
                write(socket, "POST /v1/embeddings HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "Content-Type: application/json\r\n" + requests.get(i));
                var response = response(socket);
                assertEquals(statuses[i], response.status()); assertNull(response.headers().get("Connection"));
            }
            write(socket, "GET /v1/models HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "\r\n");
            assertEquals(200, response(socket).status(), "같은 소켓의 후속 요청");
        }
    }

    @Test void workerSaturationAnnouncesCloseBeforeReadingTheBody() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var embedding = new Providers.EmbeddingProvider() {
            public String modelId() { return "stub"; }
            public int dimensions() { return 1; }
            public float[] embed(String text) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new float[] {1};
            }
        };
        try (var server = server(1, embedding); var client = HttpClient.newHttpClient()) {
            var first = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/embeddings"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var socket = socket(server)) {
                    write(socket, "POST /v1/embeddings HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "Content-Length: 1000000\r\n\r\nx");
                    var response = response(socket);
                    assertEquals(503, response.status()); assertEquals("1", response.headers().get("Retry-After"));
                    assertEquals("close", response.headers().get("Connection"));
                }
            } finally { release.countDown(); }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        }
    }
}

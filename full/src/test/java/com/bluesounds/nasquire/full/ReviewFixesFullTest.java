package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 보안 교차 검토 반영: ML 서버 과부하 거부(M4). OCR 격리 시험은 core OcrIsolationTest로 옮겼다. */
class ReviewFixesFullTest {
    static final String TOKEN = "ml-test-token-0123456789abcdefghijklmnop";
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void overloadedMlServerRejectsImmediately() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        MlServer.Scorer scorer = (query, docs, deadline) -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new float[docs.size()];
        };
        Providers.EmbeddingProvider embedding = new Providers.EmbeddingProvider() {
            public String modelId() { return "stub"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { return new float[] {1, 0}; }
        };
        try (var server = new MlServer(new InetSocketAddress("127.0.0.1", 0), MlServer.sha256(TOKEN), embedding, "e5-test", scorer, "rerank-test", 5, null, 1)) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var first = pool.submit(() -> post(server, "/v1/rerank", "Bearer " + TOKEN, "{\"query\":\"q\",\"documents\":[\"a\",\"b\"]}"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long start = System.nanoTime();
                var busy = post(server, "/v1/embeddings", "Bearer " + TOKEN, "{\"input\":\"sea\"}");
                assertEquals(503, busy.statusCode()); assertEquals("1", busy.headers().firstValue("Retry-After").orElse(null));
                assertEquals(401, post(server, "/v1/embeddings", "Bearer wrong-token-value-0123456789abcdefghij", "{\"input\":\"sea\"}").statusCode(),
                        "작업자가 모두 바빠도 인증 실패는 바로 거절");
                assertTrue(System.nanoTime() - start < 2_000_000_000L, "대기열 없이 즉시 응답");
                release.countDown();
                assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode());
                assertEquals(200, post(server, "/v1/embeddings", "Bearer " + TOKEN, "{\"input\":\"sea\"}").statusCode());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    private HttpResponse<String> post(MlServer server, String path, String auth, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).header("Authorization", auth)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

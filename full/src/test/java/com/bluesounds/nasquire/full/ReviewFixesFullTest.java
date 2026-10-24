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

/** 4단계 보안 교차 검토 반영: 렌더링 시간 제한·디코딩 상한(M1·M2), ML 서버 과부하 거부(M4). */
class ReviewFixesFullTest {
    static final String TOKEN = "ml-test-token-0123456789abcdefghijklmnop";
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void renderTimeoutFailsFileWithoutBlockingWorker() throws Exception {
        // 중단을 무시하는 렌더러도 호출자를 시간 상한 뒤 놓아준다.
        long start = System.nanoTime();
        var error = assertThrows(IOException.class, () -> OcrTextExtractor.limited(() -> {
            long until = System.nanoTime() + 1_500_000_000L;
            while (System.nanoTime() < until) Thread.onSpinWait();
            return 1;
        }, 100));
        assertTrue(error.getMessage().contains("시간 초과"));
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
        assertEquals(2, OcrTextExtractor.limited(() -> 2, 5_000), "남은 작업자로 다음 파일 처리");
        assertEquals("이미지 처리 메모리 부족",
                assertThrows(IOException.class, () -> OcrTextExtractor.limited(() -> { throw new OutOfMemoryError("test"); }, 5_000)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> OcrTextExtractor.limited(() -> { throw new IllegalArgumentException("x"); }, 5_000));
        Thread.sleep(1_600); // 위의 바쁜 작업이 끝나 공용 렌더 작업자가 다른 테스트에 영향을 주지 않게 한다.
    }

    @Test void largeImagesAreSubsampledAndOversizedRejected() throws Exception {
        Path big = temp.resolve("big.png");
        ImageIO.write(new BufferedImage(4000, 3000, BufferedImage.TYPE_BYTE_GRAY), "png", big.toFile());
        var sizes = new ArrayList<Long>();
        var extractor = new OcrTextExtractor((image, language) -> {
            try { var decoded = ImageIO.read(new ByteArrayInputStream(image)); sizes.add((long)decoded.getWidth() * decoded.getHeight()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return "ok";
        }, 1);
        extractor.extractLocated(big, (p, l, t) -> { }, 100);
        assertTrue(sizes.getFirst() <= OcrTextExtractor.MAX_PIXELS, "부분 샘플링으로 픽셀 상한 안: " + sizes);
        Path wide = temp.resolve("wide.png");
        ImageIO.write(new BufferedImage(17_000, 4, BufferedImage.TYPE_BYTE_GRAY), "png", wide.toFile());
        assertThrows(IOException.class, () -> extractor.extractLocated(wide, (p, l, t) -> { }, 100));
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(17_000, 4, BufferedImage.TYPE_BYTE_GRAY), "png", bytes);
        assertThrows(IllegalArgumentException.class, () -> OcrTextExtractor.recognizeImage((image, language) -> "x", bytes.toByteArray()), "ML 서버도 같은 상한");
        // PDF 쪽: 기본 150 DPI, 큰 쪽(A0)은 픽셀 상한에 맞춰 DPI를 낮추고 최소 DPI로도 넘으면 거부한다.
        assertEquals(150f, OcrTextExtractor.renderDpi(612, 792));
        assertEquals(62f, OcrTextExtractor.renderDpi(2384, 3370));
        assertThrows(IOException.class, () -> OcrTextExtractor.renderDpi(14_400, 14_400));
    }

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

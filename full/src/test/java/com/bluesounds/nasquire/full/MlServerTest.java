package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MlServerTest {
    static final String TOKEN = "ml-test-token-0123456789abcdefghijklmnop";
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicReference<String> ocrLanguage = new AtomicReference<>();
    private MlServer server(boolean all) throws Exception {
        Providers.EmbeddingProvider embedding = new Providers.EmbeddingProvider() {
            public String modelId() { return "stub"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { return text.contains("sea") ? new float[] {1, 0} : new float[] {0, 1}; }
            public float[] embedQuery(String text) { return new float[] {0.5f, 0.5f}; }
        };
        MlServer.Scorer scorer = (query, docs, deadline) -> {
            var s = new float[docs.size()]; for (int i = 0; i < s.length; i++) s[i] = docs.get(i).contains(query) ? 5 : -i; return s;
        };
        Providers.OcrProvider ocr = (image, language) -> { ocrLanguage.set(language); return "인식 결과"; };
        return new MlServer(new InetSocketAddress("127.0.0.1", 0), MlServer.sha256(TOKEN), embedding, "e5-test",
                all ? scorer : null, "rerank-test", 5, all ? ocr : null, 2);
    }
    private HttpResponse<String> call(MlServer server, String method, String path, String auth, String type, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) request.header("Authorization", auth);
        if (type != null) request.header("Content-Type", type);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> json(MlServer server, String path, String auth, String body) throws Exception {
        return call(server, "POST", path, auth, "application/json", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void everyRequestNeedsTheExactBearerToken() throws Exception {
        try (var server = server(true)) {
            String body = "{\"input\":\"sea\"}";
            for (String auth : Arrays.asList(null, "Bearer", "Bearer ", "bearer " + TOKEN, "Basic " + TOKEN, "Bearer " + TOKEN + "x",
                    "Bearer " + TOKEN.substring(1), "Bearer wrong-token-value-0123456789abcdefghij", "Bearer " + "a".repeat(700))) {
                for (String path : List.of("/v1/embeddings", "/v1/rerank", "/v1/ocr", "/v1/models", "/", "/api/search", "/mcp", "/api/sources", "/index.html")) {
                    var response = json(server, path, auth, body);
                    assertEquals(401, response.statusCode(), path + " " + auth);
                    assertFalse(response.body().contains(TOKEN));
                    assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
                }
                assertEquals(401, call(server, "GET", "/v1/models", auth, null, null).statusCode());
            }
            String bearer = "Bearer " + TOKEN;
            // 관리 화면·소스·검색·MCP 경로가 없다.
            for (String path : List.of("/", "/index.html", "/api/search", "/api/sources", "/api/auth", "/api/setup", "/mcp", "/api/full", "/v1/chat/completions"))
                assertEquals(404, json(server, path, bearer, "{}").statusCode(), path);
            assertEquals(405, call(server, "GET", "/v1/embeddings", bearer, null, null).statusCode());
            assertEquals(400, call(server, "POST", "/v1/embeddings?x=1", bearer, "application/json", body.getBytes()).statusCode());
            assertTrue(call(server, "GET", "/v1/models", bearer, null, null).body().contains("e5-test"));
        }
    }
    @Test void openAiCompatibleEmbeddingsAndRerankWorkWithNasquireRemoteClients() throws Exception {
        try (var server = server(true)) {
            String base = "http://127.0.0.1:" + server.port() + "/v1";
            try (var remote = new RemoteEmbeddingProvider(base, "e5-test", TOKEN, 2, 2, 5)) {
                var vectors = remote.embedBatch(List.of("sea", "land"));
                assertArrayEquals(new float[] {1, 0}, vectors.get(0)); assertArrayEquals(new float[] {0, 1}, vectors.get(1));
            }
            try (var wrongKey = new RemoteEmbeddingProvider(base, "e5-test", "wrong", 2, 2, 5)) {
                assertThrows(IllegalStateException.class, () -> wrongKey.embed("sea"));
            }
            try (var wrongModel = new RemoteEmbeddingProvider(base, "other-model", TOKEN, 2, 2, 5)) {
                assertThrows(IllegalStateException.class, () -> wrongModel.embed("sea"));
            }
            var query = json(server, "/v1/embeddings", "Bearer " + TOKEN, "{\"input\":[\"q\"],\"input_type\":\"query\"}");
            assertEquals(200, query.statusCode()); assertTrue(query.body().contains("0.5"));
            var hits = List.of(new SearchIndex.Hit("s", "S", "s:a", 1, 0, "alpha", 1, 3),
                    new SearchIndex.Hit("s", "S", "s:b", 2, 0, "beta match", 1, 2), new SearchIndex.Hit("s", "S", "s:c", 3, 0, "gamma", 1, 1));
            try (var reranker = new RemoteRerankerProvider(base, "rerank-test", TOKEN, 3, 5)) {
                assertEquals(List.of(hits.get(1), hits.get(0), hits.get(2)), reranker.rerank("match", hits));
            }
            try (var wrong = new RemoteRerankerProvider(base, "rerank-test", "wrong", 3, 5)) {
                assertSame(hits, wrong.rerank("match", hits), "인증 실패는 원래 순서");
            }
            var top = json(server, "/v1/rerank", "Bearer " + TOKEN, "{\"query\":\"x\",\"documents\":[\"a\",\"x\",{\"text\":\"b\"}],\"top_n\":1}");
            assertEquals(200, top.statusCode()); assertTrue(top.body().contains("\"index\":1")); assertFalse(top.body().contains("\"index\":0"));
            var image = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image);
            var ocr = call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", image.toByteArray());
            assertEquals(200, ocr.statusCode(), ocr.body()); assertTrue(ocr.body().contains("인식 결과")); assertEquals("kor+eng", ocrLanguage.get());
            assertEquals(400, call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", "not an image".getBytes()).statusCode());
        }
    }
    @Test void limitsAndDisabledFeatures() throws Exception {
        try (var server = server(false)) {
            String bearer = "Bearer " + TOKEN;
            assertEquals(404, json(server, "/v1/rerank", bearer, "{\"query\":\"a\",\"documents\":[\"b\"]}").statusCode());
            assertEquals(404, call(server, "POST", "/v1/ocr", bearer, "image/png", new byte[] {1}).statusCode());
            var many = new StringJoiner(",", "{\"input\":[", "]}"); for (int i = 0; i < 65; i++) many.add("\"x\"");
            assertEquals(400, json(server, "/v1/embeddings", bearer, many.toString()).statusCode());
            assertEquals(400, json(server, "/v1/embeddings", bearer, "{\"input\":\"" + "x".repeat(8193) + "\"}").statusCode());
            assertEquals(400, json(server, "/v1/embeddings", bearer, "{\"input\":[]}").statusCode());
            assertEquals(400, json(server, "/v1/embeddings", bearer, "not json").statusCode());
            assertEquals(400, json(server, "/v1/embeddings", bearer, "[1]").statusCode());
            assertEquals(400, call(server, "POST", "/v1/embeddings", bearer, "text/plain", "{\"input\":\"a\"}".getBytes()).statusCode());
            assertEquals(413, json(server, "/v1/embeddings", bearer, "{\"input\":\"" + "x".repeat(MlServer.MAX_JSON) + "\"}").statusCode());
        }
    }
    @Test void configurationRequiresTokenFromFileOrEnvironmentAndAFeature() throws Exception {
        var env = new HashMap<String, String>(Map.of("NASQUIRE_ML_EMBEDDING", "builtin", "NASQUIRE_DATA", temp.toString()));
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[] {"--nasquire.mode=ml"}, env), "토큰 미설정 시작 거부");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[] {"--nasquire.ml.token=" + TOKEN}, env), "명령행 토큰 거부");
        env.put("NASQUIRE_ML_TOKEN", "short");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], env));
        env.put("NASQUIRE_ML_TOKEN", TOKEN);
        var config = MlConfig.load(new String[] {"--server.port=8199"}, env);
        assertEquals(8199, config.port()); assertEquals("builtin", config.embedding());
        assertArrayEquals(MlServer.sha256(TOKEN), config.tokenHash()); assertFalse(config.toString().contains(TOKEN));
        env.put("NASQUIRE_ML_EMBEDDING", "off");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], env), "기능 없음 거부");
        env.remove("NASQUIRE_ML_TOKEN");
        Path tokenFile = Files.writeString(temp.resolve("token"), TOKEN + "\n");
        Path file = Files.writeString(temp.resolve("ml.properties"), "nasquire.ml.reranker=builtin\nnasquire.ml.token-file=" + tokenFile + "\n");
        var fromFile = MlConfig.load(new String[] {"--nasquire.ml.config=" + file}, env);
        assertEquals("builtin", fromFile.reranker()); assertArrayEquals(MlServer.sha256(TOKEN), fromFile.tokenHash());
        Files.writeString(file, "nasquire.ml.reranker=builtin\nnasquire.ml.token=" + TOKEN + "\n");
        assertEquals("builtin", MlConfig.load(new String[] {"--nasquire.ml.config=" + file}, env).reranker());
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[] {"--nasquire.ml.config=" + file, "--nasquire.ml.reranker=gpu"}, env));
    }
    @Test void startRefusesMissingModelsWithoutDownloading() throws Exception {
        var env = Map.of("NASQUIRE_ML_TOKEN", TOKEN, "NASQUIRE_ML_RERANKER", "builtin", "NASQUIRE_DATA", temp.resolve("data").toString(), "SERVER_PORT", "0", "SERVER_ADDRESS", "127.0.0.1");
        assertThrows(IllegalStateException.class, () -> MlServer.start(MlConfig.load(new String[0], env)));
        assertFalse(Files.exists(temp.resolve("data/models")));
    }
    @Test void localRerankerFallsBackWithoutVerifiedModelAndRejectsOutsideDownloads() throws Exception {
        var hits = List.of(new SearchIndex.Hit("s", "S", "s:a", 1, 0, "alpha", 1, 2), new SearchIndex.Hit("s", "S", "s:b", 2, 0, "beta", 1, 1));
        try (var local = new OnnxRerankerProvider(temp.resolve("missing.onnx"), temp.resolve("missing.json"), false, temp, 20, 5)) {
            assertSame(hits, local.rerank("alpha", hits));
            assertThrows(IllegalStateException.class, () -> local.scores("q", List.of("d"), local.deadline()));
        }
        Path data = Files.createDirectory(temp.resolve("data"));
        try (var outside = new OnnxRerankerProvider(temp.resolve("outside/model.onnx"), temp.resolve("outside/tokenizer.json"), true, data, 20, 5)) {
            assertThrows(IllegalStateException.class, () -> outside.scores("q", List.of("d"), outside.deadline()));
            assertFalse(Files.exists(temp.resolve("outside/model.onnx")));
        }
        Files.createDirectories(temp.resolve("bad")); Files.writeString(temp.resolve("bad/model.onnx"), "not the pinned model");
        Files.writeString(temp.resolve("bad/tokenizer.json"), "{}");
        try (var bad = new OnnxRerankerProvider(temp.resolve("bad/model.onnx"), temp.resolve("bad/tokenizer.json"), false, temp, 20, 5)) {
            assertSame(hits, bad.rerank("alpha", hits), "해시 불일치 모델은 로드하지 않음");
        }
    }
}

package com.bluesounds.nasquire.full;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** ML remote 모드: 가짜 HTTP 공급자(OpenAI 호환 임베딩·Infinity 형식 리랭크·OCR·docling-serve)로 게이트웨이 동작을 확인한다. */
class MlGatewayTest {
    static final String TOKEN = "ml-test-token-0123456789abcdefghijklmnop";
    static final String KEY_A = "provider-key-aaaaaaaa", KEY_B = "provider-key-bbbbbbbb", DOCLING_KEY = "docling-key-cccccccc";
    @TempDir Path temp;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<AutoCloseable> open = new ArrayList<>();
    @AfterEach void closeAll() throws Exception { for (var c : open.reversed()) c.close(); }

    /** 가짜 공급자 하나. 받은 인증 헤더·본문을 기록하고 임베딩 응답 상태를 바꿀 수 있다. */
    static final class Fake implements AutoCloseable {
        final HttpServer http;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger embedStatus = new AtomicInteger(200), embedCalls = new AtomicInteger();
        final List<String> models;
        final int dimensions;
        Fake(int dimensions, String... models) throws Exception {
            this.dimensions = dimensions; this.models = List.of(models);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle); http.start();
        }
        String root() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        String v1() { return root() + "/v1"; }
        String port() { return Integer.toString(http.getAddress().getPort()); }
        private void handle(HttpExchange ex) throws java.io.IOException {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(path + "|" + ex.getRequestHeaders().getFirst("Authorization") + "|" + ex.getRequestHeaders().getFirst("X-Api-Key")
                        + "|" + (body.length() > 2000 ? body.substring(0, 2000) : body));
                var out = new JsonObject(); int status = 200;
                switch (path) {
                    case "/v1/models" -> {
                        var data = new JsonArray();
                        for (String m : models) { var o = new JsonObject(); o.addProperty("id", m); data.add(o); }
                        out.add("data", data);
                    }
                    case "/v1/embeddings" -> {
                        embedCalls.incrementAndGet();
                        status = embedStatus.get();
                        if (status != 200) out.addProperty("error", "upstream-secret-detail " + root());
                        else {
                            var request = JsonParser.parseString(body).getAsJsonObject();
                            var data = new JsonArray(); var input = request.getAsJsonArray("input");
                            for (int i = 0; i < input.size(); i++) {
                                var vector = new JsonArray(); for (int d = 0; d < dimensions; d++) vector.add(d == 0 ? i + 1 : 0.25);
                                var item = new JsonObject(); item.addProperty("object", "embedding"); item.addProperty("index", i); item.add("embedding", vector);
                                data.add(item);
                            }
                            out.add("data", data); out.addProperty("model", request.get("model").getAsString());
                            var usage = new JsonObject(); usage.addProperty("prompt_tokens", 7); usage.addProperty("total_tokens", 7); out.add("usage", usage);
                        }
                    }
                    case "/v1/rerank" -> {
                        // Infinity 형식: 점수 내림차순 results[{relevance_score, index, document}].
                        var request = JsonParser.parseString(body).getAsJsonObject();
                        String query = request.get("query").getAsString(); var docs = request.getAsJsonArray("documents");
                        var results = new JsonArray();
                        for (int i = docs.size() - 1; i >= 0; i--) {
                            var r = new JsonObject(); r.addProperty("relevance_score", docs.get(i).getAsString().contains(query) ? 0.9 : 0.1 / (i + 1));
                            r.addProperty("index", i); r.addProperty("document", docs.get(i).getAsString()); results.add(r);
                        }
                        out.addProperty("object", "rerank"); out.add("results", results); out.addProperty("model", request.get("model").getAsString());
                    }
                    case "/v1/ocr" -> out.addProperty("text", "원격 인식");
                    case "/version" -> out.addProperty("docling-serve", "1.38.2");
                    case "/v1/convert/file" -> {
                        var document = new JsonObject(); document.addProperty("md_content", "# ok");
                        out.add("document", document); out.addProperty("status", "success"); out.add("errors", new JsonArray());
                    }
                    default -> status = 404;
                }
                byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes);
            }
        }
        public void close() { http.stop(0); }
    }
    private Fake fake(int dimensions, String... models) throws Exception { var f = new Fake(dimensions, models); open.add(f); return f; }

    private Map<String, String> base() {
        var env = new HashMap<String, String>();
        env.put("NASQUIRE_ML_TOKEN", TOKEN); env.put("NASQUIRE_DATA", temp.resolve("data").toString());
        env.put("SERVER_PORT", "0"); env.put("SERVER_ADDRESS", "127.0.0.1");
        return env;
    }
    private void embedding(Map<String, String> env, Fake a, Fake b, int dimensions) throws Exception {
        env.put("NASQUIRE_ML_EMBEDDING", "remote"); env.put("NASQUIRE_ML_EMBEDDING_MODEL", "emb-remote");
        env.put("NASQUIRE_ML_EMBEDDING_DIMENSIONS", Integer.toString(dimensions)); env.put("NASQUIRE_ML_EMBEDDING_SERVERS", "a,b");
        env.put("NASQUIRE_ML_EMBEDDING_SERVER_A_URL", a.v1());
        env.put("NASQUIRE_ML_EMBEDDING_SERVER_A_KEY_FILE", Files.writeString(temp.resolve("key-a"), KEY_A + "\n").toString());
        env.put("NASQUIRE_ML_EMBEDDING_SERVER_B_URL", b.v1()); env.put("NASQUIRE_ML_EMBEDDING_SERVER_B_KEY", KEY_B);
        env.put("NASQUIRE_ML_EMBEDDING_SERVER_B_TIMEOUT_SECONDS", "5");
    }
    private MlServer start(Map<String, String> env) throws Exception {
        var server = MlServer.start(MlConfig.load(new String[0], env)); open.add(server); return server;
    }
    private HttpResponse<String> call(MlServer server, String method, String path, String auth, String type, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth != null) request.header("Authorization", auth);
        if (type != null) request.header("Content-Type", type);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(MlServer server, String path, String body) throws Exception {
        return call(server, "POST", path, "Bearer " + TOKEN, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> get(MlServer server, String path) throws Exception { return call(server, "GET", path, "Bearer " + TOKEN, null, null); }
    private static void assertHidden(String text, Fake... fakes) {
        for (String secret : List.of(KEY_A, KEY_B, DOCLING_KEY, TOKEN, "127.0.0.1", "upstream-secret-detail")) assertFalse(text.contains(secret), secret + " in " + text);
        for (var f : fakes) assertFalse(text.contains(f.port()), "port in " + text);
    }

    @Test void relaysEveryRemoteFeatureWithServerHeadersAndHidesProviderDetails() throws Exception {
        var a = fake(3, "emb-remote", "rerank-remote", "tesseract"); var b = fake(3, "emb-remote");
        var env = base(); embedding(env, a, b, 3);
        env.put("NASQUIRE_ML_RERANKER", "remote"); env.put("NASQUIRE_ML_RERANKER_MODEL", "rerank-remote");
        env.put("NASQUIRE_ML_RERANKER_SERVERS", "a"); env.put("NASQUIRE_ML_RERANKER_SERVER_A_URL", a.v1()); env.put("NASQUIRE_ML_RERANKER_SERVER_A_KEY", KEY_A);
        env.put("NASQUIRE_ML_OCR", "remote"); env.put("NASQUIRE_ML_OCR_SERVERS", "a"); env.put("NASQUIRE_ML_OCR_SERVER_A_URL", a.v1());
        env.put("NASQUIRE_ML_OCR_SERVER_A_KEY", KEY_A);
        env.put("NASQUIRE_ML_DOCLING", "remote"); env.put("NASQUIRE_ML_DOCLING_SERVERS", "d1"); env.put("NASQUIRE_ML_DOCLING_SERVER_D1_URL", a.root());
        env.put("NASQUIRE_ML_DOCLING_SERVER_D1_KEY", DOCLING_KEY);
        var server = start(env);

        var e = post(server, "/v1/embeddings", "{\"model\":\"emb-remote\",\"input\":[\"sea\",\"land\"],\"input_type\":\"query\"}");
        assertEquals(200, e.statusCode(), e.body());
        var body = JsonParser.parseString(e.body()).getAsJsonObject();
        assertEquals("emb-remote", body.get("model").getAsString());
        assertEquals(2, body.getAsJsonArray("data").size());
        assertEquals(2.0, body.getAsJsonArray("data").get(1).getAsJsonObject().getAsJsonArray("embedding").get(0).getAsDouble(), "값은 정규화 없이 그대로 중계");
        assertEquals(7, body.getAsJsonObject("usage").get("total_tokens").getAsInt());
        assertEquals("a", e.headers().firstValue("X-Nasquire-Embedding-Server").orElse(null));
        assertEquals("false", e.headers().firstValue("X-Nasquire-Failover").orElse(null));
        assertHidden(e.body() + e.headers().map(), a, b);
        assertEquals(400, post(server, "/v1/embeddings", "{\"model\":\"other\",\"input\":\"x\"}").statusCode(), "다른 모델 이름 거부");
        var second = post(server, "/v1/embeddings", "{\"input\":\"x\"}");
        assertEquals("b", second.headers().firstValue("X-Nasquire-Embedding-Server").orElse(null), "round_robin");

        // 공급자는 ML 토큰이 아니라 서버별 공급자 키를 받는다. 모델 이름은 설정된 원격 모델이다.
        assertTrue(a.requests.stream().anyMatch(r -> r.startsWith("/v1/embeddings|Bearer " + KEY_A + "|") && r.contains("\"model\":\"emb-remote\"")));
        assertTrue(b.requests.stream().anyMatch(r -> r.startsWith("/v1/embeddings|Bearer " + KEY_B + "|")));
        assertTrue(a.requests.stream().noneMatch(r -> r.contains(TOKEN) || r.contains("input_type")));
        assertTrue(b.requests.stream().noneMatch(r -> r.contains(TOKEN)));

        var r = post(server, "/v1/rerank", "{\"query\":\"match\",\"documents\":[\"alpha\",\"beta match\",{\"text\":\"gamma\"}],\"top_n\":2}");
        assertEquals(200, r.statusCode(), r.body());
        var rerank = JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals("rerank-remote", rerank.get("model").getAsString());
        var results = rerank.getAsJsonArray("results");
        assertEquals(2, results.size());
        assertEquals(1, results.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals(0, results.get(1).getAsJsonObject().get("index").getAsInt());
        assertEquals(Set.of("index", "relevance_score"), results.get(0).getAsJsonObject().keySet(), "공급자 document 필드는 내보내지 않음");
        assertEquals("a", r.headers().firstValue("X-Nasquire-Rerank-Server").orElse(null));
        assertHidden(r.body(), a, b);

        var image = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", image);
        var ocr = call(server, "POST", "/v1/ocr", "Bearer " + TOKEN, "image/png", image.toByteArray());
        assertEquals(200, ocr.statusCode(), ocr.body()); assertTrue(ocr.body().contains("원격 인식"));
        assertEquals("a", ocr.headers().firstValue("X-Nasquire-Ocr-Server").orElse(null));
        assertTrue(a.requests.stream().anyMatch(x -> x.startsWith("/v1/ocr|Bearer " + KEY_A + "|")));
        // docling-serve는 X-Api-Key로 받는다(시작 확인: /version·작은 변환 1건).
        assertTrue(a.requests.stream().anyMatch(x -> x.startsWith("/version|null|" + DOCLING_KEY)));
        assertTrue(a.requests.stream().anyMatch(x -> x.startsWith("/v1/convert/file|null|" + DOCLING_KEY)));

        var models = get(server, "/v1/models");
        assertEquals(200, models.statusCode());
        var data = JsonParser.parseString(models.body()).getAsJsonObject().getAsJsonArray("data");
        var byFeature = new HashMap<String, JsonObject>();
        for (var m : data) byFeature.put(m.getAsJsonObject().get("feature").getAsString(), m.getAsJsonObject());
        assertEquals("emb-remote", byFeature.get("embedding").get("id").getAsString());
        assertEquals(3, byFeature.get("embedding").get("dimensions").getAsInt());
        assertEquals("remote", byFeature.get("embedding").get("mode").getAsString());
        assertEquals("rerank-remote", byFeature.get("reranker").get("id").getAsString());
        assertEquals("tesseract", byFeature.get("ocr").get("id").getAsString());
        assertEquals("remote", byFeature.get("ocr").get("mode").getAsString());
        assertEquals("1.38.2", byFeature.get("docling").get("version").getAsString());
        assertHidden(models.body(), a, b);

        var status = get(server, "/v1/status");
        assertEquals(200, status.statusCode());
        var features = JsonParser.parseString(status.body()).getAsJsonObject().getAsJsonObject("features");
        var servers = features.getAsJsonObject("embedding").getAsJsonArray("servers");
        assertEquals(List.of("a", "b"), servers.asList().stream().map(s -> s.getAsJsonObject().get("name").getAsString()).toList());
        assertEquals("up", servers.get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("remote", features.getAsJsonObject("docling").get("mode").getAsString());
        assertEquals("d1", features.getAsJsonObject("docling").getAsJsonArray("servers").get(0).getAsJsonObject().get("name").getAsString());
        assertHidden(status.body(), a, b);
        // 상태·모델 조회도 인증이 필요하다.
        for (String path : List.of("/v1/status", "/v1/models")) {
            assertEquals(401, call(server, "GET", path, null, null, null).statusCode());
            assertEquals(401, call(server, "GET", path, "Bearer " + KEY_A, null, null).statusCode(), "공급자 키로는 접근 불가");
        }
        assertEquals(405, post(server, "/v1/status", "{}").statusCode());
    }

    @Test void failsOverOnServerErrorsButFailsImmediatelyOn4xx() throws Exception {
        var a = fake(3, "emb-remote"); var b = fake(3, "emb-remote");
        var env = base(); embedding(env, a, b, 3);
        var server = start(env);
        int aCalls = a.embedCalls.get(), bCalls = b.embedCalls.get();

        a.embedStatus.set(503);
        var failover = post(server, "/v1/embeddings", "{\"input\":\"x\"}");   // 순번 a → 503 → b
        assertEquals(200, failover.statusCode(), failover.body());
        assertEquals("b", failover.headers().firstValue("X-Nasquire-Embedding-Server").orElse(null));
        assertEquals("true", failover.headers().firstValue("X-Nasquire-Failover").orElse(null));
        assertEquals(aCalls + 1, a.embedCalls.get()); assertEquals(bCalls + 1, b.embedCalls.get());

        var direct = post(server, "/v1/embeddings", "{\"input\":\"x\"}");     // 순번 b
        assertEquals("false", direct.headers().firstValue("X-Nasquire-Failover").orElse(null));

        a.embedStatus.set(400);
        int bBefore = b.embedCalls.get();
        var clientError = post(server, "/v1/embeddings", "{\"input\":\"x\"}"); // 순번 a → 400 → 즉시 실패
        assertEquals(502, clientError.statusCode());
        assertEquals(bBefore, b.embedCalls.get(), "4xx는 다음 서버로 넘기지 않음");
        assertHidden(clientError.body() + clientError.headers().map(), a, b);

        a.embedStatus.set(500); b.embedStatus.set(429);
        var exhausted = post(server, "/v1/embeddings", "{\"input\":\"x\"}");
        assertEquals(503, exhausted.statusCode());
        assertEquals("1", exhausted.headers().firstValue("Retry-After").orElse(null));
        assertHidden(exhausted.body(), a, b);

        b.embedStatus.set(200);
        var status = get(server, "/v1/status");
        assertTrue(status.body().contains("server_error") || status.body().contains("client_error"), status.body());
        assertHidden(status.body(), a, b);
    }

    @Test void startRefusesModelOrDimensionMismatch() throws Exception {
        var a = fake(3, "emb-remote"); var b = fake(3, "other-model");
        var env = base(); embedding(env, a, b, 3);
        var e = assertThrows(IllegalStateException.class, () -> start(env), "모델 불일치 서버가 있으면 시작 거부");
        assertTrue(e.getMessage().contains("서버 b "), e.getMessage()); assertHidden(e.getMessage(), a, b);

        var c = fake(4, "emb-remote");
        var env2 = base(); embedding(env2, a, c, 3);
        var d = assertThrows(IllegalStateException.class, () -> start(env2), "차원 불일치 거부");
        assertTrue(d.getMessage().contains("dimensions:4")); assertHidden(d.getMessage(), a, c);

        // 연결할 수 없는 서버(down)는 경고만 남기고 시작한다.
        int unused; try (var socket = new ServerSocket(0)) { unused = socket.getLocalPort(); }
        var env3 = base(); embedding(env3, a, a, 3); env3.put("NASQUIRE_ML_EMBEDDING_SERVER_B_URL", "http://127.0.0.1:" + unused + "/v1");
        var server = start(env3);
        assertEquals(200, post(server, "/v1/embeddings", "{\"input\":\"x\"}").statusCode());
    }

    @Test void configurationKeepsKeysOffTheCommandLineAndRequiresHttpsPerFeature() throws Exception {
        var env = base();
        env.put("NASQUIRE_ML_RERANKER", "remote"); env.put("NASQUIRE_ML_RERANKER_MODEL", "rerank-remote");
        env.put("NASQUIRE_ML_RERANKER_SERVERS", "gpu1"); env.put("NASQUIRE_ML_RERANKER_SERVER_GPU1_URL", "https://rerank.example.com/v1");
        assertEquals("remote", MlConfig.load(new String[0], env).reranker());
        for (String arg : List.of("--nasquire.ml.reranker-server.gpu1.key=" + KEY_A, "--nasquire.ml.reranker.server.gpu1.key=" + KEY_A, "--nasquire.ml.token=" + TOKEN)) {
            var e = assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[] {arg}, env), arg);
            assertFalse(e.getMessage().contains(KEY_A));
        }
        // 키 파일 경로는 명령행에서도 받는다.
        Path keyFile = Files.writeString(temp.resolve("rerank-key"), KEY_A);
        var withFile = MlConfig.load(new String[] {"--nasquire.ml.reranker-server.gpu1.key-file=" + keyFile}, env);
        assertEquals(KEY_A, withFile.rerankerRemote().servers().getFirst().key());
        assertFalse(withFile.toString().contains(KEY_A)); assertFalse(withFile.toString().contains("example.com"));
        env.put("NASQUIRE_ML_RERANKER_SERVER_GPU1_KEY", KEY_B);
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[] {"--nasquire.ml.reranker-server.gpu1.key-file=" + keyFile}, env), "키와 키 파일 동시 거부");
        env.remove("NASQUIRE_ML_RERANKER_SERVER_GPU1_KEY");

        // 비루프백 평문 http는 기능별 명시 허용 때만. 다른 기능의 허용은 적용되지 않는다.
        env.put("NASQUIRE_ML_RERANKER_SERVER_GPU1_URL", "http://rerank.example.com/v1");
        var insecure = assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], env));
        assertFalse(insecure.getMessage().contains("example.com"));
        env.put("NASQUIRE_ML_EMBEDDING_ALLOW_INSECURE_HTTP", "true");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], env));
        env.put("NASQUIRE_ML_RERANKER_ALLOW_INSECURE_HTTP", "true");
        assertEquals("remote", MlConfig.load(new String[0], env).reranker());

        // 설정 파일 형식(점·하이픈 표기)도 같다.
        Path file = Files.writeString(temp.resolve("ml.properties"), String.join("\n",
                "nasquire.ml.token=" + TOKEN, "nasquire.ml.embedding=remote", "nasquire.ml.embedding-model=emb-remote",
                "nasquire.ml.embedding-dimensions=1024", "nasquire.ml.embedding-servers=one, two",
                "nasquire.ml.embedding-server.one.url=https://embed1.example.com/v1", "nasquire.ml.embedding-server.one.key=" + KEY_A,
                "nasquire.ml.embedding-server.two.url=https://embed2.example.com/v1", "nasquire.ml.embedding-server.two.timeout-seconds=10",
                "nasquire.ml.docling=remote", "nasquire.ml.docling-servers=d", "nasquire.ml.docling-server.d.url=https://docling.example.com", ""));
        var fromFile = MlConfig.load(new String[] {"--nasquire.ml.config=" + file}, Map.of("NASQUIRE_DATA", temp.toString()));
        assertEquals(List.of("one", "two"), fromFile.embeddingRemote().servers().stream().map(s -> s.name()).toList());
        assertEquals(10, fromFile.embeddingRemote().servers().get(1).timeoutSeconds());
        assertEquals(1024, fromFile.embeddingRemote().dimensions());
        assertEquals("remote", fromFile.docling());
        assertFalse(fromFile.toString().contains(KEY_A));

        // 원격에는 모델 이름·차원·서버 목록이 필요하다.
        var missing = base(); missing.put("NASQUIRE_ML_EMBEDDING", "remote"); missing.put("NASQUIRE_ML_EMBEDDING_MODEL", "m");
        missing.put("NASQUIRE_ML_EMBEDDING_SERVERS", "a"); missing.put("NASQUIRE_ML_EMBEDDING_SERVER_A_URL", "https://embed.example.com/v1");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], missing), "차원 필요");
        missing.put("NASQUIRE_ML_EMBEDDING_DIMENSIONS", "3"); missing.remove("NASQUIRE_ML_EMBEDDING_SERVER_A_URL");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], missing), "주소 필요");
        missing.put("NASQUIRE_ML_EMBEDDING_SERVER_A_URL", "https://embed.example.com/v1"); missing.put("NASQUIRE_ML_EMBEDDING", "gpu");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], missing));
        missing.put("NASQUIRE_ML_EMBEDDING", "remote");
        var tesseract = new HashMap<>(missing); tesseract.put("NASQUIRE_ML_OCR", "tesseract"); tesseract.put("NASQUIRE_ML_OCR_TIMEOUT_SECONDS", "120");
        assertThrows(IllegalArgumentException.class, () -> MlConfig.load(new String[0], tesseract), "로컬 Tesseract는 60초 상한");
    }

    @Test void rerankResponsesFromDifferentProvidersBecomeOneFormat() {
        var infinity = JsonParser.parseString("{\"results\":[{\"index\":2,\"relevance_score\":0.8,\"document\":\"c\"},{\"index\":0,\"relevance_score\":0.1}]}");
        assertArrayEquals(new double[] {0.1, Double.NaN, 0.8}, MlGateway.parse(infinity, 3));
        var tei = JsonParser.parseString("[{\"index\":1,\"score\":0.5},{\"index\":0,\"score\":0.2}]");
        assertArrayEquals(new double[] {0.2, 0.5}, MlGateway.parse(tei, 2));
        var data = JsonParser.parseString("{\"data\":[{\"index\":0,\"relevance_score\":1}]}");
        assertArrayEquals(new double[] {1}, MlGateway.parse(data, 1));
        for (String bad : List.of("{\"results\":[]}", "{\"results\":[{\"index\":3,\"relevance_score\":1}]}", "{\"results\":[{\"index\":0,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}",
                "{\"results\":[{\"index\":0.5,\"relevance_score\":1}]}", "{\"results\":[{\"index\":0}]}", "{\"other\":1}", "{\"results\":[{\"index\":0,\"relevance_score\":\"NaN\"}]}"))
            assertThrows(RuntimeException.class, () -> MlGateway.parse(JsonParser.parseString(bad), 2), bad);
    }
}

package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 임베딩(OpenAI 호환 /v1/embeddings)·리랭크(/v1/rerank)·OCR(/v1/ocr)만 제공하는 별도 서버.
 * 관리 화면·소스·검색·MCP·DB가 없고, 모든 요청에 Bearer 토큰이 필요하다. 토큰·본문은 로그에 남기지 않는다.
 */
public final class MlServer implements AutoCloseable {
    /** 리랭크 점수 계산. 로컬 ONNX cross-encoder 또는 테스트 대역. */
    public interface Scorer extends AutoCloseable {
        float[] scores(String query, List<String> documents, long deadlineNanos);
        default void close() { }
    }
    static final int MAX_JSON = 1024 * 1024, MAX_IMAGE = 16 * 1024 * 1024, MAX_INPUTS = 64, MAX_TEXT = 8192;
    private static final System.Logger LOG = System.getLogger(MlServer.class.getName());
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] tokenHash;
    private final Providers.EmbeddingProvider embedding;
    private final Scorer scorer;
    private final Providers.OcrProvider ocr;
    private final String embeddingModel, rerankerModel;
    private final int rerankSeconds;
    // 기능별 동시 추론 1개. 짧게 기다린 뒤 503으로 거절하여 대기열이 메모리를 키우지 않게 한다.
    private final Semaphore embeddingSlot = new Semaphore(1), rerankSlot = new Semaphore(1), ocrSlot = new Semaphore(1);

    public MlServer(InetSocketAddress address, byte[] tokenHash, Providers.EmbeddingProvider embedding, String embeddingModel,
            Scorer scorer, String rerankerModel, int rerankSeconds, Providers.OcrProvider ocr, int threads) throws IOException {
        if (tokenHash == null || tokenHash.length != 32) throw new IllegalArgumentException("ML 토큰 필요");
        if (embedding == null && scorer == null && ocr == null) throw new IllegalArgumentException("ML 기능 없음");
        this.tokenHash = tokenHash.clone(); this.embedding = embedding; this.embeddingModel = embeddingModel;
        this.scorer = scorer; this.rerankerModel = rerankerModel; this.rerankSeconds = rerankSeconds; this.ocr = ocr;
        executor = Executors.newFixedThreadPool(threads, r -> { var t = new Thread(r, "nasquire-ml"); t.setDaemon(false); return t; });
        server = HttpServer.create(address, 32);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }

    public static MlServer start(MlConfig c) throws Exception {
        // 느린 요청·응답이 처리 스레드를 오래 점유하지 않게 한다(첫 HttpServer 생성 전에만 적용된다).
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) System.setProperty("sun.net.httpserver.maxReqTime", "60");
        if (System.getProperty("sun.net.httpserver.maxRspTime") == null) System.setProperty("sun.net.httpserver.maxRspTime", "300");
        Files.createDirectories(c.data());
        Path data = c.data().toRealPath();
        Providers.EmbeddingProvider embedding = null; Scorer scorer = null; Providers.OcrProvider ocr = null;
        try {
            if (c.embedding().equals("builtin")) {
                embedding = new OnnxEmbeddingProvider(c.embeddingModelPath(), c.embeddingTokenizerPath(),
                        c.download() ? MlConfig.E5_MODEL_URL : "", c.download() ? MlConfig.E5_TOKENIZER_URL : "",
                        MlConfig.E5_MODEL_SHA256, MlConfig.E5_TOKENIZER_SHA256, data);
                embedding.embed("warm-up"); // 시작 때 모델·해시를 확인한다.
            }
            if (c.reranker().equals("builtin")) {
                var local = new OnnxRerankerProvider(c.rerankerModelPath(), c.rerankerTokenizerPath(), c.download(), data, OnnxRerankerProvider.MAX_DOCUMENTS, c.rerankTimeoutSeconds());
                scorer = new Scorer() {
                    public float[] scores(String q, List<String> d, long deadline) { return local.scores(q, d, deadline); }
                    public void close() { local.close(); }
                };
                local.scores("warm-up", List.of("warm-up"), local.deadline());
            }
            if (c.ocr().equals("tesseract")) ocr = new TesseractOcrProvider(data, c.ocrTimeoutSeconds(), c.ocrMemoryMiB());
            var server = new MlServer(new InetSocketAddress(c.address(), c.port()), c.tokenHash(), embedding, c.embeddingModel(),
                    scorer, c.rerankerModel(), c.rerankTimeoutSeconds(), ocr, c.threads());
            LOG.log(System.Logger.Level.INFO, "Nasquire ML 서버 시작: " + c);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "nasquire-ml-shutdown"));
            return server;
        } catch (Exception | Error e) {
            if (embedding != null) embedding.close();
            if (scorer != null) scorer.close();
            throw e;
        }
    }

    static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 같은 길이 해시를 상수 시간 비교한다. 토큰 길이·내용에 따라 응답 시간이 달라지지 않게 한다. */
    boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 600) return false;
        return MessageDigest.isEqual(sha256(header.substring(7)), tokenHash);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff");
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                headers.set("WWW-Authenticate", "Bearer"); send(exchange, 401, error("인증 필요 / Unauthorized")); return;
            }
            String path = exchange.getRequestURI().getRawPath(), method = exchange.getRequestMethod();
            if (exchange.getRequestURI().getRawQuery() != null) { send(exchange, 400, error("쿼리 문자열 미지원")); return; }
            switch (path) {
                case "/v1/embeddings", "/v1/rerank", "/v1/ocr" -> {
                    if (!method.equals("POST")) { headers.set("Allow", "POST"); send(exchange, 405, error("POST만 허용")); return; }
                }
                case "/v1/models" -> {
                    if (!method.equals("GET")) { headers.set("Allow", "GET"); send(exchange, 405, error("GET만 허용")); return; }
                    send(exchange, 200, models()); return;
                }
                default -> { send(exchange, 404, error("없는 경로 / Not found")); return; }
            }
            try {
                switch (path) {
                    case "/v1/embeddings" -> send(exchange, 200, embeddings(exchange));
                    case "/v1/rerank" -> send(exchange, 200, rerank(exchange));
                    default -> send(exchange, 200, ocr(exchange));
                }
            } catch (Unavailable e) { send(exchange, 404, error("비활성 기능 / Feature disabled")); }
            catch (Busy e) { headers.set("Retry-After", "1"); send(exchange, 503, error("처리 중 / Busy")); }
            catch (TooLarge e) { send(exchange, 413, error("요청 크기 상한 초과")); }
            catch (Failed e) { send(exchange, 500, error("모델 처리 실패 / Inference failed")); }
            catch (RuntimeException | IOException e) { send(exchange, 400, error("입력 오류 / Invalid input")); }
        }
    }
    private static final class Unavailable extends RuntimeException { Unavailable() { super(null, null, false, false); } }
    private static final class Busy extends RuntimeException { Busy() { super(null, null, false, false); } }
    private static final class Failed extends RuntimeException { Failed() { super(null, null, false, false); } }
    /** 공급자 실패는 입력 오류와 구분하여 500으로 응답한다. 원인 메시지는 응답에 넣지 않는다. */
    private static <T> T infer(java.util.function.Supplier<T> work) {
        try { return work.get(); } catch (IllegalArgumentException e) { throw e; } catch (RuntimeException e) { throw new Failed(); }
    }
    private static final class TooLarge extends RuntimeException { TooLarge() { super(null, null, false, false); } }

    private JsonObject models() {
        var list = new JsonArray();
        for (String id : new String[] {embedding == null ? null : embeddingModel, scorer == null ? null : rerankerModel, ocr == null ? null : "tesseract"}) {
            if (id == null) continue;
            var o = new JsonObject(); o.addProperty("id", id); o.addProperty("object", "model"); list.add(o);
        }
        var result = new JsonObject(); result.addProperty("object", "list"); result.add("data", list); return result;
    }
    private static byte[] body(HttpExchange exchange, int limit) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && Long.parseLong(length) > limit) throw new TooLarge();
        try (var in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new TooLarge();
            return bytes;
        }
    }
    private static JsonObject json(HttpExchange exchange) throws IOException {
        var type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new IllegalArgumentException("JSON 필요");
        return JsonParser.parseString(new String(body(exchange, MAX_JSON), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static void model(JsonObject request, String expected) {
        if (request.has("model") && !request.get("model").getAsString().equals(expected)) throw new IllegalArgumentException("모델 이름 불일치");
    }
    private static void acquire(Semaphore slot) {
        try { if (!slot.tryAcquire(10, TimeUnit.SECONDS)) throw new Busy(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Busy(); }
    }

    private JsonObject embeddings(HttpExchange exchange) throws IOException {
        if (embedding == null) throw new Unavailable();
        var request = json(exchange); model(request, embeddingModel);
        if (request.has("encoding_format") && !request.get("encoding_format").getAsString().equals("float")) throw new IllegalArgumentException("float만 지원");
        var input = request.get("input"); var texts = new ArrayList<String>();
        if (input.isJsonArray()) for (var e : input.getAsJsonArray()) texts.add(e.getAsString());
        else texts.add(input.getAsString());
        if (texts.isEmpty() || texts.size() > MAX_INPUTS || texts.stream().anyMatch(t -> t.isEmpty() || t.length() > MAX_TEXT)) throw new IllegalArgumentException("입력 수·길이 오류");
        // OpenAI 형식에는 질의/문서 구분이 없다. 확장 필드 input_type=query만 질의 접두사로 처리한다.
        boolean query = request.has("input_type") && request.get("input_type").getAsString().equals("query");
        acquire(embeddingSlot);
        var data = new JsonArray();
        try {
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                float[] vector = infer(() -> query ? embedding.embedQuery(text) : embedding.embed(text));
                var values = new JsonArray(); for (float f : vector) values.add(f);
                var item = new JsonObject(); item.addProperty("object", "embedding"); item.addProperty("index", i); item.add("embedding", values);
                data.add(item);
            }
        } finally { embeddingSlot.release(); }
        var usage = new JsonObject(); usage.addProperty("prompt_tokens", 0); usage.addProperty("total_tokens", 0);
        var result = new JsonObject(); result.addProperty("object", "list"); result.add("data", data);
        result.addProperty("model", embeddingModel); result.add("usage", usage);
        return result;
    }
    private JsonObject rerank(HttpExchange exchange) throws IOException {
        if (scorer == null) throw new Unavailable();
        var request = json(exchange); model(request, rerankerModel);
        String query = request.get("query").getAsString(); var documents = new ArrayList<String>();
        for (var e : request.getAsJsonArray("documents")) documents.add(e.isJsonObject() ? e.getAsJsonObject().get("text").getAsString() : e.getAsString());
        if (query.isBlank() || query.length() > 4096 || documents.isEmpty() || documents.size() > OnnxRerankerProvider.MAX_DOCUMENTS
                || documents.stream().anyMatch(d -> d.length() > OnnxRerankerProvider.MAX_CHARACTERS)) throw new IllegalArgumentException("리랭크 입력 오류");
        int top = request.has("top_n") ? request.get("top_n").getAsInt() : documents.size();
        if (top < 1) throw new IllegalArgumentException("top_n 오류");
        acquire(rerankSlot);
        float[] scores;
        try { long deadline = System.nanoTime() + rerankSeconds * 1_000_000_000L; scores = infer(() -> scorer.scores(query, documents, deadline)); }
        finally { rerankSlot.release(); }
        if (scores.length != documents.size()) throw new IllegalStateException("점수 수 오류");
        var order = new ArrayList<Integer>(); for (int i = 0; i < scores.length; i++) order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed().thenComparing(i -> i));
        var results = new JsonArray();
        for (int i : order.subList(0, Math.min(top, order.size()))) {
            var r = new JsonObject(); r.addProperty("index", i); r.addProperty("relevance_score", scores[i]); results.add(r);
        }
        var result = new JsonObject(); result.addProperty("model", rerankerModel); result.add("results", results); return result;
    }
    private JsonObject ocr(HttpExchange exchange) throws IOException {
        if (ocr == null) throw new Unavailable();
        byte[] image = body(exchange, MAX_IMAGE);
        acquire(ocrSlot);
        String text;
        try {
            text = infer(() -> {
                try { return OcrTextExtractor.recognizeImage(ocr, image); } catch (IOException e) { throw new IllegalArgumentException("이미지 오류"); }
            });
        }
        finally { ocrSlot.release(); }
        var result = new JsonObject(); result.addProperty("text", text); return result;
    }
    private static JsonObject error(String message) { var o = new JsonObject(); o.addProperty("error", message); return o; }
    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    public void close() {
        server.stop(1); executor.shutdownNow();
        if (embedding != null) embedding.close();
        if (scorer != null) scorer.close();
    }
}

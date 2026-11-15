package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.ocr.IsolatedExtractor;
import com.bluesounds.nasquire.core.ocr.OcrTextExtractor;
import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.bluesounds.nasquire.core.remote.*;
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
 * 임베딩(OpenAI 호환 /v1/embeddings)·리랭크(/v1/rerank)·OCR(/v1/ocr)·문서 추출(/v1/extract, Open WebUI external 규격 PUT /process)·
 * 모델(/v1/models)·상태(/v1/status)만 제공하는 별도 서버. OCR·문서 추출은 공통 파이프라인({@link MlPipeline})을 탄다.
 * 관리 화면·소스·검색·MCP·DB가 없고, 모든 요청에 Bearer 토큰이 필요하다. 토큰·본문은 로그에 남기지 않는다.
 * 기능마다 내장(builtin·tesseract) 또는 원격 게이트웨이({@link MlGateway})를 쓴다. 원격이면 처리 서버 이름·페일오버 여부를
 * X-Nasquire-Embedding-Server·X-Nasquire-Rerank-Server·X-Nasquire-Ocr-Server·X-Nasquire-Docling-Server·X-Nasquire-Failover 헤더로 알린다.
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
    private final OcrTextExtractor ocr;
    private final String embeddingModel, rerankerModel, ocrEngine;
    private final int rerankSeconds;
    private final MlGateway gateway;
    // 기능별 동시 처리 상한(내장 1개, 원격 임베딩·리랭크는 작업자 수). 기다리지 않고 503으로 거절하여 대기열이 메모리를 키우지 않게 한다.
    // OCR·문서 추출은 파이프라인의 문서 슬롯 1개를 함께 쓴다.
    private final Semaphore embeddingSlot, rerankSlot;
    private final MlPipeline pipeline;
    /** 공개 생성자(시험용)가 만든 임시 데이터 폴더. 닫을 때 지운다. */
    private final Path ownedData;

    public MlServer(InetSocketAddress address, byte[] tokenHash, Providers.EmbeddingProvider embedding, String embeddingModel,
            Scorer scorer, String rerankerModel, int rerankSeconds, OcrTextExtractor ocr, int threads) throws IOException {
        this(address, tokenHash, embedding, embeddingModel, scorer, rerankerModel, rerankSeconds, ocr, null, MlGateway.NONE, threads,
                Files.createTempDirectory("nasquire-ml-"), LayoutCheck.Thresholds.DEFAULT.maxPages(), true, MlConfig.DEFAULT_DOCUMENT_TIMEOUT_SECONDS, null);
    }
    /**
     * 원격 기능을 포함한 생성자. gateway의 원격 임베딩·리랭커가 있으면 같은 기능의 내장 공급자는 null이어야 한다.
     * 원격 OCR은 ocr(core OcrTextExtractor + RemoteOcrProvider)로 넘기고 ocrEngine에 엔진 이름을 준다(null이면 tesseract).
     * documentTimeoutSeconds는 문서 처리 요청 하나의 전체 시간 상한, isolated는 격리 추출(null이면 기본, 시험은 멈추는 자식)이다.
     */
    MlServer(InetSocketAddress address, byte[] tokenHash, Providers.EmbeddingProvider embedding, String embeddingModel,
            Scorer scorer, String rerankerModel, int rerankSeconds, OcrTextExtractor ocr, String ocrEngine, MlGateway gateway, int threads,
            Path data, int doclingMaxPages, boolean ownsData, int documentTimeoutSeconds, IsolatedExtractor isolated) throws IOException {
        if (tokenHash == null || tokenHash.length != 32) throw new IllegalArgumentException("ML 토큰 필요");
        if (gateway.embedding() != null && embedding != null || gateway.rerank() != null && scorer != null) throw new IllegalArgumentException("ML 기능 중복");
        if (embedding == null && scorer == null && ocr == null && gateway.embedding() == null && gateway.rerank() == null && gateway.docling() == null)
            throw new IllegalArgumentException("ML 기능 없음");
        this.tokenHash = tokenHash.clone(); this.embedding = embedding; this.gateway = gateway;
        this.embeddingModel = gateway.embedding() != null ? gateway.embedding().model() : embeddingModel;
        this.scorer = scorer; this.rerankerModel = gateway.rerank() != null ? gateway.rerank().model() : rerankerModel;
        this.rerankSeconds = rerankSeconds; this.ocr = ocr; this.ocrEngine = ocrEngine == null ? "tesseract" : ocrEngine;
        embeddingSlot = new Semaphore(gateway.embedding() != null ? threads : 1);
        rerankSlot = new Semaphore(gateway.rerank() != null ? threads : 1);
        pipeline = new MlPipeline(data, ocr, gateway.docling(), doclingMaxPages, documentTimeoutSeconds, isolated);
        ownedData = ownsData ? data : null;
        // 대기열 없는 고정 작업자. 모두 사용 중이면 즉시 503으로 거절하여 대기 요청이 메모리를 키우지 않게 한다.
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> { var t = new Thread(r, "nasquire-ml"); t.setDaemon(false); return t; });
        server = HttpServer.create(address, 32);
        server.createContext("/", this::admit);
        // 수신 스레드에서 인증만 검사한다(해시 1회). 인증 실패는 작업자를 쓰지 않고 바로 401로 끝낸다.
        server.setExecutor(null);
        server.start();
    }
    private void admit(HttpExchange exchange) throws IOException {
        if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
            try (exchange) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, error("인증 필요 / Unauthorized"));
            }
            return;
        }
        try { executor.execute(() -> { try { handle(exchange); } catch (IOException ignored) { exchange.close(); } }); }
        catch (RejectedExecutionException e) {
            try (exchange) { exchange.getResponseHeaders().set("Retry-After", "1"); send(exchange, 503, error("처리 중 / Busy")); }
        }
    }
    public int port() { return server.getAddress().getPort(); }

    public static MlServer start(MlConfig c) throws Exception {
        // 느린 요청·응답이 처리 스레드를 오래 점유하지 않게 한다(첫 HttpServer 생성 전에만 적용된다).
        // 연결 수 상한도 같은 방식이다. 운영자가 JVM 옵션으로 지정한 값은 덮어쓰지 않는다.
        for (String[] limit : new String[][] {{"sun.net.httpserver.maxReqTime", "60"}, {"sun.net.httpserver.maxRspTime", "300"},
                {"jdk.httpserver.maxConnections", "64"}, {"sun.net.httpserver.maxIdleConnections", "16"}})
            if (System.getProperty(limit[0]) == null) System.setProperty(limit[0], limit[1]);
        Files.createDirectories(c.data());
        Path data = c.data().toRealPath();
        Providers.EmbeddingProvider embedding = null; Scorer scorer = null; OcrTextExtractor ocr = null;
        MlGateway gateway = MlGateway.open(c);
        try {
            // 원격 서버마다 모델·차원/엔진/버전을 확인한다. 불일치면 시작을 거부하고 연결 불가는 경고만 남긴다.
            gateway.verify();
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
            if (c.ocr().equals("tesseract")) ocr = new OcrTextExtractor(new TesseractOcrProvider(data, c.ocrTimeoutSeconds(), c.ocrMemoryMiB()), c.ocrMaxPages(), data);
            // 원격 OCR도 이미지 검사·디코딩은 격리 자식 프로세스가 맡고 공급자에는 PNG만 보낸다.
            if (gateway.ocr() != null) ocr = new OcrTextExtractor(gateway.ocr(), c.ocrMaxPages(), data);
            var server = new MlServer(new InetSocketAddress(c.address(), c.port()), c.tokenHash(), embedding, c.embeddingModel(),
                    scorer, c.rerankerModel(), c.rerankTimeoutSeconds(), ocr, gateway.ocrEngine(), gateway, c.threads(),
                    data, c.doclingMaxPages(), false, c.documentTimeoutSeconds(), null);
            LOG.log(System.Logger.Level.INFO, "Nasquire ML 서버 시작: " + c);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "nasquire-ml-shutdown"));
            return server;
        } catch (Exception | Error e) {
            if (embedding != null) embedding.close();
            if (scorer != null) scorer.close();
            gateway.close();
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
            String query = exchange.getRequestURI().getRawQuery();
            // 쿼리는 /v1/extract의 layout만 받는다.
            if (query != null && !path.equals("/v1/extract")) { send(exchange, 400, error("쿼리 문자열 미지원")); return; }
            switch (path) {
                case "/v1/embeddings", "/v1/rerank", "/v1/ocr", "/v1/extract" -> {
                    if (!method.equals("POST")) { headers.set("Allow", "POST"); send(exchange, 405, error("POST만 허용")); return; }
                }
                case "/process" -> {
                    if (!method.equals("PUT")) { headers.set("Allow", "PUT"); send(exchange, 405, error("PUT만 허용")); return; }
                }
                case "/v1/models", "/v1/status" -> {
                    if (!method.equals("GET")) { headers.set("Allow", "GET"); send(exchange, 405, error("GET만 허용")); return; }
                    send(exchange, 200, path.equals("/v1/models") ? models() : status()); return;
                }
                default -> { send(exchange, 404, error("없는 경로 / Not found")); return; }
            }
            int status = 200; JsonElement result;
            RemoteTrace.begin();
            try {
                result = switch (path) {
                    case "/v1/embeddings" -> embeddings(exchange);
                    case "/v1/rerank" -> rerank(exchange);
                    case "/v1/extract" -> extract(exchange, query);
                    case "/process" -> process(exchange);
                    default -> ocr(exchange);
                };
            } catch (Unavailable e) { status = 404; result = error("비활성 기능 / Feature disabled"); }
            catch (Busy | MlPipeline.Busy e) { headers.set("Retry-After", "1"); status = 503; result = error("처리 중 / Busy"); }
            catch (MlPipeline.Rejected e) { status = e.status; result = error(e.getMessage()); }
            catch (TooLarge e) { status = 413; result = error("요청 크기 상한 초과"); }
            catch (Failed e) { status = 500; result = error("모델 처리 실패 / Inference failed"); }
            catch (RemoteException e) {
                // 원격 공급자의 상태·본문·주소는 전달하지 않는다. 다음 서버로 넘길 수 있는 실패가 모두 소진되면 503, 그 밖은 502.
                if (e.retryable() || e.kind() == RemoteException.Kind.UNAVAILABLE) {
                    headers.set("Retry-After", "1"); status = 503; result = error("원격 서버 응답 없음 / Upstream unavailable");
                } else { status = 502; result = error("원격 처리 실패 / Upstream failed"); }
            }
            catch (IOException e) {
                // 추출기·격리 자식의 상한 위반(ExtractionLimit: 글자·쪽·픽셀·파일 크기)은 413, 손상·암호·렌더링 실패 등은 400.
                // 메시지 원문은 응답에 넣지 않는다.
                boolean limit = ExtractionLimit.find(e) != null;
                status = limit ? 413 : 400; result = error(limit ? "처리 상한 초과 / Limit exceeded" : "입력 오류 / Invalid input");
            }
            catch (RuntimeException e) { status = 400; result = error("입력 오류 / Invalid input"); }
            finally { trace(headers, RemoteTrace.end()); }
            send(exchange, status, result);
        }
    }
    /** 처리 서버 이름(주소 없음)과 페일오버 여부. 1차 검색 응답 헤더와 같은 이름 규칙이다. */
    private static void trace(Headers headers, Map<String, RemoteTrace.Entry> entries) {
        if (entries.isEmpty()) return;
        for (var e : entries.values()) {
            String name = switch (e.feature()) { case "embedding" -> "Embedding"; case "reranker" -> "Rerank"; case "ocr" -> "Ocr"; case "docling" -> "Docling"; default -> null; };
            if (name != null) headers.set("X-Nasquire-" + name + "-Server", e.server());
        }
        headers.set("X-Nasquire-Failover", Boolean.toString(entries.values().stream().anyMatch(RemoteTrace.Entry::failover)));
    }
    private static final class Unavailable extends RuntimeException { Unavailable() { super(null, null, false, false); } }
    private static final class Busy extends RuntimeException { Busy() { super(null, null, false, false); } }
    private static final class Failed extends RuntimeException { Failed() { super(null, null, false, false); } }
    /** 공급자 실패는 입력 오류와 구분하여 500으로 응답한다. 원인 메시지는 응답에 넣지 않는다. */
    private static <T> T infer(java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (IllegalArgumentException | RemoteException | MlPipeline.Rejected | MlPipeline.Busy | UncheckedIOException e) { throw e; }
        catch (RuntimeException e) { throw new Failed(); }
    }
    private static final class TooLarge extends RuntimeException { TooLarge() { super(null, null, false, false); } }

    private String embeddingMode() { return gateway.embedding() != null ? "remote" : embedding != null ? "builtin" : "off"; }
    private String rerankerMode() { return gateway.rerank() != null ? "remote" : scorer != null ? "builtin" : "off"; }
    private String ocrMode() { return ocr == null ? "off" : gateway.ocr() != null ? "remote" : "tesseract"; }
    /**
     * OpenAI 형식 모델 목록(data[].id)에 기능·방식을 덧붙인다. 임베딩은 실제 모델 이름·차원, 리랭커는 모델 이름, OCR은 엔진,
     * Docling은 docling-serve 버전(시작 확인에서 얻은 경우)을 보고한다. 원격 주소·키·서버 이름은 넣지 않는다.
     */
    private JsonObject models() {
        var list = new JsonArray();
        if (!embeddingMode().equals("off")) {
            var o = model(embeddingModel, "embedding", embeddingMode());
            o.addProperty("dimensions", gateway.embedding() != null ? gateway.embedding().dimensions() : embedding.dimensions());
            list.add(o);
        }
        if (!rerankerMode().equals("off")) list.add(model(rerankerModel, "reranker", rerankerMode()));
        if (ocr != null) list.add(model(ocrEngine, "ocr", ocrMode()));
        if (gateway.docling() != null) {
            var o = model("docling-serve", "docling", "remote");
            if (!gateway.doclingVersion().isEmpty()) o.addProperty("version", gateway.doclingVersion());
            list.add(o);
        }
        var result = new JsonObject(); result.addProperty("object", "list"); result.add("data", list); return result;
    }
    private static JsonObject model(String id, String feature, String mode) {
        var o = new JsonObject(); o.addProperty("id", id); o.addProperty("object", "model"); o.addProperty("owned_by", "nasquire");
        o.addProperty("feature", feature); o.addProperty("mode", mode); return o;
    }
    /** 기능별 방식·모델과 원격 서버별 상태(이름·상태·마지막 실패 종류·연속 실패 수). 주소·키는 넣지 않는다. */
    private JsonObject status() {
        var features = new JsonObject();
        var e = feature(embeddingMode(), gateway.embedding() == null ? null : gateway.embedding().pool());
        if (!embeddingMode().equals("off")) {
            e.addProperty("model", embeddingModel);
            e.addProperty("dimensions", gateway.embedding() != null ? gateway.embedding().dimensions() : embedding.dimensions());
        }
        features.add("embedding", e);
        var r = feature(rerankerMode(), gateway.rerank() == null ? null : gateway.rerank().pool());
        if (!rerankerMode().equals("off")) r.addProperty("model", rerankerModel);
        features.add("reranker", r);
        var o = feature(ocrMode(), gateway.ocr() == null ? null : gateway.ocr().pool());
        if (ocr != null) o.addProperty("engine", ocrEngine);
        features.add("ocr", o);
        var d = feature(gateway.docling() == null ? "off" : "remote", gateway.docling() == null ? null : gateway.docling().pool());
        if (gateway.docling() != null && !gateway.doclingVersion().isEmpty()) d.addProperty("version", gateway.doclingVersion());
        if (gateway.docling() != null) {
            // 문서 추출에서 Docling으로 보낸 쪽 누적(변환·실패·건너뜀). 파일 이름·내용은 담지 않는다.
            var stats = pipeline.doclingStats(); var p = new JsonObject();
            p.addProperty("converted", stats.converted()); p.addProperty("failed", stats.failed()); p.addProperty("skipped", stats.skipped());
            d.add("pages", p);
        }
        features.add("docling", d);
        var result = new JsonObject(); result.addProperty("object", "status"); result.add("features", features); return result;
    }
    private static JsonObject feature(String mode, RemotePool pool) {
        var o = new JsonObject(); o.addProperty("mode", mode);
        if (pool != null) {
            o.add("servers", MlGateway.servers(pool));
            if (!pool.disabledReason().isEmpty()) o.addProperty("disabledReason", pool.disabledReason());
        }
        return o;
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
        if (!slot.tryAcquire()) throw new Busy();
    }

    private JsonObject embeddings(HttpExchange exchange) throws IOException {
        var remote = gateway.embedding();
        if (embedding == null && remote == null) throw new Unavailable();
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
        var usage = new JsonObject(); usage.addProperty("prompt_tokens", 0); usage.addProperty("total_tokens", 0);
        try {
            if (remote != null) {
                // 원격은 입력을 그대로 중계한다(input_type은 OpenAI 형식이 아니므로 보내지 않는다). 모델 이름은 설정된 원격 모델이다.
                var embedded = remote.embed(texts).value();
                for (int i = 0; i < embedded.vectors().size(); i++) data.add(item(i, embedded.vectors().get(i)));
                usage = embedded.usage();
            } else {
                for (int i = 0; i < texts.size(); i++) {
                    String text = texts.get(i);
                    float[] vector = infer(() -> query ? embedding.embedQuery(text) : embedding.embed(text));
                    var values = new JsonArray(); for (float f : vector) values.add(f);
                    data.add(item(i, values));
                }
            }
        } finally { embeddingSlot.release(); }
        var result = new JsonObject(); result.addProperty("object", "list"); result.add("data", data);
        result.addProperty("model", embeddingModel); result.add("usage", usage);
        return result;
    }
    private static JsonObject item(int index, JsonArray values) {
        var item = new JsonObject(); item.addProperty("object", "embedding"); item.addProperty("index", index); item.add("embedding", values);
        return item;
    }
    private JsonObject rerank(HttpExchange exchange) throws IOException {
        var remote = gateway.rerank();
        if (scorer == null && remote == null) throw new Unavailable();
        var request = json(exchange); model(request, rerankerModel);
        String query = request.get("query").getAsString(); var documents = new ArrayList<String>();
        for (var e : request.getAsJsonArray("documents")) documents.add(e.isJsonObject() ? e.getAsJsonObject().get("text").getAsString() : e.getAsString());
        if (query.isBlank() || query.length() > 4096 || documents.isEmpty() || documents.size() > OnnxRerankerProvider.MAX_DOCUMENTS
                || documents.stream().anyMatch(d -> d.length() > OnnxRerankerProvider.MAX_CHARACTERS)) throw new IllegalArgumentException("리랭크 입력 오류");
        int top = request.has("top_n") ? request.get("top_n").getAsInt() : documents.size();
        if (top < 1) throw new IllegalArgumentException("top_n 오류");
        acquire(rerankSlot);
        double[] computed;
        try {
            if (remote != null) computed = remote.scores(query, documents).value();
            else {
                long deadline = System.nanoTime() + rerankSeconds * 1_000_000_000L;
                float[] local = infer(() -> scorer.scores(query, documents, deadline));
                if (local.length != documents.size()) throw new Failed();
                computed = new double[local.length]; for (int i = 0; i < local.length; i++) computed[i] = local[i];
            }
        }
        finally { rerankSlot.release(); }
        double[] scores = computed;
        // 원격 공급자가 일부 문서만 돌려주면(NaN) 그 문서는 결과에서 뺀다. 형식은 내장과 같은 results[{index, relevance_score}]다.
        var order = new ArrayList<Integer>(); for (int i = 0; i < scores.length; i++) if (!Double.isNaN(scores[i])) order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed().thenComparing(i -> i));
        var results = new JsonArray();
        for (int i : order.subList(0, Math.min(top, order.size()))) {
            var r = new JsonObject(); r.addProperty("index", i); r.addProperty("relevance_score", remote != null ? (Number)scores[i] : (Number)(float)scores[i]); results.add(r);
        }
        var result = new JsonObject(); result.addProperty("model", rerankerModel); result.add("results", results); return result;
    }
    /** 기존 /v1/ocr(이미지 → {"text"}) 호환. 내부는 파이프라인의 이미지 경로(같은 슬롯·상한·격리 자식)를 탄다. */
    private JsonObject ocr(HttpExchange exchange) throws IOException {
        if (ocr == null) throw new Unavailable();
        // 슬롯을 먼저 잡아 포화 시 본문을 받기 전에 바로 503으로 끝낸다. 본문 수신 실패도 finally에서 슬롯을 돌려준다.
        pipeline.acquire();
        MlPipeline.Result extracted;
        try {
            byte[] image = body(exchange, MAX_IMAGE);
            extracted = infer(() -> {
                try { return pipeline.image(image); }
                catch (ExtractionLimit e) { throw new UncheckedIOException(e); }
                catch (IOException e) { throw new IllegalArgumentException("이미지 오류"); }
            });
        }
        catch (UncheckedIOException e) { throw e.getCause(); }
        finally { pipeline.release(); }
        var result = new JsonObject(); result.addProperty("text", extracted.pages().getFirst().text()); return result;
    }
    /** 본문을 임시 파일로 받아 파이프라인으로 추출한다. 형식은 X-Filename 확장자 → Content-Type 순으로 정한다. */
    private MlPipeline.Result document(HttpExchange exchange, String filename, MlPipeline.Layout layout) throws IOException {
        String type = MlPipeline.type(filename, exchange.getRequestHeaders().getFirst("Content-Type"));
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        long declared;
        try { declared = length == null ? -1 : Long.parseLong(length.trim()); } catch (NumberFormatException e) { throw new MlPipeline.Rejected(400, "Content-Length 오류"); }
        // 슬롯을 먼저 잡아 포화 시 본문을 받기 전에 바로 503으로 끝낸다.
        pipeline.acquire();
        Path file = null;
        try {
            file = pipeline.receive(exchange.getRequestBody(), declared, MlPipeline.limit(type));
            Path received = file;
            return infer(() -> {
                try { return pipeline.extract(received, type, layout); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (UncheckedIOException e) { throw e.getCause(); }
        finally {
            pipeline.release();
            if (file != null) Files.deleteIfExists(file);
        }
    }
    /** layout 쿼리 하나만 받는다(auto|off|force, 없으면 auto). */
    static MlPipeline.Layout layout(String query) {
        if (query == null || query.isEmpty()) return MlPipeline.Layout.AUTO;
        if (!query.startsWith("layout=") || query.indexOf('&') >= 0) throw new MlPipeline.Rejected(400, "쿼리는 layout만 지원");
        return MlPipeline.Layout.parse(query.substring("layout=".length()));
    }
    /** POST /v1/extract: {pages:[{page, text, source}], metadata}. */
    private JsonObject extract(HttpExchange exchange, String query) throws IOException {
        var layout = layout(query);
        String filename = MlPipeline.filename(exchange.getRequestHeaders().getFirst("X-Filename"));
        var extracted = document(exchange, filename, layout);
        var pages = new JsonArray();
        for (var p : extracted.pages()) {
            var o = new JsonObject(); o.addProperty("page", p.page()); o.addProperty("text", p.text()); o.addProperty("source", p.source()); pages.add(o);
        }
        var metadata = extracted.metadata();
        if (filename != null) metadata.addProperty("filename", filename);
        var result = new JsonObject(); result.add("pages", pages); result.add("metadata", metadata); return result;
    }
    /**
     * Open WebUI external 문서 추출 규격 PUT /process(layout=auto). 응답은 쪽별 [{page_content, metadata}]이며 빈 쪽은 뺀다.
     * metadata.page는 Open WebUI·LangChain PDF 로더 관례대로 0부터(인용 화면이 +1 해서 표시), page_number는 1부터다.
     */
    private JsonArray process(HttpExchange exchange) throws IOException {
        String filename = MlPipeline.filename(exchange.getRequestHeaders().getFirst("X-Filename"));
        var extracted = document(exchange, filename, MlPipeline.Layout.AUTO);
        boolean paged = MlPipeline.PDF.contains(extracted.type()) || MlPipeline.IMAGES.contains(extracted.type());
        var list = new JsonArray();
        for (var p : extracted.pages()) {
            if (p.text().isBlank()) continue;
            var metadata = new JsonObject();
            metadata.addProperty("source", filename != null ? filename : "document." + extracted.type());
            if (paged) { metadata.addProperty("page", p.page() - 1); metadata.addProperty("page_number", p.page()); }
            else if (extracted.pages().size() > 1) metadata.addProperty("part", p.page());
            metadata.addProperty("total_pages", extracted.pages().size());
            metadata.addProperty("extraction", p.source());
            metadata.addProperty("type", extracted.type());
            metadata.addProperty("docling", extracted.docling());
            var o = new JsonObject(); o.addProperty("page_content", p.text()); o.add("metadata", metadata); list.add(o);
        }
        return list;
    }
    private static JsonObject error(String message) { var o = new JsonObject(); o.addProperty("error", message); return o; }
    private static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        // 닫기 전에 보낸다. 닫기는 받지 않은 요청 본문을 먼저 비우므로(포화 503 등) 응답이 그 뒤로 밀리지 않게 한다.
        exchange.getResponseBody().flush();
    }
    public void close() {
        server.stop(1); executor.shutdownNow();
        if (embedding != null) embedding.close();
        if (scorer != null) scorer.close();
        gateway.close();
        if (ownedData != null) {
            try { OcrScratch.deleteTree(ownedData); } catch (IOException ignored) { }
        }
    }
}

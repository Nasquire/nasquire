package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.remote.*;
import com.google.gson.*;
import java.time.Duration;
import java.util.*;

/**
 * ML 모드 원격 게이트웨이. 클라이언트는 ML 서버 주소·토큰만 알고, 공급자 주소·키는 이 서버 밖으로 나가지 않는다
 * (응답·오류·로그·/v1/models·상태에는 서버 이름만). 서버 묶음은 core {@link RemotePool}(round_robin·페일오버·서킷)을 그대로 쓴다.
 * 임베딩은 OpenAI 호환 /embeddings를 설정된 원격 모델 이름으로 중계하고, 리랭크는 공급자 응답(Cohere·Infinity·Jina의
 * results[{index, relevance_score}], TEI의 [{index, score}])을 ML 서버 /v1/rerank 형식으로 통일한다.
 * OCR은 core {@link RemoteOcrProvider}, Docling은 {@link RemoteDoclingClient}를 쓴다(Docling은 연결·상태·버전 보고까지).
 */
final class MlGateway implements AutoCloseable {
    static final int MAX_DIMENSIONS = 8192;
    static final int MAX_EMBEDDING_RESPONSE = 8 * 1024 * 1024, MAX_RERANK_RESPONSE = 4 * 1024 * 1024;
    /** 시작 확인 요청 하나의 상한. 서버 시간 상한과 비교해 작은 값을 쓴다. */
    static final int CHECK_SECONDS = 60;
    private static final System.Logger LOG = System.getLogger(MlGateway.class.getName());
    static final MlGateway NONE = new MlGateway(null, null, null, null, null);

    /** 원격 임베딩 결과. vectors는 공급자 값을 그대로(검사만) 둔 숫자 배열, usage는 정수 사용량 필드만. */
    record Embedded(List<JsonArray> vectors, JsonObject usage) { }

    record Embedding(RemotePool pool, String model, int dimensions, Duration timeout) {
        RemotePool.Result<Embedded> embed(List<String> texts) {
            return pool.execute(timeout, (http, server, limit) -> request(http, server, limit, texts, dimensions));
        }
        private Embedded request(RemoteHttp http, RemoteServer server, Duration limit, List<String> texts, int expected) {
            var body = new JsonObject(); body.addProperty("model", model); body.addProperty("encoding_format", "float");
            var input = new JsonArray(); texts.forEach(input::add); body.add("input", input);
            var response = http.post(server, "/embeddings", body, limit, MAX_EMBEDDING_RESPONSE).getAsJsonObject();
            var data = response.getAsJsonArray("data");
            if (data == null || data.size() != texts.size()) throw new IllegalStateException("임베딩 응답 수 오류");
            var vectors = new JsonArray[texts.size()];
            for (var entry : data) {
                var item = entry.getAsJsonObject(); double raw = item.get("index").getAsDouble(); int i = (int)raw;
                if (raw != i || i < 0 || i >= vectors.length || vectors[i] != null) throw new IllegalStateException("임베딩 순서 오류");
                var values = item.getAsJsonArray("embedding");
                if (values == null || values.isEmpty() || values.size() > MAX_DIMENSIONS || expected > 0 && values.size() != expected)
                    throw new IllegalStateException("임베딩 차원 불일치");
                for (var value : values)
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
                        throw new IllegalStateException("임베딩 값 오류");
                vectors[i] = values;
            }
            var usage = new JsonObject();
            for (String field : new String[] {"prompt_tokens", "total_tokens"}) {
                long tokens = 0;
                if (response.get("usage") instanceof JsonObject u && u.get(field) instanceof JsonPrimitive p && p.isNumber()) tokens = Math.max(0, p.getAsLong());
                usage.addProperty(field, tokens);
            }
            return new Embedded(Arrays.asList(vectors), usage);
        }
        /** 모델 목록(지원 시)과 시험 임베딩 1회의 차원을 설정값과 비교한다. */
        RemoteCheck verify(String server, Duration limit) {
            try {
                var models = pool.probe(server, limit, (http, s, t) -> http.models(s, t));
                if (models.isPresent() && !models.get().contains(model)) return new RemoteCheck(server, "mismatch", "model");
                int actual = pool.probe(server, limit, (http, s, t) -> request(http, s, t, List.of("연결 시험 / connection test"), 0)).vectors().getFirst().size();
                return new RemoteCheck(server, actual == dimensions ? "ok" : "mismatch", "dimensions:" + actual);
            } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
        }
    }

    record Rerank(RemotePool pool, String model, Duration timeout) {
        /** 문서별 점수. 공급자가 일부만 돌려주면 나머지는 NaN이다. */
        RemotePool.Result<double[]> scores(String query, List<String> documents) {
            return pool.execute(timeout, (http, server, limit) -> request(http, server, limit, query, documents));
        }
        private double[] request(RemoteHttp http, RemoteServer server, Duration limit, String query, List<String> documents) {
            var body = new JsonObject(); body.addProperty("model", model); body.addProperty("query", query);
            var docs = new JsonArray(); documents.forEach(docs::add); body.add("documents", docs);
            body.addProperty("top_n", documents.size());
            return parse(http.post(server, "/rerank", body, limit, MAX_RERANK_RESPONSE), documents.size());
        }
        RemoteCheck verify(String server, Duration limit) {
            try {
                var models = pool.probe(server, limit, (http, s, t) -> http.models(s, t));
                if (models.isPresent() && !models.get().contains(model)) return new RemoteCheck(server, "mismatch", "model");
                pool.probe(server, limit, (http, s, t) -> request(http, s, t, "connection test", List.of("connection test", "연결 시험")));
                return new RemoteCheck(server, "ok", "");
            } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
        }
    }

    /**
     * 공급자 리랭크 응답을 문서별 점수로 바꾼다. 받는 형식: {results:[...]}·{data:[...]}·최상위 배열,
     * 항목은 index와 relevance_score(또는 score). 색인 중복·범위 밖·유한하지 않은 점수·빈 결과는 잘못된 응답이다.
     */
    static double[] parse(JsonElement response, int count) {
        JsonArray results;
        if (response.isJsonArray()) results = response.getAsJsonArray();
        else {
            var object = response.getAsJsonObject();
            results = object.get("results") instanceof JsonArray r ? r : object.get("data") instanceof JsonArray d ? d : null;
        }
        if (results == null || results.isEmpty() || results.size() > count) throw new IllegalStateException("리랭크 응답 수 오류");
        var scores = new double[count]; Arrays.fill(scores, Double.NaN);
        for (var entry : results) {
            var item = entry.getAsJsonObject(); double raw = item.get("index").getAsDouble(); int i = (int)raw;
            var value = item.has("relevance_score") ? item.get("relevance_score") : item.get("score");
            double score = value.getAsDouble();
            if (raw != i || i < 0 || i >= count || !Double.isNaN(scores[i]) || !Double.isFinite(score)) throw new IllegalStateException("리랭크 응답 오류");
            scores[i] = score;
        }
        return scores;
    }

    private final Embedding embedding;
    private final Rerank rerank;
    private final RemoteOcrProvider ocr;
    private final String ocrEngine;
    private final RemoteDoclingClient docling;
    private volatile String doclingVersion = "";

    private MlGateway(Embedding embedding, Rerank rerank, RemoteOcrProvider ocr, String ocrEngine, RemoteDoclingClient docling) {
        this.embedding = embedding; this.rerank = rerank; this.ocr = ocr; this.ocrEngine = ocrEngine; this.docling = docling;
    }
    /** 설정의 원격 기능으로 서버 묶음을 만든다. 아직 연결하지 않는다({@link #verify()}). */
    static MlGateway open(MlConfig c) {
        Embedding e = null; Rerank r = null; RemoteOcrProvider o = null; RemoteDoclingClient d = null;
        try {
            if (c.embeddingRemote() != null) e = new Embedding(new RemotePool("embedding", c.embeddingRemote().servers()),
                    c.embeddingRemote().model(), c.embeddingRemote().dimensions(), Duration.ofSeconds(c.embeddingRemote().timeoutSeconds()));
            if (c.rerankerRemote() != null) r = new Rerank(new RemotePool("reranker", c.rerankerRemote().servers()),
                    c.rerankerRemote().model(), Duration.ofSeconds(c.rerankerRemote().timeoutSeconds()));
            if (c.ocrRemote() != null) o = new RemoteOcrProvider(new RemotePool("ocr", c.ocrRemote().servers()), c.ocrRemote().model(), c.ocrRemote().timeoutSeconds());
            if (c.doclingRemote() != null) d = new RemoteDoclingClient(new RemotePool("docling", c.doclingRemote().servers()),
                    c.doclingRemote().timeoutSeconds(), RemoteDoclingClient.DEFAULT_MAX_FILE_BYTES);
            return new MlGateway(e, r, o, c.ocrRemote() == null ? null : c.ocrRemote().model(), d);
        } catch (RuntimeException ex) {
            new MlGateway(e, r, o, null, d).close();
            throw ex;
        }
    }
    Embedding embedding() { return embedding; }
    Rerank rerank() { return rerank; }
    RemoteOcrProvider ocr() { return ocr; }
    String ocrEngine() { return ocrEngine; }
    RemoteDoclingClient docling() { return docling; }
    String doclingVersion() { return doclingVersion; }

    /**
     * 시작 확인. 서버마다 모델 목록·시험 호출로 모델·차원/엔진/버전을 확인하고 불일치가 하나라도 있으면 시작을 거부한다.
     * 연결 불가(down)·확인 불가(error)는 경고만 남기고 시작한다(서버 이름·실패 종류만 기록).
     */
    void verify() {
        if (embedding != null) check("embedding", embedding.pool(), embedding::verify);
        if (rerank != null) check("reranker", rerank.pool(), rerank::verify);
        if (ocr != null) check("ocr", ocr.pool(), ocr::verify);
        if (docling != null) check("docling", docling.pool(), (name, limit) -> {
            var result = docling.verify(name, limit);
            if ("ok".equals(result.result()) && doclingVersion.isEmpty()) doclingVersion = result.detail();
            return result;
        });
    }
    private static void check(String feature, RemotePool pool, java.util.function.BiFunction<String, Duration, RemoteCheck> verify) {
        for (var server : pool.servers()) {
            var result = verify.apply(server.name(), Duration.ofSeconds(Math.min(server.timeoutSeconds(), CHECK_SECONDS)));
            if (result.mismatch())
                throw new IllegalStateException("원격 " + feature + " 서버 " + server.name() + " 설정 불일치(" + result.detail() + ")");
            if (!"ok".equals(result.result()))
                LOG.log(System.Logger.Level.WARNING, "원격 " + feature + " 서버 " + server.name() + " 확인 실패: " + result.result() + " " + result.detail());
        }
    }
    /** 기능 하나의 서버별 상태(이름·상태·마지막 실패 종류·연속 실패 수). 주소·키는 넣지 않는다. */
    static JsonArray servers(RemotePool pool) {
        var list = new JsonArray();
        for (var s : pool.status()) {
            var o = new JsonObject(); o.addProperty("name", s.name()); o.addProperty("state", s.state());
            o.addProperty("lastError", s.lastError()); o.addProperty("consecutiveFailures", s.consecutiveFailures()); list.add(o);
        }
        return list;
    }
    public void close() {
        if (embedding != null) embedding.pool().close();
        if (rerank != null) rerank.pool().close();
        if (ocr != null) ocr.close();
        if (docling != null) docling.close();
    }
}

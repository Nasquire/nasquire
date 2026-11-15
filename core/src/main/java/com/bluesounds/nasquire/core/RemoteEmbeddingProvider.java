package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import com.google.gson.*;
import java.time.Duration;
import java.util.*;

/**
 * OpenAI 호환 /embeddings. 서버 여러 개는 {@link RemotePool}이 나누고 넘기며 응답·예외에 서버 본문이나 인증값을 포함하지 않는다.
 * 원격이 모두 실패하면 실패를 그대로 던진다(내장 모델로 대체하지 않음).
 */
public final class RemoteEmbeddingProvider implements Providers.EmbeddingProvider {
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final RemotePool pool;
    private final String model, modelId;
    private final int dimensions, batchSize;
    private final Duration timeout;
    /** 서버 하나. 색인 모델 ID는 기존 형식(remote:주소:모델)을 유지한다. */
    public RemoteEmbeddingProvider(String baseUrl, String model, String key, int dimensions, int batchSize, int timeoutSeconds) {
        this(single(baseUrl, key, timeoutSeconds), model, legacyModelId(baseUrl, model), dimensions, batchSize, timeoutSeconds);
    }
    /**
     * 서버 묶음. modelId가 null이면 주소와 무관한 remote:모델을 쓰므로 서버를 추가·교체해도 재색인하지 않는다.
     * timeoutSeconds는 페일오버를 포함한 요청 전체 상한이다. 공급자를 닫으면 묶음도 닫는다.
     */
    public RemoteEmbeddingProvider(RemotePool pool, String model, String modelId, int dimensions, int batchSize, int timeoutSeconds) {
        if (pool == null || model == null || model.isBlank() || model.length() > 256 || dimensions < 1 || dimensions > 1024
                || batchSize < 1 || batchSize > 16 || timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalArgumentException("임베딩 설정 범위 오류");
        this.pool = pool; this.model = model; this.modelId = modelId == null ? "remote:" + model : modelId;
        this.dimensions = dimensions; this.batchSize = batchSize; timeout = Duration.ofSeconds(timeoutSeconds);
    }
    private static RemotePool single(String baseUrl, String key, int timeoutSeconds) {
        try { return new RemotePool("embedding", List.of(new RemoteServer("default", baseUrl, key, timeoutSeconds))); }
        catch (RuntimeException e) { throw new IllegalArgumentException("임베딩 설정 범위 오류"); }
    }
    /** 서버 하나였던 이전 설정의 색인 모델 ID. 이관 후에도 같은 색인을 계속 쓰기 위해 공개한다. */
    public static String legacyModelId(String baseUrl, String model) { return "remote:" + baseUrl.replaceAll("/+$", "") + "/embeddings:" + model; }
    public String modelId() { return modelId; }
    public int dimensions() { return dimensions; }
    public int batchSize() { return batchSize; }
    public RemotePool pool() { return pool; }
    public float[] embed(String text) { return embedBatch(List.of(text)).getFirst(); }
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty() || texts.size() > batchSize) throw new IllegalArgumentException("배치 상한 초과");
        return pool.execute(timeout, (http, server, limit) -> request(http, server, limit, texts, dimensions)).value();
    }
    private List<float[]> request(RemoteHttp http, RemoteServer server, Duration limit, List<String> texts, int expected) {
        var body = new JsonObject(); body.addProperty("model", model); body.addProperty("encoding_format", "float");
        var input = new JsonArray(); texts.forEach(input::add); body.add("input", input);
        var data = http.post(server, "/embeddings", body, limit, MAX_RESPONSE).getAsJsonObject().getAsJsonArray("data");
        if (data.size() != texts.size()) throw new IllegalStateException("임베딩 응답 수 오류");
        var result = new float[texts.size()][];
        for (var entry : data) {
            var obj = entry.getAsJsonObject(); int i = obj.get("index").getAsInt();
            if (i < 0 || i >= result.length || result[i] != null) throw new IllegalStateException("임베딩 순서 오류");
            var values = obj.getAsJsonArray("embedding");
            if (expected > 0 && values.size() != expected || values.isEmpty() || values.size() > 8192) throw new IllegalStateException("임베딩 차원 불일치");
            float[] vector = new float[values.size()];
            for (int j = 0; j < vector.length; j++) vector[j] = values.get(j).getAsFloat();
            result[i] = expected > 0 ? normalized(vector) : vector;
        }
        return Arrays.asList(result);
    }
    /** 서버 하나의 모델 목록(지원 시)과 시험 임베딩 1회의 차원을 설정값과 비교한다. */
    public RemoteCheck verify(String server, Duration limit) {
        try {
            var models = pool.probe(server, limit, (http, s, t) -> http.models(s, t));
            if (models.isPresent() && !models.get().contains(model)) return new RemoteCheck(server, "mismatch", "model");
            int actual = pool.probe(server, limit, (http, s, t) -> request(http, s, t, List.of("연결 시험 / connection test"), 0)).getFirst().length;
            return actual == dimensions ? new RemoteCheck(server, "ok", "dimensions:" + actual) : new RemoteCheck(server, "mismatch", "dimensions:" + actual);
        } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
    }
    public static float[] normalized(float[] vector) {
        double norm = 0;
        for (float v : vector) { if (!Float.isFinite(v)) throw new IllegalArgumentException("유효하지 않은 벡터"); norm += (double)v * v; }
        if (norm == 0) throw new IllegalArgumentException("빈 벡터");
        for (int i = 0; i < vector.length; i++) vector[i] /= (float)Math.sqrt(norm);
        return vector;
    }
    public void close() { pool.close(); }
}

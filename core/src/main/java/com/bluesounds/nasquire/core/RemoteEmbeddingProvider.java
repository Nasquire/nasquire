package com.bluesounds.nasquire.core;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 응답·예외에 서버 본문이나 인증값을 포함하지 않는다. */
public final class RemoteEmbeddingProvider implements Providers.EmbeddingProvider {
    private final HttpClient client;
    private final URI endpoint;
    private final String model, key;
    private final int dimensions, batchSize;
    private final Duration timeout;
    public RemoteEmbeddingProvider(String baseUrl, String model, String key, int dimensions, int batchSize, int timeoutSeconds) {
        URI base = URI.create(baseUrl);
        if (!Set.of("https", "http").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || model == null || model.isBlank()
                || key == null || key.contains("\n") || key.contains("\r") || dimensions < 1 || dimensions > 1024
                || batchSize < 1 || batchSize > 16 || timeoutSeconds < 1 || timeoutSeconds > 300)
            throw new IllegalArgumentException("임베딩 설정 범위 오류");
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
        this.model = model; this.key = key; this.dimensions = dimensions; this.batchSize = batchSize;
        timeout = Duration.ofSeconds(timeoutSeconds);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public String modelId() { return "remote:" + endpoint + ":" + model; }
    public int dimensions() { return dimensions; }
    public int batchSize() { return batchSize; }
    public float[] embed(String text) { return embedBatch(List.of(text)).getFirst(); }
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty() || texts.size() > batchSize) throw new IllegalArgumentException("배치 상한 초과");
        var body = new JsonObject(); body.addProperty("model", model); body.addProperty("encoding_format", "float");
        var input = new JsonArray(); texts.forEach(input::add); body.add("input", input);
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json");
        if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
        try {
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), ignored -> boundedBody());
            {
                if (response.statusCode() != 200) throw new IllegalStateException("임베딩 HTTP 실패 (" + response.statusCode() + ")");
                byte[] bytes = response.body();
                if (bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("임베딩 응답 상한 초과");
                var data = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("data");
                if (data.size() != texts.size()) throw new IllegalStateException("임베딩 응답 수 오류");
                var result = new float[texts.size()][];
                for (var entry : data) {
                    var obj = entry.getAsJsonObject(); int i = obj.get("index").getAsInt();
                    if (i < 0 || i >= result.length || result[i] != null) throw new IllegalStateException("임베딩 순서 오류");
                    var values = obj.getAsJsonArray("embedding");
                    if (values.size() != dimensions) throw new IllegalStateException("임베딩 차원 불일치");
                    float[] vector = new float[dimensions];
                    for (int j = 0; j < dimensions; j++) vector[j] = values.get(j).getAsFloat();
                    result[i] = normalized(vector);
                }
                return Arrays.asList(result);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("임베딩 중단"); }
        catch (Exception e) { throw new IllegalStateException("원격 임베딩 실패: 설정·연결·응답을 확인하세요"); }
    }
    private static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private java.util.concurrent.Flow.Subscription subscription;
            private long size;
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<java.nio.ByteBuffer> buffers) {
                for (var b : buffers) size += b.remaining();
                if (size > 2 * 1024 * 1024) { subscription.cancel(); delegate.onError(new java.io.IOException("응답 상한 초과")); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
    public static float[] normalized(float[] vector) {
        double norm = 0;
        for (float v : vector) { if (!Float.isFinite(v)) throw new IllegalArgumentException("유효하지 않은 벡터"); norm += (double)v * v; }
        if (norm == 0) throw new IllegalArgumentException("빈 벡터");
        for (int i = 0; i < vector.length; i++) vector[i] /= (float)Math.sqrt(norm);
        return vector;
    }
    public void close() { client.close(); }
}

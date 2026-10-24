package com.bluesounds.nasquire.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Qdrant REST 저장소. 점 payload에는 소스 ID·색인 key·offset·색인 세대만 두고 본문은 보내지 않는다.
 * 검색은 소스 범위를 Qdrant payload 필터로 보내고, 응답도 다시 범위를 검사한다. 응답·예외에 서버 본문이나 키를 남기지 않는다.
 */
public final class QdrantVectorStore implements VectorStore {
    static final int MAX_RESPONSE = 4 * 1024 * 1024, BATCH = 64;
    /** 연속 실패 수가 이 값에 이르면 OPEN 동안 모든 호출을 원격 요청 없이 즉시 실패시킨다. keyword 검색·설정은 영향이 없다. */
    static final int FAILURES = 3;
    static long openMillis = 30_000;
    /** 검색은 설정 시간, 여러 요청으로 이뤄진 쓰기·초기화는 설정 시간의 4배를 작업 전체 상한으로 한다. */
    private static final int WRITE_BUDGET = 4;
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long openUntil;
    private volatile boolean open;
    private interface Operation<T> { T run(long deadline) throws IOException; }
    private final HttpClient client;
    private final String base, collection, key;
    private final Duration timeout;
    private int ensured;
    public QdrantVectorStore(String url, String collection, String apiKey, int timeoutSeconds) {
        URI uri = URI.create(url == null ? "" : url);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || collection == null || !collection.matches("[A-Za-z0-9_-]{1,64}")
                || apiKey == null || apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c < 0x21 || c > 0x7e)
                || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Qdrant 설정 범위 오류");
        base = url.replaceAll("/+$", ""); this.collection = collection; key = apiKey; timeout = Duration.ofSeconds(timeoutSeconds);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public String id() { return "qdrant:" + base + "/" + collection; }

    private <T> T guarded(int budget, Operation<T> operation) throws IOException {
        if (open && System.nanoTime() - openUntil < 0) throw new IOException("Qdrant 일시 차단 (연속 실패)");
        try {
            T result = operation.run(System.nanoTime() + timeout.toNanos() * budget);
            failures.set(0); open = false;
            return result;
        } catch (IOException e) {
            if (failures.incrementAndGet() >= FAILURES) { openUntil = System.nanoTime() + openMillis * 1_000_000; open = true; failures.set(0); }
            throw e;
        }
    }
    public synchronized void reset(int dimensions) throws IOException {
        guarded(WRITE_BUDGET, deadline -> {
            call("DELETE", "", null, true, deadline);
            ensured = 0; create(dimensions, deadline); return null;
        });
    }
    private void create(int dimensions, long deadline) throws IOException {
        if (dimensions < 1 || dimensions > 4096) throw new IllegalArgumentException("차원 오류");
        var vectors = new JsonObject(); vectors.addProperty("size", dimensions); vectors.addProperty("distance", "Cosine");
        var body = new JsonObject(); body.add("vectors", vectors);
        call("PUT", "", body, false, deadline);
        // 범위 필터와 삭제 필터가 쓰는 payload 필드를 색인한다.
        for (String field : List.of("source", "key", "epoch")) {
            var index = new JsonObject(); index.addProperty("field_name", field); index.addProperty("field_schema", field.equals("epoch") ? "integer" : "keyword");
            call("PUT", "/index?wait=true", index, false, deadline);
        }
        ensured = dimensions;
    }
    private void ensure(int dimensions, long deadline) throws IOException {
        if (ensured == dimensions) return;
        var info = call("GET", "", null, true, deadline);
        if (info == null) { create(dimensions, deadline); return; }
        int size;
        try { size = info.getAsJsonObject().getAsJsonObject("result").getAsJsonObject("config").getAsJsonObject("params").getAsJsonObject("vectors").get("size").getAsInt(); }
        catch (RuntimeException e) { throw new IOException("Qdrant 컬렉션 정보 오류"); }
        if (size != dimensions) throw new IOException("Qdrant 컬렉션 차원 불일치: 재색인이 필요합니다");
        ensured = dimensions;
    }
    public synchronized void replace(String key, List<Point> points, int dimensions) throws IOException {
        for (var p : points) if (!p.key().equals(key) || !key.startsWith(p.source() + ":") || p.vector().length != dimensions) throw new IllegalArgumentException("점 오류");
        guarded(WRITE_BUDGET, deadline -> { upsert(key, points, dimensions, deadline); return null; });
    }
    private void upsert(String key, List<Point> points, int dimensions, long deadline) throws IOException {
        ensure(dimensions, deadline);
        delete("key", key, deadline);
        for (int i = 0; i < points.size(); i += BATCH) {
            var array = new JsonArray();
            for (var p : points.subList(i, Math.min(i + BATCH, points.size()))) {
                var point = new JsonObject(); point.addProperty("id", pointId(p.key(), p.offset()));
                var vector = new JsonArray(); for (float v : p.vector()) vector.add(v); point.add("vector", vector);
                var payload = new JsonObject(); payload.addProperty("source", p.source()); payload.addProperty("key", p.key()); payload.addProperty("offset", p.offset());
                payload.addProperty("epoch", p.epoch());
                point.add("payload", payload); array.add(point);
            }
            var body = new JsonObject(); body.add("points", array);
            call("PUT", "/points?wait=true", body, false, deadline);
        }
    }
    public synchronized void deleteKey(String key) throws IOException { guarded(WRITE_BUDGET, deadline -> { delete("key", key, deadline); return null; }); }
    public synchronized void deleteSource(String source) throws IOException { guarded(WRITE_BUDGET, deadline -> { delete("source", source, deadline); return null; }); }
    private void delete(String field, String value, long deadline) throws IOException {
        var body = new JsonObject(); body.add("filter", must(field, match("value", new JsonPrimitive(value))));
        call("POST", "/points/delete?wait=true", body, true, deadline);
    }
    public List<Match> search(float[] vector, Set<String> sources, int limit, long epoch) throws IOException {
        if (sources.isEmpty()) return List.of();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("결과 수 오류");
        var query = new JsonArray(); for (float v : vector) query.add(v);
        var any = new JsonArray(); sources.stream().sorted().forEach(any::add);
        var body = new JsonObject(); body.add("query", query); body.addProperty("limit", limit);
        var filter = must("source", match("any", any));
        var current = new JsonObject(); current.addProperty("key", "epoch"); current.add("match", match("value", new JsonPrimitive(epoch)));
        filter.getAsJsonArray("must").add(current);
        body.add("filter", filter);
        var fields = new JsonArray(); fields.add("source"); fields.add("key"); fields.add("offset"); fields.add("epoch");
        body.add("with_payload", fields); body.addProperty("with_vector", false);
        var response = guarded(1, deadline -> call("POST", "/points/query", body, false, deadline));
        var matches = new ArrayList<Match>();
        try {
            for (var element : response.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("points")) {
                var point = element.getAsJsonObject(); var payload = point.getAsJsonObject("payload");
                String source = payload.get("source").getAsString(), key = payload.get("key").getAsString();
                int offset = payload.get("offset").getAsInt(); float score = point.get("score").getAsFloat();
                // 서버 필터가 무시되거나 손상돼도 범위 밖·이전 세대 점은 버린다.
                if (!payload.has("epoch") || payload.get("epoch").getAsLong() != epoch) continue;
                if (!sources.contains(source) || !key.startsWith(source + ":") || !Float.isFinite(score) || offset < 0) continue;
                matches.add(new Match(source, key, offset, score));
                if (matches.size() >= limit) break;
            }
        } catch (RuntimeException e) { throw new IOException("Qdrant 응답 형식 오류"); }
        return matches;
    }
    static String pointId(String key, int offset) {
        return UUID.nameUUIDFromBytes((key + "\0" + offset).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static JsonObject match(String kind, JsonElement value) { var m = new JsonObject(); m.add(kind, value); return m; }
    private static JsonObject must(String field, JsonObject match) {
        var condition = new JsonObject(); condition.addProperty("key", field); condition.add("match", match);
        var list = new JsonArray(); list.add(condition);
        var filter = new JsonObject(); filter.add("must", list); return filter;
    }
    private JsonElement call(String method, String path, JsonObject body, boolean allowMissing, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Qdrant 작업 시간 상한 초과");
        var request = HttpRequest.newBuilder(URI.create(base + "/collections/" + collection + path)).timeout(Duration.ofNanos(remaining))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        if (body != null) request.header("Content-Type", "application/json");
        if (!key.isEmpty()) request.header("api-key", key);
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // 본문을 끝없이 지연하는 서버에도 작업 전체 상한을 적용한다.
            pending = client.sendAsync(request.build(), ignored -> Bounded.body(MAX_RESPONSE));
            var response = pending.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (allowMissing && response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IOException("Qdrant HTTP 실패 (" + response.statusCode() + ")");
            return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Qdrant 요청 중단"); }
        catch (java.util.concurrent.TimeoutException e) { throw new IOException("Qdrant 작업 시간 상한 초과"); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException("Qdrant 연결 실패"); }
        catch (IOException e) { throw e.getMessage() != null && e.getMessage().startsWith("Qdrant") ? e : new IOException("Qdrant 연결 실패"); }
        catch (RuntimeException e) { throw new IOException("Qdrant 응답 형식 오류"); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }
    public void close() { client.close(); }

    /** 응답 본문 상한. 초과하면 수신을 중단한다. */
    static final class Bounded {
        private Bounded() { }
        static HttpResponse.BodySubscriber<byte[]> body(long limit) {
            return new HttpResponse.BodySubscriber<>() {
                private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
                private java.util.concurrent.Flow.Subscription subscription;
                private long size;
                public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
                public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
                public void onNext(List<java.nio.ByteBuffer> buffers) {
                    for (var b : buffers) size += b.remaining();
                    if (size > limit) { subscription.cancel(); delegate.onError(new IOException("응답 상한 초과")); }
                    else delegate.onNext(buffers);
                }
                public void onError(Throwable error) { delegate.onError(error); }
                public void onComplete() { delegate.onComplete(); }
            };
        }
    }
}

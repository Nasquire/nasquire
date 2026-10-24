package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 일반 /rerank API. 범위 검사된 상위 조각만 전송하며 실패하면 입력 순서를 보존한다. */
public final class RemoteRerankerProvider implements Providers.RerankerProvider {
    private final HttpClient client;
    private final URI endpoint;
    private final String model, key;
    private final int topN, seconds;
    // 과부하 때 대기열·스레드를 만들지 않고 즉시 원래 순서를 사용한다.
    private final Semaphore active = new Semaphore(1);
    public RemoteRerankerProvider(String url, String model, String key, int topN, int seconds) {
        URI base = URI.create(url);
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || model == null || model.isBlank() || model.length() > 256
                || key == null || key.contains("\r") || key.contains("\n") || key.length() > 4096
                || topN < 1 || topN > 100 || seconds < 1 || seconds > 30) throw new IllegalArgumentException("리랭커 설정 범위 오류");
        endpoint = URI.create(url.replaceAll("/+$", "") + "/rerank"); this.model = model; this.key = key; this.topN = topN; this.seconds = seconds;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(seconds)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits) {
        if (hits.size() < 2 || !active.tryAcquire()) return hits;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            int count = Math.min(topN, hits.size());
            var body = new JsonObject(); body.addProperty("model", model); body.addProperty("query", query); body.addProperty("top_n", count);
            var docs = new JsonArray();
            for (var h : hits.subList(0, count)) docs.add(h.text().substring(0, Math.min(1200, h.text().length())));
            body.add("documents", docs);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(seconds)).header("Content-Type", "application/json");
            if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
            pending = client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), ignored -> boundedBody());
            // 본문을 끝없이 지연하는 서버에도 전체 요청 시간 상한을 적용한다.
            var response = pending.get(seconds, TimeUnit.SECONDS);
            if (response.statusCode() != 200) return hits;
            var results = JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("results");
            if (results.size() != count) return hits;
            var scores = new double[count]; var seen = new boolean[count];
            for (var entry : results) {
                var r = entry.getAsJsonObject(); double raw = r.get("index").getAsDouble(); int i = (int)raw;
                double score = r.get("relevance_score").getAsDouble();
                if (raw != i || i < 0 || i >= count || seen[i] || !Double.isFinite(score)) return hits;
                scores[i] = score; seen[i] = true;
            }
            var order = new ArrayList<Integer>(); for (int i = 0; i < count; i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed());
            var ranked = new ArrayList<SearchIndex.Hit>(); order.forEach(i -> ranked.add(hits.get(i)));
            ranked.addAll(hits.subList(count, hits.size())); return List.copyOf(ranked);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return hits; }
        catch (Exception e) { return hits; }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); active.release(); }
    }
    private static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private int size;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
            public void onNext(List<ByteBuffer> buffers) {
                for (var b : buffers) size += b.remaining();
                if (size > 65536) { subscription.cancel(); delegate.onError(new java.io.IOException("응답 상한 초과")); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable e) { delegate.onError(e); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
    public void close() { client.shutdownNow(); }
}

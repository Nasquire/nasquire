package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import com.google.gson.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 일반 /rerank API(Cohere·Jina·Infinity 호환: {model, query, documents, top_n} → results[{index, relevance_score}]).
 * 범위 검사된 상위 조각만 전송하며 실패·혼잡하면 입력 순서를 보존한다. 외부 순위만 받고 근거·원래 score는 그대로 둔다.
 */
public final class RemoteRerankerProvider implements Providers.RerankerProvider {
    private static final int MAX_RESPONSE = 65536;
    private final RemotePool pool;
    private final String model;
    private final int topN;
    private final Duration timeout;
    // 과부하 때 대기열·스레드를 만들지 않고 즉시 원래 순서를 사용한다.
    private final Semaphore active = new Semaphore(1);
    /** 서버 하나. */
    public RemoteRerankerProvider(String url, String model, String key, int topN, int seconds) {
        this(single(url, key, seconds), model, topN, seconds);
    }
    /** 서버 묶음. seconds는 페일오버를 포함한 요청 전체 상한(1~30초)이다. 공급자를 닫으면 묶음도 닫는다. */
    public RemoteRerankerProvider(RemotePool pool, String model, int topN, int seconds) {
        if (pool == null || model == null || model.isBlank() || model.length() > 256 || topN < 1 || topN > 100 || seconds < 1 || seconds > 30)
            throw new IllegalArgumentException("리랭커 설정 범위 오류");
        this.pool = pool; this.model = model; this.topN = topN; timeout = Duration.ofSeconds(seconds);
    }
    private static RemotePool single(String url, String key, int seconds) {
        try { return new RemotePool("reranker", List.of(new RemoteServer("default", url, key, seconds))); }
        catch (RuntimeException e) { throw new IllegalArgumentException("리랭커 설정 범위 오류"); }
    }
    public RemotePool pool() { return pool; }
    public List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits) { return reranked(query, hits).hits(); }
    @Override
    public Providers.Reranked reranked(String query, List<SearchIndex.Hit> hits) {
        if (hits.size() < 2 || !active.tryAcquire()) return Providers.Reranked.original(hits);
        try {
            int count = Math.min(topN, hits.size());
            var result = pool.execute(timeout, (http, server, limit) -> scores(http, server, limit, query, hits.subList(0, count)));
            var scores = result.value();
            var order = new ArrayList<Integer>(); for (int i = 0; i < count; i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed());
            var ranked = new ArrayList<SearchIndex.Hit>(); order.forEach(i -> ranked.add(hits.get(i)));
            ranked.addAll(hits.subList(count, hits.size()));
            return new Providers.Reranked(List.copyOf(ranked), result.server(), result.failover(), true);
        } catch (RuntimeException e) { return Providers.Reranked.original(hits); }
        finally { active.release(); }
    }
    private double[] scores(RemoteHttp http, RemoteServer server, Duration limit, String query, List<SearchIndex.Hit> hits) {
        int count = hits.size();
        var body = new JsonObject(); body.addProperty("model", model); body.addProperty("query", query); body.addProperty("top_n", count);
        var docs = new JsonArray();
        for (var h : hits) docs.add(h.text().substring(0, Math.min(1200, h.text().length())));
        body.add("documents", docs);
        var results = http.post(server, "/rerank", body, limit, MAX_RESPONSE).getAsJsonObject().getAsJsonArray("results");
        if (results.size() != count) throw new IllegalStateException("리랭크 응답 수 오류");
        var scores = new double[count]; var seen = new boolean[count];
        for (var entry : results) {
            var r = entry.getAsJsonObject(); double raw = r.get("index").getAsDouble(); int i = (int)raw;
            double score = r.get("relevance_score").getAsDouble();
            if (raw != i || i < 0 || i >= count || seen[i] || !Double.isFinite(score)) throw new IllegalStateException("리랭크 응답 오류");
            scores[i] = score; seen[i] = true;
        }
        return scores;
    }
    /** 서버 하나의 모델 목록(지원 시)을 확인하고 짧은 시험 요청 1건으로 응답 형식을 확인한다. */
    public RemoteCheck verify(String server, Duration limit) {
        try {
            var models = pool.probe(server, limit, (http, s, t) -> http.models(s, t));
            if (models.isPresent() && !models.get().contains(model)) return new RemoteCheck(server, "mismatch", "model");
            var sample = List.of(new SearchIndex.Hit("test", "test", "test:a", 1, 0, "connection test", 0, 1),
                    new SearchIndex.Hit("test", "test", "test:b", 1, 0, "연결 시험", 0, 1));
            pool.probe(server, limit, (http, s, t) -> scores(http, s, t, "connection test", sample));
            return new RemoteCheck(server, "ok", "");
        } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
    }
    public void close() { pool.close(); }
}

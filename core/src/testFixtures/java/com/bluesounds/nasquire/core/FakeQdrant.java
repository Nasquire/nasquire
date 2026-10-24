package com.bluesounds.nasquire.core;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 테스트용 가짜 Qdrant REST 서버. 필요한 API만 메모리에서 흉내 낸다. */
public final class FakeQdrant implements AutoCloseable {
    public record Request(String method, String path, String apiKey, String body) { }
    private record Point(String id, float[] vector, JsonObject payload) { }
    private final HttpServer server;
    private final String apiKey;
    private final Map<String, Integer> sizes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Point>> collections = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    /** 서버 측 필터를 무시하는 손상/악의적 서버를 흉내 낸다. */
    public volatile boolean ignoreFilter;
    public volatile boolean fail;
    /** 느린 서버를 흉내 낸다. 요청마다 이만큼 기다린 뒤 처리한다. */
    public volatile long delayMillis;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "fake-qdrant"); t.setDaemon(true); return t; });

    public FakeQdrant(String apiKey) throws IOException {
        this.apiKey = apiKey;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/", this::handle);
        server.setExecutor(executor);
        server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public int points(String collection) { return collections.getOrDefault(collection, Map.of()).size(); }
    public Set<String> payloadValues(String collection, String field) {
        var values = new TreeSet<String>();
        collections.getOrDefault(collection, Map.of()).values().forEach(p -> values.add(p.payload.get(field).getAsString()));
        return values;
    }
    public List<Request> requests(String suffix) { return requests.stream().filter(r -> r.path.endsWith(suffix)).toList(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            requests.add(new Request(method, path, exchange.getRequestHeaders().getFirst("api-key"), body));
            if (delayMillis > 0) { try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } }
            if (fail) { reply(exchange, 500, "{\"status\":{\"error\":\"secret-qdrant-body\"}}"); return; }
            if (apiKey != null && !apiKey.equals(exchange.getRequestHeaders().getFirst("api-key"))) { reply(exchange, 401, "{}"); return; }
            var parts = path.substring("/collections/".length()).split("/", 2);
            String name = parts[0], rest = parts.length > 1 ? parts[1] : "";
            var input = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
            switch (method + " " + rest) {
                case "GET " -> {
                    if (!sizes.containsKey(name)) { reply(exchange, 404, "{}"); return; }
                    reply(exchange, 200, "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":" + sizes.get(name) + ",\"distance\":\"Cosine\"}}}}}");
                }
                case "PUT " -> {
                    if (sizes.containsKey(name)) { reply(exchange, 409, "{}"); return; }
                    sizes.put(name, input.getAsJsonObject("vectors").get("size").getAsInt()); collections.put(name, new ConcurrentHashMap<>()); ok(exchange);
                }
                case "DELETE " -> {
                    if (sizes.remove(name) == null) { reply(exchange, 404, "{}"); return; }
                    collections.remove(name); ok(exchange);
                }
                case "PUT index" -> ok(exchange);
                case "PUT points" -> {
                    var points = collection(exchange, name); if (points == null) return;
                    for (var e : input.getAsJsonArray("points")) {
                        var o = e.getAsJsonObject(); var array = o.getAsJsonArray("vector");
                        if (array.size() != sizes.get(name)) { reply(exchange, 400, "{}"); return; }
                        var vector = new float[array.size()]; for (int i = 0; i < vector.length; i++) vector[i] = array.get(i).getAsFloat();
                        points.put(o.get("id").getAsString(), new Point(o.get("id").getAsString(), vector, o.getAsJsonObject("payload")));
                    }
                    ok(exchange);
                }
                case "POST points/delete" -> {
                    var points = collection(exchange, name); if (points == null) return;
                    points.values().removeIf(p -> matches(p, input.getAsJsonObject("filter")));
                    ok(exchange);
                }
                case "POST points/query" -> {
                    var points = collection(exchange, name); if (points == null) return;
                    var array = input.getAsJsonArray("query"); var query = new float[array.size()];
                    for (int i = 0; i < query.length; i++) query[i] = array.get(i).getAsFloat();
                    var result = new JsonArray();
                    points.values().stream().filter(p -> ignoreFilter || matches(p, input.getAsJsonObject("filter")))
                            .sorted(Comparator.comparingDouble((Point p) -> -cosine(query, p.vector)).thenComparing(Point::id))
                            .limit(input.get("limit").getAsInt()).forEach(p -> {
                                var o = new JsonObject(); o.addProperty("id", p.id); o.addProperty("score", cosine(query, p.vector)); o.add("payload", p.payload); result.add(o);
                            });
                    var points2 = new JsonObject(); points2.add("points", result);
                    var response = new JsonObject(); response.add("result", points2);
                    reply(exchange, 200, response.toString());
                }
                default -> reply(exchange, 404, "{}");
            }
        }
    }
    private Map<String, Point> collection(HttpExchange exchange, String name) throws IOException {
        var points = collections.get(name);
        if (points == null) reply(exchange, 404, "{}");
        return points;
    }
    /** must 조건의 match.value / match.any만 지원한다. 필터가 없으면 거부한다. */
    private static boolean matches(Point p, JsonObject filter) {
        if (filter == null) throw new IllegalArgumentException("filter 필요");
        for (var c : filter.getAsJsonArray("must")) {
            var condition = c.getAsJsonObject(); var match = condition.getAsJsonObject("match");
            var field = p.payload.get(condition.get("key").getAsString());
            if (field == null) return false;
            String actual = field.getAsString();
            if (match.has("value") && !match.get("value").getAsString().equals(actual)) return false;
            if (match.has("any")) {
                boolean found = false; for (var v : match.getAsJsonArray("any")) found |= v.getAsString().equals(actual);
                if (!found) return false;
            }
        }
        return true;
    }
    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
    private static void ok(HttpExchange exchange) throws IOException { reply(exchange, 200, "{\"result\":true,\"status\":\"ok\"}"); }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
}

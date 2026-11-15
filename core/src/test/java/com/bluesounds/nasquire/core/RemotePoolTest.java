package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 공통 원격 연결 계층: round_robin, 페일오버 범위, 서킷 브레이커, 모델·차원 확인, 리랭커(lite 포함). */
class RemotePoolTest {
    private HttpServer server;
    /** 서버 이름별 응답 상태. 200이면 정상 응답, -1이면 응답 지연(시간 초과). */
    private final Map<String, Integer> status = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, String> models = new ConcurrentHashMap<>();
    private final Map<String, Integer> dims = new ConcurrentHashMap<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            String[] parts = e.getRequestURI().getPath().split("/");
            String name = parts[1], op = parts[parts.length - 1];
            e.getRequestBody().readAllBytes();
            String body;
            if (op.equals("models")) {
                String model = models.get(name);
                if (model == null) { e.sendResponseHeaders(404, -1); e.close(); return; }
                body = "{\"data\":[{\"id\":\"" + model + "\"}]}";
            } else {
                calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                int code = status.getOrDefault(name, 200);
                if (code == -1) { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } code = 200; }
                if (code != 200) { e.sendResponseHeaders(code, -1); e.close(); return; }
                body = op.equals("rerank") ? "{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}"
                        : "{\"data\":[{\"index\":0,\"embedding\":" + "[1,0,0,0]".substring(0, dims.getOrDefault(name, 2) * 2) + "]}]}";
                body = body.replace(",]", "]");
            }
            byte[] bytes = body.getBytes();
            try { e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { } finally { e.close(); }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private RemoteServer remote(String name, int timeout) {
        return new RemoteServer(name, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name + "/v1", "", timeout);
    }
    private int calls(String name) { return calls.getOrDefault(name, new AtomicInteger()).get(); }
    private static RemotePool.Call<String> embedCall() {
        return (http, s, t) -> { http.post(s, "/embeddings", new com.google.gson.JsonObject(), t, 4096); return s.name(); };
    }

    @Test void roundRobinSpreadsRequests() {
        try (var pool = new RemotePool("embedding", List.of(remote("a", 2), remote("b", 2)))) {
            var used = new ArrayList<String>();
            for (int i = 0; i < 4; i++) { var r = pool.execute(Duration.ofSeconds(5), embedCall()); used.add(r.server()); assertFalse(r.failover()); }
            assertEquals(List.of("a", "b", "a", "b"), used);
        }
    }

    @Test void retryableFailuresMoveToNextServerButClientErrorsFailImmediately() {
        for (int code : new int[] {500, 503, 429, -1}) {
            status.clear(); calls.clear(); status.put("a", code);
            try (var pool = new RemotePool("embedding", List.of(remote("a", 1), remote("b", 1)))) {
                var r = pool.execute(Duration.ofSeconds(5), embedCall());
                assertEquals("b", r.server(), "code " + code); assertTrue(r.failover());
                assertEquals(1, calls("a"), "서버당 1회"); assertEquals(1, calls("b"));
                assertEquals("down", pool.status().getFirst().state());
            }
        }
        status.clear(); calls.clear(); status.put("a", 400);
        try (var pool = new RemotePool("embedding", List.of(remote("a", 1), remote("b", 1)))) {
            var e = assertThrows(RemoteException.class, () -> pool.execute(Duration.ofSeconds(5), embedCall()));
            assertEquals(RemoteException.Kind.CLIENT_ERROR, e.kind()); assertEquals(0, calls("b"), "4xx는 다음 서버로 넘기지 않음");
            assertEquals("up", pool.status().getFirst().state(), "4xx는 연결 실패로 세지 않음");
            assertFalse(e.getMessage().contains("127.0.0.1"));
        }
        status.clear(); calls.clear(); status.put("a", 502); status.put("b", 502);
        try (var pool = new RemotePool("embedding", List.of(remote("a", 1), remote("b", 1)))) {
            var e = assertThrows(RemoteException.class, () -> pool.execute(Duration.ofSeconds(5), embedCall()));
            assertEquals(RemoteException.Kind.SERVER_ERROR, e.kind(), "모두 실패하면 실패 반환(대체 없음)");
        }
    }

    @Test void totalDeadlineBoundsFailover() {
        status.put("a", -1); status.put("b", -1);
        try (var pool = new RemotePool("embedding", List.of(remote("a", 1), remote("b", 1)))) {
            long start = System.nanoTime();
            var e = assertThrows(RemoteException.class, () -> pool.execute(Duration.ofMillis(1200), embedCall()));
            assertEquals(RemoteException.Kind.TIMEOUT, e.kind());
            assertTrue(System.nanoTime() - start < 1_900_000_000L, "전체 시간 상한");
        }
    }

    @Test void circuitOpensAfterConsecutiveFailuresAndRecoversWithOneTrial() {
        var now = new AtomicLong(1_000);
        status.put("a", 503);
        try (var pool = new RemotePool("embedding", List.of(remote("a", 1), remote("b", 1)), 3, 30_000, now::get)) {
            for (int i = 0; i < 6; i++) pool.execute(Duration.ofSeconds(5), embedCall());
            assertEquals(3, calls("a")); assertEquals("open", pool.status().getFirst().state());
            for (int i = 0; i < 4; i++) assertEquals("b", pool.execute(Duration.ofSeconds(5), embedCall()).server());
            assertEquals(3, calls("a"), "열린 동안 제외");
            now.addAndGet(30_000); status.remove("a");
            var used = new ArrayList<String>();
            for (int i = 0; i < 4; i++) used.add(pool.execute(Duration.ofSeconds(5), embedCall()).server());
            assertTrue(used.contains("a"), "시험 요청 성공 후 복귀");
            assertEquals("up", pool.status().getFirst().state());
            // 시험 요청이 실패하면 즉시 다시 연다.
            status.put("a", 503);
            for (int i = 0; i < 6; i++) pool.execute(Duration.ofSeconds(5), embedCall());
            int before = calls("a"); now.addAndGet(30_000);
            for (int i = 0; i < 4; i++) pool.execute(Duration.ofSeconds(5), embedCall());
            assertEquals(before + 1, calls("a")); assertEquals("open", pool.status().getFirst().state());
        }
    }

    @Test void traceRecordsServerOnlyWhenStarted() {
        status.put("a", 503);
        try (var pool = new RemotePool("reranker", List.of(remote("a", 1), remote("b", 1)))) {
            pool.execute(Duration.ofSeconds(5), embedCall());
            assertTrue(RemoteTrace.end().isEmpty());
            pool.execute(Duration.ofSeconds(5), embedCall()); // round_robin: 다음 요청은 다시 a부터
            RemoteTrace.begin();
            pool.execute(Duration.ofSeconds(5), embedCall());
            var trace = RemoteTrace.end();
            assertEquals(new RemoteTrace.Entry("reranker", "b", true), trace.get("reranker"));
        }
        assertThrows(IllegalArgumentException.class, () -> new RemotePool("x", List.of(remote("a", 1), remote("a", 1))));
        assertThrows(IllegalArgumentException.class, () -> new RemoteServer("bad name", "https://example.com", "", 1));
        assertThrows(IllegalArgumentException.class, () -> new RemoteServer("a", "https://user@example.com", "", 1));
        assertFalse(new RemoteServer("a", "https://example.com", "secret-value", 1).toString().contains("secret"));
    }

    @Test void verifyDetectsModelAndDimensionMismatchButAllowsDownServers() {
        models.put("a", "m"); models.put("b", "other"); dims.put("c", 3);
        var down = new RemoteServer("d", "http://127.0.0.1:9/v1", "", 1);
        try (var provider = new RemoteEmbeddingProvider(new RemotePool("embedding", List.of(remote("a", 2), remote("b", 2), remote("c", 2), down)), "m", null, 2, 1, 5)) {
            assertEquals("remote:m", provider.modelId(), "서버와 무관한 모델 ID");
            assertEquals("ok", provider.verify("a", Duration.ofSeconds(2)).result());
            assertEquals(new RemoteCheck("b", "mismatch", "model"), provider.verify("b", Duration.ofSeconds(2)));
            assertEquals(new RemoteCheck("c", "mismatch", "dimensions:3"), provider.verify("c", Duration.ofSeconds(2)));
            assertEquals("down", provider.verify("d", Duration.ofSeconds(2)).result());
            provider.pool().disable("mismatch");
            assertEquals(RemoteException.Kind.UNAVAILABLE, assertThrows(RemoteException.class, () -> provider.embed("x")).kind());
        }
        assertEquals("remote:https://example.com/v1/embeddings:m", RemoteEmbeddingProvider.legacyModelId("https://example.com/v1/", "m"));
    }

    @Test void remoteRerankerFailsOverAndKeepsOrderWhenAllFail() {
        var hits = List.of(new SearchIndex.Hit("s", "S", "s:a", 1, 0, "alpha", 0, 2), new SearchIndex.Hit("s", "S", "s:b", 1, 0, "beta", 0, 1));
        status.put("a", 429);
        try (var reranker = new RemoteRerankerProvider(new RemotePool("reranker", List.of(remote("a", 2), remote("b", 2))), "m", 10, 5)) {
            var result = reranker.reranked("q", hits);
            assertTrue(result.applied()); assertEquals("b", result.server()); assertTrue(result.failover());
            assertEquals(List.of(hits.get(1), hits.get(0)), result.hits());
            status.put("b", 500);
            var failed = reranker.reranked("q", hits);
            assertFalse(failed.applied()); assertSame(hits, failed.hits(), "모두 실패하면 원래 순서");
            models.put("b", "other");
            assertTrue(reranker.verify("b", Duration.ofSeconds(2)).mismatch());
        }
    }
}

package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.bluesounds.nasquire.core.remote.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 교차 검토 반영: 4xx 본문 비대기 즉시 실패, 원격 호출 마감 시각(남은 시간) 전달, 상한 예외 종류 보존. */
class CrossReview37Test {
    @TempDir Path temp;
    private final List<HttpServer> servers = new ArrayList<>();
    @AfterEach void stop() { servers.forEach(s -> s.stop(0)); }
    private HttpServer server() throws IOException {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newCachedThreadPool()); servers.add(s); return s;
    }
    private static String root(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    @Test void clientErrorFailsImmediatelyWithoutWaitingForBodyOrFailover() throws Exception {
        // 상태 줄·헤더로 401을 보낸 뒤 본문을 오래 지연하는 서버: 본문을 기다리지 않고 즉시 CLIENT_ERROR, 다음 서버로 넘기지 않는다.
        var slow = server(); var other = server(); var otherCalls = new AtomicInteger();
        slow.createContext("/", e -> {
            e.sendResponseHeaders(401, 1000); e.getResponseBody().write('{'); e.getResponseBody().flush();
            try { Thread.sleep(8_000); } catch (InterruptedException ignored) { }
            e.close();
        });
        other.createContext("/", e -> { otherCalls.incrementAndGet(); byte[] b = "{}".getBytes(); e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close(); });
        slow.start(); other.start();
        try (var pool = new RemotePool("embedding", List.of(new RemoteServer("a", root(slow), "", 30), new RemoteServer("b", root(other), "", 30)))) {
            long start = System.nanoTime();
            var error = assertThrows(RemoteException.class, () -> pool.execute(Duration.ofSeconds(20),
                    (http, server, limit) -> http.post(server, "/x", new com.google.gson.JsonObject(), limit, 1024)));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(RemoteException.Kind.CLIENT_ERROR, error.kind()); assertEquals(401, error.status());
            assertTrue(millis < 4_000, "본문 지연을 기다리지 않음: " + millis + "ms");
            assertEquals(0, otherCalls.get(), "4xx는 페일오버 없음");
        }
        // 429는 여전히 재시도 대상이다(본문 수신 뒤 다음 서버로).
        var limited = server();
        limited.createContext("/", e -> { byte[] b = "{}".getBytes(); e.sendResponseHeaders(429, b.length); e.getResponseBody().write(b); e.close(); });
        limited.start();
        try (var pool = new RemotePool("embedding", List.of(new RemoteServer("a", root(limited), "", 30), new RemoteServer("b", root(other), "", 30)))) {
            var result = pool.execute(Duration.ofSeconds(20), (http, server, limit) -> http.post(server, "/x", new com.google.gson.JsonObject(), limit, 1024));
            assertEquals("b", result.server()); assertTrue(result.failover());
        }
    }

    @Test void deadlineShrinksPoolAndDoclingTimeouts() throws Exception {
        // 남은 시간이 서버당·전체 시간 상한보다 짧으면 그만큼만 쓴다. Docling은 document_timeout도 남은 시간으로 보낸다.
        var docling = server(); var timeouts = new CopyOnWriteArrayList<Integer>();
        docling.createContext("/v1/convert/file", e -> {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            var m = Pattern.compile("name=\"document_timeout\"\r\n\r\n(\\d+)").matcher(body);
            timeouts.add(m.find() ? Integer.parseInt(m.group(1)) : -1);
            byte[] b = "{\"status\":\"success\",\"document\":{\"md_content\":\"| a | b |\\n|---|---|\\n| converted table text here |\"},\"errors\":[]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, b.length); e.getResponseBody().write(b); e.close();
        });
        docling.start();
        try (var client = new RemoteDoclingClient(new RemotePool("docling", List.of(new RemoteServer("d", root(docling), "", 300))), 300, RemoteDoclingClient.DEFAULT_MAX_FILE_BYTES)) {
            client.convert("# x".getBytes(), "a.md", null, false);
            assertEquals(300, timeouts.getLast(), "마감 없는 기존 경로는 그대로");
            client.convert("# x".getBytes(), "a.md", null, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
            assertTrue(timeouts.getLast() <= 20 && timeouts.getLast() >= 18, "남은 시간: " + timeouts.getLast());
            var expired = assertThrows(RemoteException.class, () -> client.convert("# x".getBytes(), "a.md", null, false, System.nanoTime() - 1));
            assertEquals(RemoteException.Kind.TIMEOUT, expired.kind());
            assertEquals(2, timeouts.size(), "마감이 지났으면 보내지 않는다");

            // DoclingPages: 쪽마다 시간 상한을 새로 시작하지 않고 같은 마감의 남은 시간을 쓴다. 마감이 지나면 남은 쪽은 1차 결과 유지(retry).
            var pages = new DoclingPages(() -> Optional.of(client), () -> LayoutCheck.Thresholds.DEFAULT, () -> 0, 0);
            Path pdf = Files.write(temp.resolve("a.pdf"), "%PDF-1.4 test".getBytes());
            var check = new LayoutCheck(LayoutCheck.Thresholds.DEFAULT, true);
            check.always(1); check.always(2);
            var parts = List.of(new DoclingPages.Part(1, Location.page(1), "first"), new DoclingPages.Part(2, Location.page(2), "second"));
            timeouts.clear();
            var outcome = pages.refine("r", Path.of("a.pdf"), pdf, check, parts, 10_000, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
            assertEquals(2, timeouts.size()); assertTrue(timeouts.stream().allMatch(t -> t <= 10), "쪽마다 남은 시간 이하: " + timeouts);
            assertEquals("converted", outcome.pages().get(1));
            timeouts.clear();
            var late = pages.refine("r", Path.of("a.pdf"), pdf, check, parts, 10_000, System.nanoTime() - 1);
            assertTrue(timeouts.isEmpty()); assertTrue(late.retry());
            assertEquals(List.of("retry", "retry"), List.copyOf(late.pages().values()));
            assertEquals("first", late.parts().getFirst().text(), "1차 결과 유지");
        }
    }

    @Test void extractorLimitsKeepTheirType() throws Exception {
        Path text = Files.writeString(temp.resolve("a.txt"), "x".repeat(100));
        var error = assertThrows(IOException.class, () -> new PlainTextExtractor().extract(text, (p, t) -> { }, 10));
        assertNotNull(ExtractionLimit.find(error), "글자 상한은 상한 종류");
        Path broken = Files.write(temp.resolve("a.docx"), new byte[] {1, 2, 3});
        var corrupt = assertThrows(IOException.class, () -> new OfficeTextExtractor().extract(broken, (p, t) -> { }, 10));
        assertNull(ExtractionLimit.find(corrupt), "손상 문서는 상한이 아니다");
        assertNotNull(ExtractionLimit.find(new IOException("wrapped", new RuntimeException(new ExtractionLimit("상한")))));
    }
}

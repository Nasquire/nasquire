package com.bluesounds.nasquire.core.remote;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 평문 http 본문 요청에 h2c 업그레이드 헤더를 붙이지 않는다(uvicorn 등은 업그레이드 요청 본문을 422로 거부). */
class RemoteHttpVersionTest {
    @Test
    void plainHttpPostHasNoH2cUpgrade() throws Exception {
        var upgrade = new AtomicReference<String>("unset");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upgrade.get() == null ? 200 : 422, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var http = new RemoteHttp()) {
            var target = new RemoteServer("local", "http://127.0.0.1:" + server.getAddress().getPort(), null, 5);
            var result = http.post(target, "/embeddings", new JsonObject(), Duration.ofSeconds(5), 1024);
            assertNull(upgrade.get());
            assertTrue(result.getAsJsonObject().get("ok").getAsBoolean());
        } finally {
            server.stop(0);
        }
    }
}

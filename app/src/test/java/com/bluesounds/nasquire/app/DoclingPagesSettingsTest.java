package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 2차 뒷부분(lite 포함): Docling 쪽 판단 기준값·재시도 상한 설정, 상태 집계(주소 비노출), 소스별 "항상 Docling" 저장값 호환. */
class DoclingPagesSettingsTest {
    @TempDir Path temp;
    private HttpServer server;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/version", e -> send(e, "{\"docling-serve\":\"1.38.2\"}"));
        server.createContext("/v1/convert/file", e -> { e.getRequestBody().readAllBytes(); send(e, "{\"document\":{\"md_content\":\"# ok\"},\"status\":\"success\",\"errors\":[]}"); });
        server.start();
    }
    private static void send(com.sun.net.httpserver.HttpExchange e, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void thresholdsAreConservativeValidatedAndOnlyActiveWhenConnected() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        try (var index = new SearchIndex(temp.resolve("index"))) {
            var crypto = new EmbeddingSettings(index, data, new MockEnvironment());
            try (var docling = new DoclingSettings(data, crypto)) {
                assertEquals(LayoutCheck.Thresholds.DEFAULT, docling.thresholds());
                assertEquals("3", docling.view().get("minGridLines")); assertEquals("40", docling.view().get("minImagePercent"));
                assertEquals("3", docling.view().get("maxRetries"));
                assertFalse(docling.pages().connected(), "off면 판단하지 않음");
                assertNull(docling.pages().check(Path.of("a.pdf"), false));
                for (var bad : List.of(Map.of("minGridLines", "1"), Map.of("minColumns", "21"), Map.of("minImagePercent", "101"), Map.of("maxPages", "0"), Map.of("maxRetries", "11")))
                    assertThrows(IllegalArgumentException.class, () -> docling.save(bad), bad.toString());
                var input = new HashMap<String, Object>(Map.of("docling", "remote", "minGridLines", "4", "maxPages", "5", "maxRetries", "1"));
                input.put("servers", List.of(Map.of("name", "docling-a", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())));
                docling.save(input);
                assertTrue(docling.pages().connected());
                assertEquals(new LayoutCheck.Thresholds(4, 3, 4, 40, 5), docling.thresholds());
                assertEquals(1, docling.pages().maxRetries());
                assertNotNull(docling.pages().check(Path.of("a.pdf"), false));
                assertNull(docling.pages().check(Path.of("a.hwpx"), true), "HWPX는 대상 아님");
                @SuppressWarnings("unchecked") var health = (Map<String, Object>)docling.health().get("pages");
                assertEquals(Map.of("converted", 0L, "failed", 0L, "skipped", 0L, "retryWaiting", 0), health);
                assertFalse(docling.health().toString().contains("127.0.0.1"));
                assertTrue(docling.view().containsKey("pages"));
                // 다시 읽어도 기준값 유지.
                docling.save(Map.of("docling", "off"));
            }
            try (var again = new DoclingSettings(data, crypto)) {
                assertEquals(4, again.thresholds().minGridLines()); assertFalse(again.pages().connected());
            }
        }
    }

    @Test void alwaysDoclingColumnIsAddedWithFalseForExistingSources() throws Exception {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        // 이전 버전 소스 표(always_docling 없음).
        db.execute("CREATE TABLE sources (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, root VARCHAR(4096) NOT NULL, includes CLOB NOT NULL, excludes CLOB NOT NULL, kind VARCHAR(20) DEFAULT 'directory' NOT NULL)");
        db.update("INSERT INTO sources(id,name,root,includes,excludes) VALUES ('old','docs','/data/docs','','')");
        var storage = new Storage(db);
        assertFalse(storage.source("old").alwaysDocling());
        var source = storage.source("old");
        storage.save(new Source(source.id(), source.name(), source.root(), source.includes(), source.excludes(), source.kind(), true));
        assertTrue(storage.source("old").alwaysDocling());
        assertTrue(storage.source("old").sameScope(source), "범위는 그대로");
    }
}

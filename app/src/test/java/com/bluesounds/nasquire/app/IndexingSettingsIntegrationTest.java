package com.bluesounds.nasquire.app;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndexingSettingsIntegrationTest {
    private static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-integration-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString());
        r.add("nasquire.upload-max-bytes", () -> "4096");
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "50");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
    @Autowired LoginAttempts attempts;
    @Autowired DocumentService documents;
    @Autowired UploadService uploads;
    @Autowired DownloadService downloads;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String csrf;
    HttpResponse<String> call(String path, String method, Object body, String bearer, boolean useCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (useCsrf) request.header("X-CSRF-TOKEN", csrf);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (path.equals("/mcp")) request.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response, int status) { assertEquals(status, response.statusCode(), response.body()); return mapper.readTree(response.body()); }
    void auth() throws Exception { csrf = json(call("/api/auth", "GET", null, null, false), 200).get("csrfToken").asText(); }
    String username = "admin", password = "example-password-123";
    int login() throws Exception {
        auth();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login")).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(); auth(); return status;
    }
    @Autowired IndexingSettings settings;
    @Test void settingsAndSupportedFormats() throws Exception {
        auth();
        assertEquals(401, call("/api/indexing", "GET", null, null, false).statusCode());
        assertEquals(200, call("/api/setup", "POST", Map.of("username", username, "password", password), null, true).statusCode());
        assertEquals(200, login());
        var valid = Map.of("scanMillis", 60000L, "maxFileBytes", 1048576L);
        assertEquals(403, call("/api/indexing", "PUT", valid, null, false).statusCode());
        for (long interval : new long[]{59999, 3600001})
            assertEquals(400, call("/api/indexing", "PUT", Map.of("scanMillis", interval, "maxFileBytes", 1048576), null, true).statusCode());
        for (long maximum : new long[]{1048575, 536870913})
            assertEquals(400, call("/api/indexing", "PUT", Map.of("scanMillis", 60000, "maxFileBytes", maximum), null, true).statusCode());
        assertEquals(200, call("/api/indexing", "PUT", valid, null, true).statusCode());
        var loaded = new IndexingSettings(TEMP.resolve("data"), 999, 99);
        assertEquals(valid, loaded.view());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit WHERE action='indexing-save'", Integer.class));
        var formats = json(call("/api/supported-extensions", "GET", null, null, false), 200);
        assertTrue(formats.toString().contains("hwpx"));
        boolean full = json(call("/api/full", "GET", null, null, false), 200).get("available").asBoolean();
        assertEquals(full, formats.toString().contains("png"));
        Path root = Files.createDirectory(TEMP.resolve("source"));
        var source = json(call("/api/sources", "POST", Map.of("name", "Sample", "path", root.toString()), null, true), 200);
        String scan = "/api/sources/" + source.get("id").asText() + "/scan";
        assertEquals(403, call(scan, "POST", null, null, false).statusCode());
        assertEquals(200, call(scan, "POST", null, null, true).statusCode());
    }
}

package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShareController {
    record Input(String source, String path, Long expiresAt, String password, Long maxDownloads) { }
    private final ShareService shares;
    private final Storage storage;
    ShareController(ShareService shares, Storage storage) { this.shares = shares; this.storage = storage; }
    @GetMapping("/api/shares") Object list() { return shares.list(); }
    @PostMapping("/api/shares") Object create(@RequestBody Input input) throws IOException {
        return shares.create(input.source, input.path, input.expiresAt, input.password, input.maxDownloads);
    }
    @DeleteMapping("/api/shares/{id}") void revoke(@PathVariable String id) { shares.revoke(id); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String password(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() > 512 || !header.startsWith("Basic ")) return null;
        try {
            String pair = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':'); return colon < 0 ? null : pair.substring(colon + 1);
        } catch (IllegalArgumentException e) { return null; }
    }
    @GetMapping("/s/{token}") void publicShare(@PathVariable String token, @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "false") boolean download, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            var target = shares.authorize(token, path, password(request));
            if (download || !target.directory()) {
                try (var channel = shares.open(target); var input = Channels.newInputStream(channel)) {
                    response.setContentType("application/octet-stream");
                    response.setHeader("Content-Disposition", "attachment; filename=\"download\"; filename*=UTF-8''" + encode(target.relative().getFileName().toString()));
                    input.transferTo(response.getOutputStream());
                }
            } else {
                // 쿼리에는 공유 루트 기준 하위 경로만 표시한다. 소스 ID·루트·상위 경로를 보내지 않는다.
                StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Nasquire share</title><body><main><h1>Nasquire</h1><p>Shared files / 공유 파일</p><ul>");
                for (var entry : shares.entries(target)) {
                    String name = entry.get("name").toString(), child = path.isEmpty() ? name : path + "/" + name;
                    html.append("<li><a href=\"?path=").append(encode(child)).append(Boolean.TRUE.equals(entry.get("directory")) ? "" : "&amp;download=true")
                        .append("\">").append(escape(name)).append(Boolean.TRUE.equals(entry.get("directory")) ? "/" : "").append("</a></li>");
                }
                response.setContentType("text/html;charset=UTF-8"); response.getWriter().write(html.append("</ul></main></body></html>").toString());
                storage.audit("share-visitor", "share-list", 200);
            }
        } catch (ShareService.PasswordRequired e) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Nasquire share\", charset=\"UTF-8\""); response.setStatus(401);
        } catch (ShareService.Delayed e) { response.setHeader("Retry-After", "900"); response.setStatus(429);
        } catch (IOException | RuntimeException e) {
            if (!response.isCommitted()) { response.resetBuffer(); response.setStatus(404); }
            storage.audit("share-visitor", "share-access", 404);
        }
    }
}

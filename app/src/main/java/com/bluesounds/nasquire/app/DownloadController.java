package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class DownloadController {
    private final DownloadService downloads;
    private final Storage storage;
    public DownloadController(DownloadService downloads, Storage storage) { this.downloads = downloads; this.storage = storage; }
    static String requestBase(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request).replacePath(request.getContextPath()).replaceQuery(null).build().toUriString();
    }
    @GetMapping("/api/download") Object issue(@RequestParam String path, HttpServletRequest request) throws IOException {
        return downloads.issue((Storage.Identity)request.getAttribute("nasquire.token"), path, requestBase(request));
    }
    @GetMapping("/download") void download(@RequestParam String ticket, HttpServletResponse response) throws IOException {
        int status = 200;
        try {
            String path = downloads.verify(ticket);
            try (var channel = downloads.open(path); var input = Channels.newInputStream(channel)) {
                String filename = Path.of(path.substring(path.indexOf(':') + 1)).getFileName().toString();
                String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
                response.setContentType("application/octet-stream");
                response.setHeader("Content-Disposition", "attachment; filename=\"download\"; filename*=UTF-8''" + encoded);
                response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("X-Content-Type-Options", "nosniff");
                input.transferTo(response.getOutputStream());
            }
        } catch (IOException | RuntimeException e) { status = 403; throw e; }
        finally { storage.audit("download-link", "download-receive", status); }
    }
}

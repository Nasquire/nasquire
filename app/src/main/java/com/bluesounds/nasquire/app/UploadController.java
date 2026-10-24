package com.bluesounds.nasquire.app;

import java.io.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads/{id}")
public class UploadController {
    record Operation(String path, String destination, Boolean overwrite) { }
    private final UploadService uploads;
    UploadController(UploadService uploads) { this.uploads = uploads; }
    @GetMapping Object list(@PathVariable String id, @RequestParam(defaultValue = "") String path) throws IOException { return uploads.list(id, path); }
    @PostMapping(consumes = "multipart/form-data") void upload(@PathVariable String id, @RequestParam String path,
            @RequestParam(defaultValue = "false") boolean overwrite, @RequestParam MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) { uploads.upload(id, path, input, overwrite); }
    }
    @PostMapping("/folder") void folder(@PathVariable String id, @RequestBody Operation op) throws IOException { uploads.folder(id, op.path); }
    @PostMapping("/move") void move(@PathVariable String id, @RequestBody Operation op) throws IOException { uploads.move(id, op.path, op.destination, Boolean.TRUE.equals(op.overwrite)); }
    @PostMapping("/trash") Object trash(@PathVariable String id, @RequestBody Operation op) throws IOException { return Map.of("id", uploads.trash(id, op.path)); }
    @GetMapping("/trash") Object trash(@PathVariable String id) throws IOException { return uploads.trashList(id); }
    @PostMapping("/restore/{item}") void restore(@PathVariable String id, @PathVariable String item) throws IOException { uploads.restore(id, item); }
}

package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.IndexingService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 환경 값은 최초 기본값이며 저장한 화면 설정을 우선한다. */
@Component
public final class IndexingSettings {
    private final Path file;
    private long scanMillis, maxFileBytes;
    public IndexingSettings(Path dataFolder,
            @Value("${nasquire.scan-millis:300000}") long scanMillis,
            @Value("${nasquire.max-file-bytes:33554432}") long maxFileBytes) throws IOException {
        file = dataFolder.resolve("indexing.properties");
        var properties = new Properties();
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { properties.load(in); }
        this.scanMillis = Long.parseLong(properties.getProperty("scanMillis", Long.toString(scanMillis)));
        this.maxFileBytes = Long.parseLong(properties.getProperty("maxFileBytes", Long.toString(maxFileBytes)));
        if (this.scanMillis < 100 || this.maxFileBytes < 1) throw new IllegalArgumentException("색인 기본 설정 오류");
    }
    public synchronized Map<String, Long> view() { return Map.of("scanMillis", scanMillis, "maxFileBytes", maxFileBytes); }
    public synchronized void save(long interval, long maximum, IndexingService indexing) throws IOException {
        if (interval < 60000 || interval > 3600000 || maximum < 1048576 || maximum > 536870912)
            throw new IllegalArgumentException("검사 주기 1분~1시간, 파일 상한 1~512 MiB 범위로 입력하세요");
        var properties = new Properties();
        properties.setProperty("scanMillis", Long.toString(interval));
        properties.setProperty("maxFileBytes", Long.toString(maximum));
        Path temporary = Files.createTempFile(file.getParent(), "indexing-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temporary)) { properties.store(out, "Nasquire indexing"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        scanMillis = interval; maxFileBytes = maximum;
        indexing.maxBytes(maximum); indexing.reschedule(interval);
    }
}

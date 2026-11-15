package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * full 전용 로컬 Tesseract 실행 제한(시간·메모리). OCR 선택(off·tesseract·remote)과 쪽 상한은 {@link OcrSettings}의 일반 설정으로 옮겼다.
 * 이전 full.properties의 ocr·ocrMaxPages는 ocr.properties로 이관된 뒤, 리랭커 항목은 reranker.properties로 이관된 뒤 다음 저장 때 지운다.
 */
@Service
public final class FullSettings implements AutoCloseable {
    private final Path folder, file;
    private Properties settings = defaults();
    static final Set<String> FIELDS = Set.of("ocrTimeoutSeconds", "ocrMemoryMiB");
    /** 이전 /api/full의 OCR 선택 항목. 요청에 오면 OCR 일반 설정으로 넘긴다. */
    static final Set<String> LEGACY_OCR = Set.of("ocr", "ocrMaxPages");
    public FullSettings(Path dataFolder) throws Exception {
        folder = dataFolder; file = folder.resolve("full.properties");
        if (Files.exists(file)) {
            var stored = new Properties(); try (var in = Files.newInputStream(file)) { stored.load(in); }
            for (String k : FIELDS) if (stored.containsKey(k)) settings.setProperty(k, stored.getProperty(k));
        }
        limits(settings);
    }
    private static Properties defaults() {
        var p = new Properties();
        p.setProperty("ocrTimeoutSeconds", "15"); p.setProperty("ocrMemoryMiB", "512");
        return p;
    }
    public synchronized Map<String, Object> view() {
        var result = new LinkedHashMap<String, Object>();
        for (String k : FIELDS) result.put(k, settings.getProperty(k));
        result.put("available", available());
        return result;
    }
    static boolean available() { return ServiceLoader.load(Providers.FullProvider.class).findFirst().isPresent(); }
    /** 로컬 Tesseract 공급자. full 빌드가 없으면 IllegalStateException. */
    synchronized Providers.OcrProvider tesseract() {
        int[] limits = limits(settings);
        return Providers.full().tesseract(folder, limits[0], limits[1]);
    }
    private static int[] limits(Properties p) {
        return new int[] {number(p, "ocrTimeoutSeconds", 1, 60), number(p, "ocrMemoryMiB", 128, 2048)};
    }
    /** 저장 뒤 Tesseract 제한이 바뀌었으면 true. */
    public synchronized boolean save(Map<String, String> input) throws Exception {
        var next = new Properties(); next.putAll(settings);
        for (var e : input.entrySet()) {
            if (!FIELDS.contains(e.getKey()) || e.getValue() == null || e.getValue().length() > 4096) throw new IllegalArgumentException("full 설정 오류");
            next.setProperty(e.getKey(), e.getValue());
        }
        limits(next);
        boolean changed = FIELDS.stream().anyMatch(k -> !Objects.equals(settings.getProperty(k), next.getProperty(k)));
        var stored = new Properties();
        if (Files.exists(file)) {
            var old = new Properties(); try (var in = Files.newInputStream(file)) { old.load(in); }
            // 아직 이관되지 않은 이전 리랭커·OCR 선택 항목은 보존한다.
            if (!Files.exists(folder.resolve("reranker.properties")))
                for (String k : RerankerSettings.LEGACY_FULL) if (old.containsKey(k)) stored.setProperty(k, old.getProperty(k));
            if (!Files.exists(folder.resolve("ocr.properties")))
                for (String k : LEGACY_OCR) if (old.containsKey(k)) stored.setProperty(k, old.getProperty(k));
        }
        for (String k : FIELDS) stored.setProperty(k, next.getProperty(k));
        Path temporary = Files.createTempFile(folder, "full-", ".properties");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            try (var out = Files.newOutputStream(temporary)) { stored.store(out, "Nasquire full settings"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        settings = next;
        return changed;
    }
    private static int number(Properties p, String name, int min, int max) {
        int n = Integer.parseInt(p.getProperty(name));
        if (n < min || n > max) throw new IllegalArgumentException("full 제한 오류"); return n;
    }
    public synchronized void close() { }
}

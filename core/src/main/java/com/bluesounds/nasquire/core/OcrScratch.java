package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/** 데이터 폴더 ocr-tmp의 OCR 임시 폴더(page-*, job-*). 앱 시작 때 OCR 사용 여부와 무관하게 오래된 것을 정리한다. */
public final class OcrScratch {
    private OcrScratch() { }
    public static Path folder(Path data) { return data.resolve("ocr-tmp"); }

    /** 강제 종료로 남은 24시간 지난 임시 폴더만 링크를 따르지 않고 지운다. 진행 중 작업과 겹치지 않게 오래된 것만 대상이다. */
    public static void cleanStale(Path data) {
        Path root = folder(data);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith("page-") && !name.startsWith("job-")) continue;
                try {
                    var attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.lastModifiedTime().toMillis() >= cutoff) continue;
                    if (attributes.isDirectory()) deleteTree(entry); else Files.deleteIfExists(entry);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /** 링크는 따라가지 않고 링크 자체만 지운다. 깊이는 임시 폴더 구조에 맞춰 4로 제한한다. */
    public static void deleteTree(Path folder) throws IOException {
        Files.walkFileTree(folder, java.util.EnumSet.noneOf(FileVisitOption.class), 4, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

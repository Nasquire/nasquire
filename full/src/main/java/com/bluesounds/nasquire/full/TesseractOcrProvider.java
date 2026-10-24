package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.Providers;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Linux prlimit로 주소 공간·출력 파일·CPU를 제한한다. 셸과 소스 경로는 전달하지 않는다. */
public final class TesseractOcrProvider implements Providers.OcrProvider {
    private final Path temporary;
    private final int seconds, memoryMiB;
    private final String executable;
    public TesseractOcrProvider(Path data, int seconds, int memoryMiB) {
        this(data, seconds, memoryMiB, "/usr/bin/tesseract");
    }
    TesseractOcrProvider(Path data, int seconds, int memoryMiB, String executable) {
        if (seconds < 1 || seconds > 60 || memoryMiB < 128 || memoryMiB > 2048) throw new IllegalArgumentException("OCR 제한 오류");
        this.temporary = data.resolve("ocr-tmp"); this.seconds = seconds; this.memoryMiB = memoryMiB; this.executable = executable;
        cleanOldPages();
    }
    /** 이전 실행이 강제 종료되어 남은 24시간 지난 page-* 임시 폴더를 정리한다. 진행 중 작업과 겹치지 않게 오래된 것만 지운다. */
    private void cleanOldPages() {
        if (!Files.isDirectory(temporary, LinkOption.NOFOLLOW_LINKS)) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        try (var pages = Files.newDirectoryStream(temporary, "page-*")) {
            for (Path page : pages) {
                if (!Files.isDirectory(page, LinkOption.NOFOLLOW_LINKS) || Files.getLastModifiedTime(page, LinkOption.NOFOLLOW_LINKS).toMillis() >= cutoff) continue;
                try (var files = Files.newDirectoryStream(page)) {
                    for (Path f : files) if (!Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(f);
                }
                try { Files.deleteIfExists(page); } catch (DirectoryNotEmptyException ignored) { }
            }
        } catch (IOException ignored) { }
    }
    public synchronized String recognize(byte[] image, String language) {
        if (!"kor+eng".equals(language) || image.length == 0 || image.length > 16 * 1024 * 1024) throw new IllegalArgumentException("OCR 입력 오류");
        Path folder = null; Process process = null;
        try {
            Files.createDirectories(temporary);
            folder = Files.createTempDirectory(temporary, "page-");
            Path input = folder.resolve("input.png"), output = folder.resolve("result");
            Files.write(input, image);
            var command = new ProcessBuilder("/usr/bin/prlimit", "--as=" + (memoryMiB * 1024L * 1024L), "--cpu=" + seconds,
                    "--fsize=1048576", "--", executable, input.toString(), output.toString(), "-l", language, "--psm", "3");
            // 부모 환경(비밀값 포함 가능)을 넘기지 않는다. 학습 데이터 위치는 운영자가 지정한 경우만 전달한다.
            var environment = command.environment(); String tessdata = environment.get("TESSDATA_PREFIX");
            environment.clear();
            environment.put("PATH", "/usr/bin:/bin"); environment.put("LANG", "C.UTF-8"); environment.put("OMP_THREAD_LIMIT", "1");
            if (tessdata != null && !tessdata.isBlank()) environment.put("TESSDATA_PREFIX", tessdata);
            command.redirectOutput(ProcessBuilder.Redirect.DISCARD); command.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = command.start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("OCR 시간 상한 초과");
            Path result = folder.resolve("result.txt");
            if (process.exitValue() != 0 || !Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) > 1048576) throw new IOException("OCR 실행 실패");
            return Files.readString(result);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("OCR 중단"); }
        catch (IOException e) { throw new IllegalStateException("OCR 실행 파일·언어 데이터·제한을 확인하세요"); }
        finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (folder != null) {
                try (var files = Files.list(folder)) { for (Path p : files.toList()) Files.deleteIfExists(p); }
                catch (IOException ignored) { }
                try { Files.deleteIfExists(folder); } catch (IOException ignored) { }
            }
        }
    }
}

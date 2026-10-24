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
            command.environment().put("OMP_THREAD_LIMIT", "1");
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

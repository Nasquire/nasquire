package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.OcrScratch;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/**
 * OCR 렌더링·디코딩을 JVM 밖 자식 프로세스({@link OcrWorker})로 격리한다. 같은 클래스 경로의 java를 작은 힙과 prlimit(주소 공간·CPU·파일 크기)로
 * 실행하고, 환경을 비우며, 데이터 폴더 ocr-tmp 안 0700 작업 폴더만 쓰게 한다. 원본은 부모가 링크를 따르지 않고 읽기 전용으로 열어
 * 작업 폴더에 복사하므로 자식은 소스 폴더를 열지 않는다. 파일 단위 전체 시간 상한을 넘기면 자손까지 강제 종료하고 회수한다.
 * 자식 실행은 프로세스 전체에서 동시에 1개다.
 */
final class OcrSandbox {
    private static final Semaphore SLOT = new Semaphore(1, true);
    /** 복사 입력 상한. 색인기 파일 크기 상한·ML 요청 상한보다 크다. */
    static final long MAX_INPUT = 1024L * 1024 * 1024;
    static final long BASE_MILLIS = 15_000, PAGE_MILLIS = 10_000, MAX_MILLIS = 300_000;
    static final int HEAP_MIB = 192, ADDRESS_MIB = 2048;
    private final Path temporary;
    private final String mainClass;
    private final long baseMillis, pageMillis;

    OcrSandbox(Path data) { this(data, OcrWorker.class.getName(), BASE_MILLIS, PAGE_MILLIS); }
    /** 테스트는 멈추는 가짜 자식 main과 짧은 시간 상한을 쓴다. */
    OcrSandbox(Path data, String mainClass, long baseMillis, long pageMillis) {
        if (baseMillis < 1 || pageMillis < 0) throw new IllegalArgumentException("OCR 시간 상한 오류");
        this.temporary = OcrScratch.folder(data).toAbsolutePath(); this.mainClass = mainClass; this.baseMillis = baseMillis; this.pageMillis = pageMillis;
        OcrScratch.cleanStale(data);
    }

    /** 자식이 만든 쪽 PNG. 닫으면 작업 폴더를 지운다. */
    static final class Job implements AutoCloseable {
        private final Path folder; private final int count;
        private Job(Path folder, int count) { this.folder = folder; this.count = count; }
        int count() { return count; }
        /** 링크를 따르지 않고 일반 파일만 상한 안에서 읽는다. */
        byte[] page(int n) throws IOException {
            if (n < 1 || n > count) throw new IOException("OCR 쪽 번호 오류");
            return readBounded(folder.resolve("out-" + n + ".png"), OcrWorker.MAX_PNG);
        }
        public void close() { delete(folder); }
    }

    Job pdf(Path source, List<Integer> pages, int maxPages) throws IOException {
        var list = new StringJoiner(",");
        for (int page : pages) list.add(String.valueOf(page));
        return run("pdf", maxPages, list.toString(), pages.size(), folder -> copy(source, folder.resolve("input")));
    }
    Job image(Path source, int maxPages) throws IOException {
        return run("image", maxPages, "", maxPages, folder -> copy(source, folder.resolve("input")));
    }
    Job single(byte[] data) throws IOException {
        return run("single", 1, "", 1, folder -> {
            try (var out = FileChannel.open(folder.resolve("input"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) out.write(buffer);
            }
        });
    }
    private interface Prepare { void into(Path folder) throws IOException; }

    private Job run(String mode, int maxPages, String pages, int units, Prepare prepare) throws IOException {
        long limit = Math.min(MAX_MILLIS, baseMillis + pageMillis * units);
        try { if (!SLOT.tryAcquire(limit + 10_000, TimeUnit.MILLISECONDS)) throw new IOException("OCR 작업자 사용 중"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("OCR 중단"); }
        Path folder = null; Process process = null; boolean done = false;
        try {
            Files.createDirectories(temporary);
            folder = Files.createTempDirectory(temporary, "job-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            prepare.into(folder);
            var command = command(mode, folder, maxPages, pages, limit);
            var builder = new ProcessBuilder(command).directory(folder.toFile());
            // 부모 환경(비밀값·JAVA_TOOL_OPTIONS 포함 가능)을 넘기지 않는다.
            var environment = builder.environment(); environment.clear();
            environment.put("PATH", "/usr/bin:/bin"); environment.put("LANG", "C.UTF-8"); environment.put("MALLOC_ARENA_MAX", "2");
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            boolean finished;
            try { finished = process.waitFor(limit, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("OCR 중단"); }
            if (!finished) throw new IOException("이미지 렌더링·디코딩 시간 초과");
            int exit = process.exitValue();
            if (exit == OcrWorker.LIMIT) throw new OcrTextExtractor.Limit("OCR 이미지 상한 초과 또는 입력 오류");
            if (exit == OcrWorker.MEMORY) throw new IOException("이미지 처리 메모리 부족");
            if (exit != OcrWorker.OK) throw new IOException("OCR 이미지 작업자 실패");
            int count;
            try { count = Integer.parseInt(new String(readBounded(folder.resolve("count"), 16), java.nio.charset.StandardCharsets.US_ASCII).trim()); }
            catch (NumberFormatException e) { throw new IOException("OCR 이미지 작업자 출력 오류"); }
            if (count < 1 || count > maxPages || (!pages.isEmpty() && count != units)) throw new IOException("OCR 이미지 작업자 출력 오류");
            done = true;
            return new Job(folder, count);
        } finally {
            if (process != null) kill(process);
            SLOT.release();
            if (!done && folder != null) delete(folder);
        }
    }

    /** 자손을 먼저, 그다음 자식을 강제 종료하고 회수한다. 정상 종료 뒤에도 남은 자손이 없게 한다. */
    static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private List<String> command(String mode, Path folder, int maxPages, String pages, long limitMillis) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long cpu = Math.max(1, (limitMillis + 999) / 1000);
        var command = new ArrayList<>(List.of("/usr/bin/prlimit", "--as=" + (ADDRESS_MIB * 1024L * 1024L), "--cpu=" + cpu,
                "--fsize=" + OcrWorker.MAX_OUTPUT, "--core=0", "--", java,
                "-Xms16m", "-Xmx" + HEAP_MIB + "m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:ActiveProcessorCount=1",
                "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=96m", "-XX:CompressedClassSpaceSize=64m", "-XX:MaxDirectMemorySize=16m",
                "-XX:-UsePerfData", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + folder, "-Dpdfbox.fontcache=" + folder));
        // 자식의 작업 디렉터리는 작업 폴더이므로 상대 경로 클래스 경로를 절대 경로로 바꾼다.
        var entries = new StringJoiner(File.pathSeparator);
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator))
            if (!entry.isEmpty()) entries.add(Path.of(entry).toAbsolutePath().toString());
        String classPath = entries.toString();
        String bootJar = bootJar(classPath);
        if (bootJar != null && mainClass.equals(OcrWorker.class.getName())) {
            // 실행 JAR(java -jar)은 앱 main이 Spring보다 먼저 자식 모드로 분기한다.
            command.addAll(List.of("-jar", bootJar, "--nasquire.mode=ocr-worker"));
        } else command.addAll(List.of("-cp", classPath, mainClass));
        command.addAll(List.of(mode, folder.toString(), String.valueOf(maxPages)));
        if (!pages.isEmpty()) command.add(pages);
        return command;
    }
    private static String bootJar(String classPath) {
        if (classPath.isEmpty() || classPath.contains(File.pathSeparator) || !classPath.endsWith(".jar")) return null;
        try (var jar = new java.util.jar.JarFile(classPath)) { return jar.getEntry("BOOT-INF/") != null || jar.getEntry("BOOT-INF/lib/") != null ? classPath : null; }
        catch (IOException e) { return null; }
    }

    /** 원본은 마지막 경로 요소가 링크면 열지 않고 읽기 전용으로 연다. 일반 파일·크기 상한을 연 뒤 다시 확인한다. */
    static void copy(Path source, Path target) throws IOException {
        var attributes = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_INPUT) throw new IOException("OCR 원본 파일 오류");
        try (var in = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long size = in.size();
            if (size > MAX_INPUT) throw new IOException("OCR 원본 파일 오류");
            long position = 0;
            while (position < size) {
                long n = in.transferTo(position, Math.min(size - position, MAX_INPUT - position), out);
                if (n <= 0) break;
                position += n;
            }
        }
    }
    static byte[] readBounded(Path file, long max) throws IOException {
        var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > max) throw new IOException("OCR 이미지 작업자 출력 오류");
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > max) throw new IOException("OCR 이미지 작업자 출력 오류");
            var buffer = ByteBuffer.allocate((int)channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            return buffer.array();
        }
    }
    private static void delete(Path folder) {
        try { OcrScratch.deleteTree(folder); } catch (IOException ignored) { }
    }
}

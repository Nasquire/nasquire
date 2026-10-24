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
 * 작업 폴더에 복사하므로 자식은 소스 폴더를 열지 않는다. 복사와 자식 실행을 합친 파일 단위 전체 시간 상한을 넘기면 자식의 프로세스 그룹
 * (setsid)과 미리 확보한 자손 목록을 강제 종료하고, 모두 끝난 것을 확인한 뒤에 슬롯을 돌려주고 작업 폴더를 지운다.
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
        Path folder = OcrScratch.folder(data).toAbsolutePath();
        try { Files.createDirectories(folder); folder = folder.toRealPath(); } catch (IOException e) { throw new UncheckedIOException("OCR 임시 폴더 생성 실패", e); }
        // 실경로로 고정해 결과 PNG를 루트부터 NOFOLLOW로 다시 열 수 있게 한다.
        this.temporary = folder; this.mainClass = mainClass; this.baseMillis = baseMillis; this.pageMillis = pageMillis;
        OcrScratch.cleanStale(data);
    }
    /** 테스트용: 슬롯이 반환되었는지 확인한다. */
    static int availableSlots() { return SLOT.availablePermits(); }

    /** 자식이 만든 쪽 PNG. 닫으면 작업 폴더를 지운다. */
    static final class Job implements AutoCloseable {
        private final Path folder; private final int count; private long read;
        private Job(Path folder, int count) { this.folder = folder; this.count = count; }
        int count() { return count; }
        /** 링크를 따르지 않고 열린 fd가 일반 파일일 때만 쪽·합계 상한 안에서 읽는다. */
        byte[] page(int n) throws IOException {
            if (n < 1 || n > count) throw new IOException("OCR 쪽 번호 오류");
            byte[] png = readBounded(folder.resolve("out-" + n + ".png"), OcrWorker.MAX_PNG);
            read += png.length;
            if (read > OcrWorker.MAX_OUTPUT) throw new IOException("OCR 이미지 작업자 출력 상한 초과");
            return png;
        }
        public void close() { delete(folder); }
    }

    Job pdf(Path source, List<Integer> pages, int maxPages) throws IOException {
        var list = new StringJoiner(",");
        for (int page : pages) list.add(String.valueOf(page));
        return run("pdf", maxPages, list.toString(), pages.size(), (folder, deadline) -> copy(source, folder.resolve("input"), deadline));
    }
    Job image(Path source, int maxPages) throws IOException {
        return run("image", maxPages, "", maxPages, (folder, deadline) -> copy(source, folder.resolve("input"), deadline));
    }
    Job single(byte[] data) throws IOException {
        return run("single", 1, "", 1, (folder, deadline) -> {
            try (var out = FileChannel.open(folder.resolve("input"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) out.write(buffer);
            }
        });
    }
    private interface Prepare { void into(Path folder, long deadlineNanos) throws IOException; }

    private Job run(String mode, int maxPages, String pages, int units, Prepare prepare) throws IOException {
        long limit = Math.min(MAX_MILLIS, baseMillis + pageMillis * units);
        try { if (!SLOT.tryAcquire(limit + 10_000, TimeUnit.MILLISECONDS)) throw new IOException("OCR 작업자 사용 중"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("OCR 중단"); }
        // 원본 복사부터 자식 종료까지 하나의 시간 상한에 넣는다.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limit);
        Path folder = null; Process process = null; boolean done = false;
        try {
            Files.createDirectories(temporary);
            folder = Files.createTempDirectory(temporary, "job-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            prepare.into(folder, deadline);
            var command = command(mode, folder, maxPages, pages, limit);
            var builder = new ProcessBuilder(command).directory(folder.toFile());
            // 부모 환경(비밀값·JAVA_TOOL_OPTIONS 포함 가능)을 넘기지 않는다.
            var environment = builder.environment(); environment.clear();
            environment.put("PATH", "/usr/bin:/bin"); environment.put("LANG", "C.UTF-8"); environment.put("MALLOC_ARENA_MAX", "2");
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            boolean finished;
            try { finished = process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("OCR 중단"); }
            if (!finished) throw new IOException("이미지 렌더링·디코딩 시간 초과");
            int exit = process.exitValue();
            // 결과 검사 전에 남은 자손도 회수해 출력 파일 변경을 막는다. 미회수 시 finally의 회수자가 슬롯을 보유한다.
            if (!kill(process, 5_000)) throw new IOException("OCR 자손 종료 확인 실패");
            process = null;
            if (exit == OcrWorker.LIMIT) throw new OcrTextExtractor.Limit("OCR 이미지 상한 초과 또는 입력 오류");
            if (exit == OcrWorker.MEMORY) throw new IOException("이미지 처리 메모리 부족");
            if (exit != OcrWorker.OK) throw new IOException("OCR 이미지 작업자 실패");
            int count;
            try { count = Integer.parseInt(new String(readBounded(folder.resolve("count"), 16), java.nio.charset.StandardCharsets.US_ASCII).trim()); }
            catch (NumberFormatException e) { throw new IOException("OCR 이미지 작업자 출력 오류"); }
            if (count < 1 || count > maxPages || (!pages.isEmpty() && count != units)) throw new IOException("OCR 이미지 작업자 출력 오류");
            checkOutput(folder, count);
            done = true;
            return new Job(folder, count);
        } finally {
            // 자식·자손 종료를 확인한 뒤에만 슬롯을 돌려주고(실패 시) 작업 폴더를 지운다. 확인이 늦으면 백그라운드에서 이어서 확인한다.
            Path cleanup = done ? null : folder;
            if (process == null) { SLOT.release(); if (cleanup != null) delete(cleanup); }
            else reap(process, cleanup);
        }
    }

    /** 자식이 쓴 결과의 종류·쪽당·합계 상한을 부모가 다시 확인한다(자식의 fsize 상한과 별개). */
    static void checkOutput(Path folder, int count) throws IOException {
        long total = 0;
        for (int n = 1; n <= count; n++) {
            try (var pinned = com.bluesounds.nasquire.core.SecureFiles.open(folder.resolve("out-" + n + ".png"))) {
                if (pinned.size() > OcrWorker.MAX_PNG) throw new IOException("OCR 이미지 작업자 출력 오류");
                total += pinned.size();
            }
            if (total > OcrWorker.MAX_OUTPUT) throw new IOException("OCR 이미지 작업자 출력 상한 초과");
        }
    }

    private static void reap(Process process, Path cleanup) {
        if (kill(process, 5_000)) { SLOT.release(); if (cleanup != null) delete(cleanup); return; }
        Thread.ofPlatform().daemon().name("nasquire-ocr-reaper").start(() -> {
            while (!kill(process, 1_000)) { }
            SLOT.release(); if (cleanup != null) delete(cleanup);
        });
    }

    /**
     * 자손 목록을 먼저 확보한 뒤 자식의 프로세스 그룹(setsid로 만든 새 세션)·자손·자식을 강제 종료하고, 모두 끝났는지(좀비 제외) 확인한다.
     * 자식이 먼저 끝나 고아가 된 손자도 같은 프로세스 그룹이라 함께 종료된다. 시간 안에 확인되면 true.
     */
    static boolean kill(Process process, long waitMillis) {
        return kill(process, waitMillis, Path.of("/proc"));
    }
    static boolean kill(Process process, long waitMillis, Path proc) {
        var family = new ArrayList<>(process.descendants().toList());
        long group = process.pid();
        if (process.isAlive()) signalGroup(group);
        else signalRunningGroup(proc, group);
        family.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try { process.waitFor(waitMillis, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        while (true) {
            boolean alive = process.isAlive() || groupAlive(proc, group);
            for (var member : family) alive |= running(proc, member.pid());
            if (!alive) return true;
            if (System.nanoTime() >= deadline) return false;
            // 그룹에 남은 구성원이 있으면 그 번호는 재사용되지 않으므로 다시 보내도 안전하다.
            signalRunningGroup(proc, group);
            family.forEach(ProcessHandle::destroyForcibly);
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }
    private static final class Signals {
        static final java.lang.invoke.MethodHandle KILL = java.lang.foreign.Linker.nativeLinker().downcallHandle(
                java.lang.foreign.Linker.nativeLinker().defaultLookup().find("kill").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));
    }
    /** kill(-pgid, SIGKILL). 그룹 번호는 자식 pid이며 JVM 자신의 그룹과 다르다(setsid). */
    private static void signalGroup(long group) {
        if (group <= 1 || group == ProcessHandle.current().pid() || group > Integer.MAX_VALUE) return;
        try { int ignored = (int) Signals.KILL.invokeExact((int) -group, 9); } catch (Throwable ignored) { }
    }
    private static void signalRunningGroup(Path proc, long group) {
        try { if (groupRunning(proc, group)) signalGroup(group); }
        catch (IOException | RuntimeException ignored) { /* 종료 미확인 상태에서는 그룹 번호에 재신호하지 않는다. */ }
    }
    /** 열거·읽기·파싱 실패는 종료 미확인이다. 좀비·종료·확실히 사라진 pid만 종료로 본다. */
    static boolean running(Path proc, long pid) {
        try {
            String stat = stat(proc, pid);
            return stat != null && !List.of("Z", "X", "x").contains(field(stat, 0));
        } catch (IOException | RuntimeException e) { return true; }
    }
    static boolean groupAlive(Path proc, long group) {
        try { return groupRunning(proc, group); }
        catch (IOException | RuntimeException e) { return true; }
    }
    private static boolean groupRunning(Path proc, long group) throws IOException {
        try (var entries = Files.newDirectoryStream(proc, "[0-9]*")) {
            for (Path entry : entries) {
                String stat = stat(proc, Long.parseLong(entry.getFileName().toString()));
                if (stat != null && String.valueOf(group).equals(field(stat, 2)) && !List.of("Z", "X", "x").contains(field(stat, 0))) return true;
            }
            return false;
        }
    }
    private static String stat(Path proc, long pid) throws IOException {
        Path folder = proc.resolve(String.valueOf(pid));
        String text;
        try { text = Files.readString(folder.resolve("stat")); }
        catch (NoSuchFileException e) {
            // 열거 직후 실제로 사라진 프로세스는 정상이다. stat만 없거나 접근을 확인할 수 없으면 실패 닫힘.
            if (Files.isDirectory(proc) && Files.notExists(folder)) return null;
            throw e;
        }
        int end = text.lastIndexOf(')');
        if (end < 0 || end + 2 >= text.length()) throw new IOException("프로세스 상태 확인 실패");
        String rest = text.substring(end + 2);
        if (!field(rest, 0).matches("[RSDZTtXxKWPIN]") || !field(rest, 2).matches("[0-9]+")) throw new IOException("프로세스 상태 확인 실패");
        return rest;
    }
    /** "(comm)" 뒤 필드: 0=state, 1=ppid, 2=pgrp. */
    private static String field(String rest, int index) {
        var parts = rest.split(" ");
        return parts.length > index ? parts[index] : "";
    }

    private List<String> command(String mode, Path folder, int maxPages, String pages, long limitMillis) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long cpu = Math.max(1, (limitMillis + 999) / 1000);
        // setsid로 새 세션·프로세스 그룹을 만들어 고아가 된 손자까지 그룹 단위로 종료한다(자식은 그룹 리더가 아니므로 fork 없이 exec).
        var command = new ArrayList<>(List.of("/usr/bin/setsid", "/usr/bin/prlimit", "--as=" + (ADDRESS_MIB * 1024L * 1024L), "--cpu=" + cpu,
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

    /**
     * 원본을 루트부터 각 요소 NOFOLLOW로 따라가 O_NOFOLLOW|O_NONBLOCK으로 열고 열린 fd의 fstat이 일반 파일일 때만 읽는다(색인기가 넘기는
     * /proc/self/fd 고정 경로는 그 inode만 다시 연다). 검사 뒤 FIFO·외부 링크로 교체되어도 멈추거나 소스 밖을 읽지 않는다.
     * 복사는 작업 시간 상한 안에서만 진행한다.
     */
    static void copy(Path source, Path target, long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        try (var pinned = com.bluesounds.nasquire.core.SecureFiles.open(source.toAbsolutePath()); var in = pinned.channel();
             var out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (pinned.size() > MAX_INPUT) throw new IOException("OCR 원본 파일 오류");
            var buffer = ByteBuffer.allocate(65536);
            long total = 0;
            while (in.read(buffer.clear()) > 0) {
                total += buffer.position();
                if (total > MAX_INPUT) throw new IOException("OCR 원본 파일 오류");
                checkDeadline(deadlineNanos);
                buffer.flip(); while (buffer.hasRemaining()) out.write(buffer);
            }
            checkDeadline(deadlineNanos);
        }
    }
    private static void checkDeadline(long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("OCR 중단");
        if (System.nanoTime() >= deadlineNanos) throw new IOException("OCR 원본 복사 시간 초과");
    }
    static byte[] readBounded(Path file, long max) throws IOException {
        try (var pinned = com.bluesounds.nasquire.core.SecureFiles.open(file); var channel = pinned.channel()) {
            if (pinned.size() > max) throw new IOException("OCR 이미지 작업자 출력 오류");
            var buffer = ByteBuffer.allocate((int) pinned.size());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) > 0) throw new IOException("OCR 이미지 작업자 출력 변경");
            return buffer.array();
        }
    }
    private static void delete(Path folder) {
        try { OcrScratch.deleteTree(folder); } catch (IOException ignored) { }
    }
}

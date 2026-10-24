package com.bluesounds.nasquire.full;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 마지막 보안 잔여: OCR 원본 FIFO·중간 링크 교체(S1·S2), 자식 프로세스 그룹 회수·작업 폴더 검증(S4). */
class FinalReviewFullTest {
    @TempDir Path temp;

    @Test void fifoSourceFailsFastAndReleasesSlot() throws Exception {
        Path fifo = temp.resolve("scan.png");
        Files.writeString(fifo, "교체 전 일반 파일");
        Files.delete(fifo);
        assertEquals(0, new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start().waitFor());
        var sandbox = new OcrSandbox(temp.resolve("data"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class, () -> sandbox.image(fifo, 1)));
        assertEquals(1, OcrSandbox.availableSlots(), "슬롯 반환");
        try (var left = Files.list(temp.resolve("data/ocr-tmp"))) { assertEquals(0, left.count(), "작업 폴더 정리"); }
        // 중간 디렉터리가 소스 밖 링크면 복사하지 않는다.
        Path outside = Files.createDirectories(temp.resolve("outside"));
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY), "png", outside.resolve("x.png").toFile());
        Path source = Files.createDirectories(temp.resolve("source"));
        Files.createSymbolicLink(source.resolve("dir"), outside);
        assertThrows(IOException.class, () -> sandbox.image(source.resolve("dir/x.png"), 1));
        assertEquals(1, OcrSandbox.availableSlots());
    }

    @Test void parentChecksOutputTotalAndOpenedFileType() throws Exception {
        Path output = Files.createDirectory(temp.resolve("output"));
        // 희소 파일로 쪽당 상한은 지키지만 합계 256 MiB를 넘는 출력을 만든다.
        for (int n = 1; n <= 17; n++) {
            try (var file = java.nio.channels.FileChannel.open(output.resolve("out-" + n + ".png"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.position(OcrWorker.MAX_PNG - 1); file.write(java.nio.ByteBuffer.wrap(new byte[1]));
            }
        }
        OcrSandbox.checkOutput(output, 16);
        assertThrows(IOException.class, () -> OcrSandbox.checkOutput(output, 17));
        Path first = output.resolve("out-1.png"); Files.delete(first);
        assertEquals(0, new ProcessBuilder("/usr/bin/mkfifo", first.toString()).start().waitFor());
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThrows(IOException.class, () -> OcrSandbox.checkOutput(output, 1));
            assertThrows(IOException.class, () -> OcrSandbox.readBounded(first, OcrWorker.MAX_PNG));
        });
    }

    @Test void copyRejectsExpiredDeadline() throws Exception {
        Path source = Files.writeString(temp.resolve("source.png"), "image");
        assertThrows(IOException.class, () -> OcrSandbox.copy(source, temp.resolve("copy"), System.nanoTime() - 1));
        assertFalse(Files.exists(temp.resolve("copy")));
    }

    @Test void orphanedGrandchildIsKilledBeforeSlotRelease() throws Exception {
        Path image = temp.resolve("scan.png");
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY), "png", image.toFile());
        Path data = temp.resolve("data");
        var sandbox = new OcrSandbox(data, OrphaningOcrWorker.class.getName(), 20_000, 0);
        assertThrows(IOException.class, () -> sandbox.image(image, 1), "출력 없는 자식은 실패");
        assertEquals(1, OcrSandbox.availableSlots(), "슬롯 반환");
        long pid = Long.parseLong(Files.readString(data.resolve("ocr-tmp/orphan.pid")).trim());
        assertFalse(running(pid), "자식이 먼저 끝나 고아가 된 손자도 종료: " + pid);
        try (var left = Files.list(data.resolve("ocr-tmp"))) { assertEquals(1, left.count(), "작업 폴더 정리(pid 기록만 남음)"); }
    }

    @Test void workerAcceptsOnlyPrivateJobFolders() throws Exception {
        Path scratch = Files.createDirectories(temp.resolve("data/ocr-tmp")).toRealPath();
        Path job = Files.createTempDirectory(scratch, "job-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertTrue(OcrWorker.jobFolder(job));
        assertFalse(OcrWorker.jobFolder(temp.toRealPath()), "ocr-tmp/job-* 아님");
        Path open = Files.createDirectory(scratch.resolve("job-open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertFalse(OcrWorker.jobFolder(open), "0700 아님");
        Path link = Files.createSymbolicLink(scratch.resolve("job-link"), job);
        assertFalse(OcrWorker.jobFolder(link), "링크 거부");
        Path other = Files.createDirectories(temp.resolve("other/job-1"));
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
        assertFalse(OcrWorker.jobFolder(other.toRealPath()), "부모가 ocr-tmp 아님");
        assertEquals(OcrWorker.FAILED, OcrWorker.run(new String[] {"image", other.toRealPath().toString(), "1"}));
    }

    /** /proc 상태가 좀비·종료가 아니면 실행 중이다. */
    static boolean running(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            char state = stat.charAt(stat.lastIndexOf(')') + 2);
            return state != 'Z' && state != 'X' && state != 'x';
        } catch (IOException e) { return false; }
    }
}

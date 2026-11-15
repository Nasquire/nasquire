package com.bluesounds.nasquire.core.ocr;

import com.bluesounds.nasquire.core.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 4단계 보안 교차 검토 반영: 디코딩 상한(M1), OCR 자식 프로세스 격리(M2·재검토 R1). lite·full 공통 core로 옮겼다. */
class OcrIsolationTest {
    @TempDir Path temp;

    @Test void hangingOcrChildIsKilledAndNextFileWorks() throws Exception {
        // 재검토 R1: 자식 프로세스가 멈추거나 무한 루프여도 파일 단위 시간 상한 뒤 자손까지 강제 종료되고 다음 파일 OCR은 정상이다.
        Path image = temp.resolve("scan.png");
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_BYTE_GRAY), "png", image.toFile());
        Providers.OcrProvider provider = (png, language) -> "인식 OCR";
        var hanging = new OcrTextExtractor(provider, 1, new OcrSandbox(temp, HangingOcrWorker.class.getName(), 1_500, 0));
        var seen = ConcurrentHashMap.<ProcessHandle>newKeySet();
        var watching = new java.util.concurrent.atomic.AtomicBoolean(true);
        var watcher = new Thread(() -> {
            while (watching.get()) { ProcessHandle.current().descendants().forEach(seen::add); Thread.onSpinWait(); }
        });
        watcher.start();
        long start = System.nanoTime();
        try {
            var error = assertThrows(IOException.class, () -> hanging.extractLocated(image, (p, l, t) -> { }, 100));
            assertTrue(error.getMessage().contains("시간 초과"), error.getMessage());
        } finally { watching.set(false); watcher.join(); }
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "시간 상한 뒤 반환");
        assertTrue(seen.size() >= 2, "자식과 손자 프로세스를 관찰: " + seen.size());
        for (var process : seen) {
            process.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(process.isAlive(), "자식 프로세스 잔존 없음: " + process.pid());
        }
        assertEquals(0, ProcessHandle.current().descendants().count());
        // 같은 자식 슬롯으로 다음 파일을 정상 처리한다.
        var texts = new ArrayList<String>();
        new OcrTextExtractor(provider, 1, temp).extractLocated(image, (p, l, t) -> texts.add(t), 100);
        assertEquals(List.of("인식 OCR"), texts);
        try (var left = Files.list(temp.resolve("ocr-tmp"))) { assertEquals(0, left.count(), "작업 폴더 정리"); }
        // 상대 경로 데이터 폴더(예: --nasquire.data=./data)도 자식의 작업 디렉터리와 무관하게 동작한다.
        Path relative = Path.of("build", "ocr-relative-" + ProcessHandle.current().pid());
        try {
            texts.clear();
            new OcrTextExtractor(provider, 1, relative).extractLocated(image, (p, l, t) -> texts.add(t), 100);
            assertEquals(List.of("인식 OCR"), texts);
        } finally { com.bluesounds.nasquire.core.OcrScratch.deleteTree(relative); }
        // 자식이 상한 위반·실패로 끝나면 그 파일만 실패한다.
        Path broken = Files.write(temp.resolve("broken.png"), new byte[] {1, 2, 3});
        var failed = assertThrows(com.bluesounds.nasquire.core.ExtractionFailure.class,
                () -> new OcrTextExtractor(provider, 1, temp).extractLocated(broken, (p, l, t) -> { }, 100));
        assertEquals(2, failed.exitCode());
        assertTrue(failed.getMessage().contains("timeout=false, exception=java.lang.IllegalArgumentException"));
        // 소스 밖을 가리키는 링크는 열지 않는다.
        Path link = Files.createSymbolicLink(temp.resolve("link.png"), image);
        assertThrows(IOException.class, () -> new OcrTextExtractor(provider, 1, temp).extractLocated(link, (p, l, t) -> { }, 100));
    }

    @Test void largeImagesAreSubsampledAndOversizedRejected() throws Exception {
        Path big = temp.resolve("big.png");
        ImageIO.write(new BufferedImage(4000, 3000, BufferedImage.TYPE_BYTE_GRAY), "png", big.toFile());
        var sizes = new ArrayList<Long>();
        var extractor = new OcrTextExtractor((image, language) -> {
            try { var decoded = ImageIO.read(new ByteArrayInputStream(image)); sizes.add((long)decoded.getWidth() * decoded.getHeight()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return "ok";
        }, 1, temp);
        extractor.extractLocated(big, (p, l, t) -> { }, 100);
        assertTrue(sizes.getFirst() <= OcrTextExtractor.MAX_PIXELS, "부분 샘플링으로 픽셀 상한 안: " + sizes);
        Path wide = temp.resolve("wide.png");
        ImageIO.write(new BufferedImage(17_000, 4, BufferedImage.TYPE_BYTE_GRAY), "png", wide.toFile());
        assertThrows(IOException.class, () -> extractor.extractLocated(wide, (p, l, t) -> { }, 100));
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(17_000, 4, BufferedImage.TYPE_BYTE_GRAY), "png", bytes);
        var single = new OcrTextExtractor((image, language) -> "x", 1, temp);
        assertThrows(com.bluesounds.nasquire.core.ExtractionLimit.class, () -> single.recognizeImage(bytes.toByteArray()), "ML 서버도 같은 상한(상한 종류 보존, 413)");
        assertThrows(IllegalArgumentException.class, () -> single.recognizeImage(new byte[] {1, 2, 3}), "그 밖의 입력 오류(400)");
        var small = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY), "png", small);
        assertEquals("x", single.recognizeImage(small.toByteArray()));
        // PDF 쪽: 기본 150 DPI, 큰 쪽(A0)은 픽셀 상한에 맞춰 DPI를 낮추고 최소 DPI로도 넘으면 거부한다.
        assertEquals(150f, OcrTextExtractor.renderDpi(612, 792));
        assertEquals(62f, OcrTextExtractor.renderDpi(2384, 3370));
        assertThrows(IOException.class, () -> OcrTextExtractor.renderDpi(14_400, 14_400));
    }
}

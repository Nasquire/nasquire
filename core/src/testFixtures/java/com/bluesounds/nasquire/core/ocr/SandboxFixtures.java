package com.bluesounds.nasquire.core.ocr;

import java.nio.file.Path;

/** full 시험이 격리 자식 프로세스의 마감·강제 종료를 확인하는 데 쓰는 대역(core 내부 생성자 접근). */
public final class SandboxFixtures {
    private SandboxFixtures() { }
    /** 모든 자식 실행이 멈추는 격리 추출. ownMillis는 샌드박스 자체 시간 상한(문서 마감보다 길게 준다). */
    public static IsolatedExtractor hanging(Path data, OcrTextExtractor ocr, long ownMillis) {
        return new IsolatedExtractor(new OcrSandbox(data, Hanging.class.getName(), ownMillis, 0), ocr);
    }
    /** 프로세스 전체 격리 자식 슬롯(1개)이 비었는지. */
    public static int availableSlots() { return OcrSandbox.availableSlots(); }

    /** 손자 프로세스를 띄우고 중단에 응하지 않는 무한 루프로 멈추는 가짜 자식. */
    public static final class Hanging {
        public static void main(String[] args) throws Exception {
            new ProcessBuilder("/bin/sleep", "120").start();
            long spins = 0;
            while (true) { spins++; if (spins < 0) break; }
        }
    }
}

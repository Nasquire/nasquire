package com.bluesounds.nasquire.full;

/** 테스트용 가짜 OCR 자식: 손자 프로세스를 띄우고 중단에 응하지 않는 무한 루프로 멈춘다. */
public final class HangingOcrWorker {
    public static void main(String[] args) throws Exception {
        new ProcessBuilder("/bin/sleep", "120").start();
        long spins = 0;
        while (true) { spins++; if (spins < 0) break; }
    }
}

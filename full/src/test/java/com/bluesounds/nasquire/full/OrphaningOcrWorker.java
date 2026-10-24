package com.bluesounds.nasquire.full;

import java.nio.file.*;

/** 테스트용 가짜 OCR 자식: 손자 프로세스를 띄워 pid를 남기고 자신은 바로 끝나 손자를 고아로 만든다. */
public final class OrphaningOcrWorker {
    public static void main(String[] args) throws Exception {
        var grandchild = new ProcessBuilder("/bin/sleep", "120").start();
        Files.writeString(Path.of(args[1]).getParent().resolve("orphan.pid"), String.valueOf(grandchild.pid()));
        System.exit(0);
    }
}

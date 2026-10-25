package com.bluesounds.nasquire.core;

import java.io.IOException;

/** 원문·절대 경로·프로세스 출력을 포함하지 않는 추출 진단 정보. */
public final class ExtractionFailure extends IOException {
    private final Integer exitCode;
    public Integer exitCode() { return exitCode; }
    public ExtractionFailure(String stage, Integer exitCode, boolean timedOut, String exceptionType) {
        super("stage=" + safe(stage) + ", exit=" + (exitCode == null ? "unknown" : exitCode)
                + ", timeout=" + timedOut + ", exception=" + safe(exceptionType)
                + (timedOut ? " (시간 초과)" : ""));
        this.exitCode = exitCode;
    }
    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$-]{1,160}") ? value : "unknown";
    }
}

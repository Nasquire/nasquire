package com.bluesounds.nasquire.core;

import java.io.IOException;

/** 원문·절대 경로·프로세스 출력을 포함하지 않는 추출 진단 정보. */
public final class ExtractionFailure extends IOException {
    private final Integer exitCode;
    private final boolean timedOut;
    private final String exceptionType;
    public Integer exitCode() { return exitCode; }
    /** 시간 상한으로 중단했는지. */
    public boolean timedOut() { return timedOut; }
    /** 안전하게 거른 예외 종류(격리 자식이 남긴 이름 포함). 알 수 없으면 unknown. */
    public String exceptionType() { return safe(exceptionType); }
    public ExtractionFailure(String stage, Integer exitCode, boolean timedOut, String exceptionType) {
        super("stage=" + safe(stage) + ", exit=" + (exitCode == null ? "unknown" : exitCode)
                + ", timeout=" + timedOut + ", exception=" + safe(exceptionType)
                + (timedOut ? " (시간 초과)" : ""));
        this.exitCode = exitCode; this.timedOut = timedOut; this.exceptionType = exceptionType;
    }
    private static String safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$-]{1,160}") ? value : "unknown";
    }
}

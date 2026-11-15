package com.bluesounds.nasquire.core;

import java.io.IOException;

/**
 * 추출 안전 상한 위반(글자·근거 단위·쪽·픽셀·파일·해제 크기 등). 손상·암호 같은 입력 오류와 구분하며, ML 서버는 이 타입만 413으로 응답한다.
 * 메시지에는 원문·경로를 넣지 않는다.
 */
public class ExtractionLimit extends IOException {
    public ExtractionLimit(String message) { super(message); }
    public ExtractionLimit(String message, Throwable cause) { super(message, cause); }
    /** 원인 사슬(최대 8단계)에서 상한 위반을 찾는다. 없으면 null. */
    public static ExtractionLimit find(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause())
            if (current instanceof ExtractionLimit limit) return limit;
        return null;
    }
}

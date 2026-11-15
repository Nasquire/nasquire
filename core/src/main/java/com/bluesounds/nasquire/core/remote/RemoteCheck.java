package com.bluesounds.nasquire.core.remote;

/**
 * 서버 하나의 설정 확인 결과. result는 ok·down(연결 불가, 설정은 허용)·mismatch(모델·차원 불일치, 설정 거부)·error(확인 불가 응답).
 * detail은 불일치 항목(model, dimensions:N) 또는 실패 종류이며 주소·본문을 담지 않는다.
 */
public record RemoteCheck(String server, String result, String detail) {
    public boolean mismatch() { return "mismatch".equals(result); }
    /** 확인 중 실패를 결과로 바꾼다. 다음 서버로 넘기는 종류는 down, 나머지는 error. */
    public static RemoteCheck failed(String server, RemoteException e) {
        return new RemoteCheck(server, e.retryable() ? "down" : "error", e.kind().name().toLowerCase(java.util.Locale.ROOT));
    }
}

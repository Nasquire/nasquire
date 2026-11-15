package com.bluesounds.nasquire.core.remote;

/** 원격 호출 실패 종류. 메시지에 주소·키·응답 본문을 넣지 않는다. */
public final class RemoteException extends IllegalStateException {
    public enum Kind {
        /** 연결 실패·연결 끊김. 다음 서버로 넘긴다. */ CONNECT,
        /** 시간 초과. 다음 서버로 넘긴다. */ TIMEOUT,
        /** HTTP 5xx. 다음 서버로 넘긴다. */ SERVER_ERROR,
        /** HTTP 429. 다음 서버로 넘긴다. */ RATE_LIMITED,
        /** 그 밖의 HTTP 4xx·3xx. 즉시 실패한다. */ CLIENT_ERROR,
        /** 형식·크기·수가 맞지 않는 응답. 즉시 실패한다. */ INVALID_RESPONSE,
        /** 사용할 수 있는 서버가 없음(모두 차단·기능 비활성). */ UNAVAILABLE
    }
    private final Kind kind;
    private final int status;
    public RemoteException(Kind kind) { this(kind, 0); }
    public RemoteException(Kind kind, int status) {
        super("원격 호출 실패: " + kind.name().toLowerCase(java.util.Locale.ROOT) + (status > 0 ? " (" + status + ")" : ""));
        this.kind = kind; this.status = status;
    }
    public Kind kind() { return kind; }
    /** HTTP 상태 코드. 응답이 없으면 0. */
    public int status() { return status; }
    /** 같은 요청을 다음 서버로 넘길 수 있는 실패. */
    public boolean retryable() { return kind == Kind.CONNECT || kind == Kind.TIMEOUT || kind == Kind.SERVER_ERROR || kind == Kind.RATE_LIMITED; }
}

package com.bluesounds.nasquire.core.remote;

import java.net.URI;
import java.util.Set;

/**
 * 원격 서버 하나. 이름은 상태·응답 헤더에 쓰는 공개 식별자이고 주소·키는 공개하지 않는다.
 * @param timeoutSeconds 요청 1회의 시간 상한. 실제 상한은 기능 전체 남은 시간과 비교해 작은 값이다.
 */
public record RemoteServer(String name, String baseUrl, String key, int timeoutSeconds) {
    public static final int MAX_TIMEOUT_SECONDS = 300;
    public RemoteServer {
        if (!validName(name)) throw new IllegalArgumentException("원격 서버 이름 오류 (영문·숫자·._- 1~64자)");
        URI base;
        try { base = URI.create(baseUrl == null ? "" : baseUrl); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("원격 서버 주소 오류"); }
        if (!Set.of("https", "http").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || baseUrl.length() > 2048)
            throw new IllegalArgumentException("원격 서버 주소 오류");
        if (key == null) key = "";
        if (key.contains("\n") || key.contains("\r") || key.length() > 4096) throw new IllegalArgumentException("원격 서버 키 오류");
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) throw new IllegalArgumentException("원격 서버 시간 제한 오류");
    }
    public static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"); }
    /** 기본 주소 뒤에 경로를 붙인다. 끝의 /는 하나로 정리한다. */
    public URI resolve(String path) { return URI.create(baseUrl.replaceAll("/+$", "") + path); }
    @Override public String toString() { return "RemoteServer[" + name + "]"; }
}

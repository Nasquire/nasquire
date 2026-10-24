package com.bluesounds.nasquire.core;

import java.net.URI;
import java.util.Locale;

/** 원격 인증정보 전송 주소 정책. DNS 조회 없이 명시적인 루프백 주소만 평문을 기본 허용한다. */
public final class RemoteUrl {
    private RemoteUrl() { }
    public static final String HTTPS_REQUIRED = "원격 연결에는 HTTPS가 필요합니다 (평문은 allowInsecureHttp=true로 명시 허용)";
    /** 루프백이 아닌 평문 http 주소. 형식 오류는 각 공급자의 기존 검사에 맡긴다. */
    public static boolean insecure(String url) {
        URI uri;
        try { uri = URI.create(url == null ? "" : url.trim()); } catch (IllegalArgumentException e) { return false; }
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return !(host.equals("localhost") || host.equals("[::1]") || host.equals("::1") || host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
    }
    public static void require(String url, boolean allowInsecureHttp) {
        if (!allowInsecureHttp && insecure(url)) throw new IllegalArgumentException(HTTPS_REQUIRED);
    }
}

package com.bluesounds.nasquire.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 전달 헤더는 명시한 연결 프록시 IP에서 온 요청에만 적용한다. */
@Component
class ClientAddresses {
    private final Set<String> trusted;
    ClientAddresses(@Value("${nasquire.trusted-proxies:}") String proxies) {
        trusted = new HashSet<>();
        for (String value : proxies.split(",")) if (!value.isBlank()) trusted.add(numeric(value.trim()));
    }
    private static String numeric(String value) {
        if (value == null || value.length() > 64 || !value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("IP 주소 필요");
        try { return java.net.InetAddress.getByName(value).getHostAddress(); }
        catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("IP 주소 오류"); }
    }
    String address(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        try {
            if (!trusted.contains(numeric(remote))) return remote;
            String header = request.getHeader("X-Forwarded-For");
            if (header == null || header.length() > 2048) return remote;
            String[] chain = header.split(",", -1);
            String current = numeric(remote);
            for (int i = chain.length - 1; i >= 0 && trusted.contains(current); i--) current = numeric(chain[i].trim());
            return current;
        } catch (IllegalArgumentException e) { return remote; }
    }
}

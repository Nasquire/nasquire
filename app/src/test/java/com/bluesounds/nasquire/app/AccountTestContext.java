package com.bluesounds.nasquire.app;

/** HTTP 요청 밖에서 사용자별 저장소를 호출하는 테스트의 명시적 인증 범위. */
final class AccountTestContext implements AutoCloseable {
    private final org.springframework.security.core.context.SecurityContext previous;
    AccountTestContext(String username) {
        previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(username, null, java.util.List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    }
    public void close() { org.springframework.security.core.context.SecurityContextHolder.setContext(previous); }
}

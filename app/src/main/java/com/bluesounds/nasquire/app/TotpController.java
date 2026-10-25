package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class TotpController {
    /** 비밀번호 승인 뒤 코드 대기 세션. 세션별 실패가 상한에 이르면 대기 상태를 지운다. */
    record Pending(String username, long created, java.util.concurrent.atomic.AtomicInteger failures) implements java.io.Serializable { }
    record Enrollment(String encrypted, long created) { }
    record Input(String code, String password) { }
    private final TotpService totp;
    private final EmbeddingSettings secrets;
    private final Storage storage;
    private final LoginAttempts attempts;
    private final ClientAddresses addresses;
    private final BCryptPasswordEncoder passwords;
    TotpController(TotpService totp, EmbeddingSettings secrets, Storage storage, LoginAttempts attempts, ClientAddresses addresses, BCryptPasswordEncoder passwords) {
        this.totp = totp; this.secrets = secrets; this.storage = storage; this.attempts = attempts; this.addresses = addresses; this.passwords = passwords;
    }
    static Pending pending(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        var value = (Pending)session.getAttribute("totp.pending");
        if (value != null && System.currentTimeMillis() - value.created < 300000 && value.failures.get() < LoginAttempts.LIMIT) return value;
        session.removeAttribute("totp.pending"); return null;
    }
    /** 확인과 시도 예약을 원자적으로 수행한다. 거부되면 429를 쓰고 null. */
    private LoginAttempts.Ticket acquire(String prefix, HttpServletRequest request, HttpServletResponse response, String username, String actor) {
        var ticket = attempts.acquire(prefix, addresses.address(request), username, System.currentTimeMillis());
        if (ticket != null) return ticket;
        storage.audit(actor, "totp-rate-limit", 429); response.setStatus(429); response.setHeader("Retry-After", "900"); return null;
    }
    private void failed(LoginAttempts.Ticket ticket, HttpServletResponse response, String actor, String action) {
        attempts.failed(ticket, System.currentTimeMillis());
        storage.audit(actor, action, 401); response.setStatus(401);
    }
    private boolean password(Input input, Authentication auth) {
        return input != null && input.password != null && input.password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(input.password, storage.user(auth.getName()).getPassword());
    }
    @GetMapping("/totp") Map<String, Object> status() { return Map.of("enabled", totp.enabled()); }
    /** 등록은 해제와 같이 현재 비밀번호를 다시 확인한다. 실패는 로그인과 분리된 키로 제한한다. */
    @PostMapping("/totp/enroll") Map<String, String> enroll(@RequestBody(required = false) Input input, HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        if (totp.enabled()) throw new IllegalArgumentException("이미 활성화되었습니다");
        try (var ticket = acquire(LoginAttempts.MANAGE, request, response, auth.getName(), "admin")) {
            if (ticket == null) return Map.of();
            if (!password(input, auth)) { failed(ticket, response, "admin", "totp-enroll"); return Map.of(); }
            String secret = Totp.secret();
            request.getSession().setAttribute("totp.enrollment", new Enrollment(secrets.encrypt(EmbeddingSettings.TOTP, secret), System.currentTimeMillis()));
            response.setHeader("Cache-Control", "no-store");
            String label = URLEncoder.encode("Nasquire:" + auth.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            return Map.of("secret", secret, "uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Nasquire&algorithm=SHA1&digits=6&period=30");
        }
    }
    @PostMapping("/totp/enable") Map<String, Object> enable(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        try (var ticket = acquire(LoginAttempts.MANAGE, request, response, auth.getName(), "admin")) {
            if (ticket == null) return Map.of();
            var enrollment = (Enrollment)request.getSession().getAttribute("totp.enrollment");
            if (enrollment == null || System.currentTimeMillis() - enrollment.created > 600000) throw new IllegalArgumentException("등록 만료");
            List<String> codes;
            try { codes = totp.enable(enrollment.encrypted, input.code); }
            catch (IllegalArgumentException e) { failed(ticket, response, "admin", "totp-enable"); return Map.of(); }
            request.getSession().removeAttribute("totp.enrollment");
            attempts.succeeded(ticket); storage.audit("admin", "totp-enable", 200);
            response.setHeader("Cache-Control", "no-store"); return Map.of("recoveryCodes", codes);
        }
    }
    @PostMapping("/totp/disable") void disable(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        try (var ticket = acquire(LoginAttempts.MANAGE, request, response, auth.getName(), "admin")) {
            if (ticket == null) return;
            if (!password(input, auth) || !totp.disable(input.code)) { failed(ticket, response, "admin", "totp-disable"); return; }
            attempts.succeeded(ticket); storage.audit("admin", "totp-disable", 200);
            request.getSession().removeAttribute("totp.enrollment");
        }
    }
    @PostMapping("/login/totp") void login(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Pending pending = pending(request);
        if (pending == null) { response.setStatus(401); return; }
        try (var ticket = acquire("", request, response, pending.username, "anonymous")) {
            if (ticket == null) return;
            boolean recovery = TotpService.recoveryCode(input.code) != null;
            if (!totp.verify(input.code, true)) {
                failed(ticket, response, "anonymous", "login-totp");
                if (pending.failures.incrementAndGet() >= LoginAttempts.LIMIT) {
                    request.getSession().removeAttribute("totp.pending"); storage.audit("anonymous", "login-totp-pending-cleared", 401);
                }
                return;
            }
            request.changeSessionId(); request.getSession().removeAttribute("totp.pending");
            request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(storage.user(pending.username), null, storage.user(pending.username).getAuthorities()));
            SecurityContextHolder.setContext(context); new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().saveToken(null, request, response);
            attempts.succeeded(ticket);
            storage.audit("admin", recovery ? "login-recovery-code" : "login-totp", 200);
        }
    }
    /** 코드 단계를 취소하고 대기 세션을 정리한다. 실패 기록은 유지한다. */
    @DeleteMapping("/login/totp") void cancel(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute("totp.pending") != null) { session.removeAttribute("totp.pending"); storage.audit("anonymous", "login-totp-cancel", 204); }
        response.setStatus(204);
    }
    /** 키 파일 누락·불일치는 500 대신 통제된 503과 오프라인 복구 안내로 응답한다. */
    @ExceptionHandler(EmbeddingSettings.KeyUnavailable.class)
    org.springframework.http.ResponseEntity<Map<String, String>> keyUnavailable(EmbeddingSettings.KeyUnavailable e) {
        storage.audit("system", "totp-key-unavailable", 503);
        return org.springframework.http.ResponseEntity.status(503).body(Map.of("error", "TOTP 암호화 키를 사용할 수 없습니다. 서버를 멈추고 --recover-admin으로 복구하세요 / TOTP encryption key unavailable. Stop the server and run --recover-admin"));
    }
}

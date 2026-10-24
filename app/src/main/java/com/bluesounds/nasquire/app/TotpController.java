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
    record Pending(String username, long created) { }
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
        if (value != null && System.currentTimeMillis() - value.created < 300000) return value;
        session.removeAttribute("totp.pending"); return null;
    }
    private boolean allowed(HttpServletRequest request, HttpServletResponse response, String username) {
        if (attempts.allowed(addresses.address(request), username, System.currentTimeMillis())) return true;
        storage.audit("anonymous", "totp-rate-limit", 429); response.setStatus(429); response.setHeader("Retry-After", "900"); return false;
    }
    private void failed(HttpServletRequest request, HttpServletResponse response, String username, String action) {
        attempts.failed(addresses.address(request), username, System.currentTimeMillis());
        storage.audit("anonymous", action, 401); response.setStatus(401);
    }
    @GetMapping("/totp") Map<String, Object> status() { return Map.of("enabled", totp.enabled()); }
    @PostMapping("/totp/enroll") Map<String, String> enroll(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        if (totp.enabled()) throw new IllegalArgumentException("이미 활성화되었습니다");
        String secret = Totp.secret();
        request.getSession().setAttribute("totp.enrollment", new Enrollment(secrets.encrypt(secret), System.currentTimeMillis()));
        response.setHeader("Cache-Control", "no-store");
        String label = URLEncoder.encode("Nasquire:" + auth.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return Map.of("secret", secret, "uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Nasquire&algorithm=SHA1&digits=6&period=30");
    }
    @PostMapping("/totp/enable") Map<String, Object> enable(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        if (!allowed(request, response, auth.getName())) return Map.of();
        var enrollment = (Enrollment)request.getSession().getAttribute("totp.enrollment");
        if (enrollment == null || System.currentTimeMillis() - enrollment.created > 600000) throw new IllegalArgumentException("등록 만료");
        List<String> codes;
        try { codes = totp.enable(enrollment.encrypted, input.code); }
        catch (IllegalArgumentException e) { failed(request, response, auth.getName(), "totp-enable"); return Map.of(); }
        request.getSession().removeAttribute("totp.enrollment");
        attempts.succeeded(addresses.address(request), auth.getName()); storage.audit("admin", "totp-enable", 200);
        response.setHeader("Cache-Control", "no-store"); return Map.of("recoveryCodes", codes);
    }
    @PostMapping("/totp/disable") void disable(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        if (!allowed(request, response, auth.getName())) return;
        if (input.password == null || input.password.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(input.password, storage.user(auth.getName()).getPassword()) || !totp.disable(input.code)) {
            failed(request, response, auth.getName(), "totp-disable"); return;
        }
        attempts.succeeded(addresses.address(request), auth.getName()); storage.audit("admin", "totp-disable", 200);
        request.getSession().removeAttribute("totp.enrollment");
    }
    @PostMapping("/login/totp") void login(@RequestBody Input input, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Pending pending = pending(request);
        if (pending == null) { response.setStatus(401); return; }
        if (!allowed(request, response, pending.username)) return;
        if (!totp.verify(input.code, true)) { failed(request, response, pending.username, "login-totp"); return; }
        request.changeSessionId(); request.getSession().removeAttribute("totp.pending");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(storage.user(pending.username), null, storage.user(pending.username).getAuthorities()));
        SecurityContextHolder.setContext(context); new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        attempts.succeeded(addresses.address(request), pending.username);
        storage.audit("admin", input.code.length() == 40 ? "login-recovery-code" : "login-totp", 200);
    }
}

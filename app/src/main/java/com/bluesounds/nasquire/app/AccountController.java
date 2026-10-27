package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
class AccountController {
    record Input(String currentPassword, String newPassword, String confirmation, String username, String code) { }
    private final Storage storage;
    private final TotpService totp;
    private final LoginAttempts attempts;
    private final ClientAddresses addresses;
    private final BCryptPasswordEncoder passwords;
    AccountController(Storage storage, TotpService totp, LoginAttempts attempts, ClientAddresses addresses, BCryptPasswordEncoder passwords) {
        this.storage = storage; this.totp = totp; this.attempts = attempts; this.addresses = addresses; this.passwords = passwords;
    }
    @GetMapping Map<String, Object> account(Authentication auth) { return Map.of("username", auth.getName(), "totpEnabled", totp.enabled()); }
    @PutMapping("/{kind}") synchronized void change(@PathVariable String kind, @RequestBody Input input, Authentication auth, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!kind.equals("password") && !kind.equals("username")) throw new IllegalArgumentException("계정 변경 종류 오류");
        String action = "account-change-" + kind;
        try (var ticket = attempts.acquire("account-change:", addresses.address(request), auth.getName(), System.currentTimeMillis())) {
            if (ticket == null) { storage.audit("admin", action + "-rate-limit", 429); response.setHeader("Retry-After", "900"); response.setStatus(429); return; }
            long generation = storage.generation();
            if (!Long.valueOf(generation).equals(request.getSession().getAttribute("admin.generation"))) { storage.audit("admin", action, 401); response.setStatus(401); return; }
            var user = storage.user(auth.getName());
            if (input.currentPassword == null || input.currentPassword.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(input.currentPassword, user.getPassword())) {
                attempts.failed(ticket, System.currentTimeMillis()); storage.audit("admin", action, 401); response.setStatus(401); return;
            }
            String username = auth.getName(), hash = user.getPassword();
            try {
                if (kind.equals("password")) {
                    PasswordPolicy.validate(input.newPassword);
                    if (!input.newPassword.equals(input.confirmation)) throw new IllegalArgumentException("새 비밀번호 확인 불일치 / Password confirmation does not match");
                    hash = passwords.encode(input.newPassword);
                } else {
                    if (input.username == null || !input.username.matches("[A-Za-z0-9_.-]{3,100}")) throw new IllegalArgumentException("사용자명은 영문·숫자·_.- 3~100자 / Username must contain 3–100 ASCII letters, digits or _.-");
                    username = input.username;
                }
            } catch (IllegalArgumentException e) { storage.audit("admin", action, 400); throw e; }
            if (totp.enabled() && !totp.verify(input.code, false)) {
                attempts.failed(ticket, System.currentTimeMillis()); storage.audit("admin", action, 401); response.setStatus(401); return;
            }
            if (!storage.changeAccount(generation, username, hash)) { storage.audit("admin", action, 409); response.setStatus(409); return; }
            request.changeSessionId();
            request.getSession().setAttribute("admin.generation", generation + 1);
            request.getSession().setAttribute("admin.authenticatedAt", System.currentTimeMillis());
            request.getSession().removeAttribute("totp.enrollment");
            var context = SecurityContextHolder.createEmptyContext();
            var updated = storage.user(username);
            context.setAuthentication(new UsernamePasswordAuthenticationToken(updated, null, updated.getAuthorities()));
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
            attempts.succeeded(ticket); storage.audit("admin", action, 200);
            response.setHeader("Cache-Control", "no-store");
        }
    }
}

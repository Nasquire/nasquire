package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
class OidcController {
    private final OidcSettings settings;
    private final OidcService oidc;
    private final OidcAccounts accounts;
    private final Storage storage;
    private final TotpService totp;
    private final LoginAttempts attempts;
    private final ClientAddresses addresses;
    OidcController(OidcSettings settings, OidcService oidc, OidcAccounts accounts, Storage storage, TotpService totp, LoginAttempts attempts, ClientAddresses addresses) {
        this.settings = settings; this.oidc = oidc; this.accounts = accounts; this.storage = storage; this.totp = totp; this.attempts = attempts; this.addresses = addresses;
    }
    @GetMapping("/api/oidc") Map<String,Object> view() { return settings.view(); }
    @PutMapping("/api/oidc") void save(@RequestBody Map<String,String> input) throws Exception { settings.save(input); storage.audit("admin", "oidc-settings", 200); }
    @DeleteMapping("/api/oidc/secret") void clear() throws Exception { settings.save(Map.of("clearSecret", "true")); storage.audit("admin", "oidc-secret-delete", 200); }
    @PostMapping("/api/oidc/test") Map<String,Boolean> test() throws Exception { oidc.discover(true); storage.audit("admin", "oidc-discovery-test", 200); return Map.of("ok", true); }
    @GetMapping("/oidc/status") Map<String,Object> status() { return Map.of("enabled", settings.enabled(), "buttonLabel", settings.view().get("buttonLabel")); }
    @GetMapping("/oidc/login") void start(HttpServletRequest req, HttpServletResponse res) throws Exception {
        res.setHeader("Cache-Control", "no-store");
        if (!settings.enabled()) { error(res, 401); return; }
        if (!attempts.allowOAuth("oidc-start", addresses.address(req), System.currentTimeMillis())) { error(res, 429); return; }
        try {
            var pending = oidc.pending(); String url = oidc.authorize(pending);
            req.getSession().setAttribute("oidc.pending", pending); res.sendRedirect(url);
        } catch (Exception e) { storage.audit("anonymous", "oidc-start-failed", 401); error(res, 401); }
    }
    @GetMapping("/oidc/callback") void callback(HttpServletRequest req, HttpServletResponse res) throws Exception {
        res.setHeader("Cache-Control", "no-store"); res.setHeader("Referrer-Policy", "no-referrer");
        var session = req.getSession(false); OidcService.Pending pending = null;
        if (session != null) synchronized (session) { pending = (OidcService.Pending)session.getAttribute("oidc.pending"); session.removeAttribute("oidc.pending"); }
        if (pending == null || req.getParameter("error") != null) { error(res, 401); return; }
        try (var ticket = attempts.acquire("oidc:", addresses.address(req), addresses.address(req), System.currentTimeMillis())) {
            if (ticket == null) { storage.audit("anonymous", "oidc-rate-limit", 429); error(res, 429); return; }
            try {
                if (!settings.enabled() || pending == null || System.currentTimeMillis() - pending.created() > 300000 || req.getParameterValues("state") == null || req.getParameterValues("state").length != 1
                        || !pending.state().equals(req.getParameter("state")) || req.getParameter("error") != null || req.getParameterValues("code") == null || req.getParameterValues("code").length != 1) throw new IllegalArgumentException();
                var login = accounts.login(oidc.exchange(req.getParameter("code"), pending), pending.revision());
                var a = login.account();
                if (pending.revision() != settings.revision()) throw new IllegalArgumentException();
                // 기존 세션의 인증/TOTP/CSRF 속성도 모두 폐기한다.
                session.invalidate(); session = req.getSession(true); req.changeSessionId();
                session.setAttribute("account.id", a.id()); session.setAttribute("admin.generation", a.generation());
                var context = SecurityContextHolder.createEmptyContext();
                if (login.deferred() != null) {
                    session.setAttribute("totp.pending", new TotpController.Pending(a.username(), System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicInteger()));
                    session.setAttribute("oidc.deferred", login.deferred());
                }
                else {
                    var user = storage.user(a.username()); context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
                    session.setAttribute("admin.authenticatedAt", System.currentTimeMillis());
                }
                SecurityContextHolder.setContext(context); new HttpSessionSecurityContextRepository().saveContext(context, req, res);
                new HttpSessionCsrfTokenRepository().saveToken(null, req, res); attempts.succeeded(ticket);
                storage.audit("user:" + a.id(), totp.enabled(a.username()) ? "oidc-totp-required" : "oidc-login", 200); res.sendRedirect("/");
            } catch (Exception e) { attempts.failed(ticket, System.currentTimeMillis()); storage.audit("anonymous", "oidc-login-failed", 401); error(res, 401); }
        }
    }
    static void error(HttpServletResponse res, int status) throws java.io.IOException {
        res.setStatus(status); res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Sign in failed</title><h1>로그인 실패 / Sign in failed</h1><p>다시 시도하거나 로컬 로그인을 사용하세요 / Try again or use local sign in.</p><a href=\"/\">Nasquire</a></html>");
    }
    @ExceptionHandler(EmbeddingSettings.KeyUnavailable.class)
    org.springframework.http.ResponseEntity<Map<String,String>> keyError() { storage.audit("admin", "oidc-key-unavailable", 503); return org.springframework.http.ResponseEntity.status(503).body(Map.of("error", "embedding.key 백업 복원 필요 / Restore embedding.key backup")); }
    @ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<Map<String,String>> failure() { storage.audit("admin", "oidc-operation-failed", 400); return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "OIDC 설정 또는 연결 실패 / OIDC configuration or connection failed")); }
}

package com.bluesounds.nasquire.app;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Bean UserDetailsService users(Storage store) { return store::user; }
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain security(HttpSecurity http, Storage storage, LoginAttempts attempts, ClientAddresses addresses, TotpService totp, OAuthService oauth, @Value("${nasquire.upload-max-bytes:33554432}") long maximum, @Value("${nasquire.mcp-allowed-origins:}") String allowedOrigins) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/assets/**", "/icon.svg", "/LICENSE.txt", "/react-LICENSE.txt", "/LICENSE-EXCEPTIONS.md", "/api/auth", "/api/setup", "/api/login", "/api/login/totp", "/download", "/error").permitAll()
                .requestMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server", "/oauth/register", "/oauth/token", "/oauth/revoke", "/oauth/login.js", "/oauth/style.css").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/oauth/authorize").permitAll()
                .requestMatchers("/mcp").authenticated()
                .requestMatchers("/api/search", "/api/document", "/api/download").authenticated()
                .anyRequest().hasRole("ADMIN"))
            .csrf(c -> c.ignoringRequestMatchers(r -> r.getAttribute("nasquire.token") != null || Set.of("/oauth/register", "/oauth/token", "/oauth/revoke").contains(r.getServletPath()))
                    .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestHandler() {
                        private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
                        @Override public void handle(HttpServletRequest req, HttpServletResponse res, java.util.function.Supplier<CsrfToken> token) { delegate.handle(req, res, token); }
                        @Override public String resolveCsrfTokenValue(HttpServletRequest req, CsrfToken token) {
                            return (req.getHeader(token.getHeaderName()) != null || req.getServletPath().equals("/oauth/authorize")) ? delegate.resolveCsrfTokenValue(req, token) : null;
                        }
                    }))
            .addFilterBefore(new OAuthGuard(oauth, attempts, addresses), CsrfFilter.class)
            .addFilterBefore(new MultipartGuard(maximum), CsrfFilter.class)
            .addFilterBefore(new LoginGuard(attempts, addresses, storage), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new BearerFilter(storage, oauth, Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet())), CsrfFilter.class)
            .formLogin(f -> f.securityContextRepository(new PasswordContextRepository(totp::enabled)).loginProcessingUrl("/api/login")
                    .successHandler((req, res, auth) -> {
                        if (totp.enabled()) {
                            var context = SecurityContextHolder.createEmptyContext();
                            SecurityContextHolder.setContext(context);
                            new org.springframework.security.web.context.HttpSessionSecurityContextRepository().saveContext(context, req, res);
                            req.getSession().setAttribute("totp.pending", new TotpController.Pending(auth.getName(), System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicInteger()));
                            storage.audit("anonymous", "login-totp-required", 202); res.setStatus(202);
                        } else {
                            req.getSession().removeAttribute("totp.pending");
                            var ticket = LoginGuard.ticket(req); if (ticket != null) attempts.succeeded(ticket);
                            storage.audit("admin", "login", 200); res.setStatus(200);
                        }
                    })
                    .failureHandler((req, res, ex) -> {
                        var ticket = LoginGuard.ticket(req); if (ticket != null) attempts.failed(ticket, System.currentTimeMillis());
                        storage.audit("anonymous", "login", 401); res.setStatus(401);
                    }))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.setStatus(401))
                    .accessDeniedHandler((req, res, ex) -> res.setStatus(403)))
            .requestCache(c -> c.disable());
        return http.build();
    }

    /** 성공 핸들러보다 먼저 실행되는 기본 세션 저장에서도 ADMIN 노출을 차단한다. */
    static class PasswordContextRepository extends org.springframework.security.web.context.HttpSessionSecurityContextRepository {
        private final java.util.function.BooleanSupplier requiresTotp;
        PasswordContextRepository(java.util.function.BooleanSupplier requiresTotp) { this.requiresTotp = requiresTotp; }
        @Override public void saveContext(org.springframework.security.core.context.SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            super.saveContext(requiresTotp.getAsBoolean() ? SecurityContextHolder.createEmptyContext() : context, request, response);
        }
    }
    static class MultipartGuard extends OncePerRequestFilter {
        private final long maximum;
        MultipartGuard(long maximum) { this.maximum = maximum + 1048576; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String type = req.getContentType();
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
                if (!req.getServletPath().startsWith("/api/uploads/")) { res.setStatus(403); return; }
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) { res.setStatus(401); return; }
                if (req.getContentLengthLong() > maximum) { res.setStatus(413); return; }
            }
            chain.doFilter(req, res);
        }
    }
    static class LoginGuard extends OncePerRequestFilter {
        private final LoginAttempts attempts;
        private final ClientAddresses addresses;
        private final Storage storage;
        LoginGuard(LoginAttempts attempts, ClientAddresses addresses, Storage storage) { this.attempts = attempts; this.addresses = addresses; this.storage = storage; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            if (!req.getServletPath().equals("/api/login") || !req.getMethod().equals("POST")) { chain.doFilter(req, res); return; }
            // 확인과 진행 중 시도 예약을 원자적으로 처리하고, 성공/실패 핸들러가 같은 표로 기록한다.
            var ticket = attempts.acquire("", addresses.address(req), req.getParameter("username"), System.currentTimeMillis());
            if (ticket == null) { storage.audit("anonymous", "login-rate-limit", 429); res.setHeader("Retry-After", "900"); res.setStatus(429); return; }
            req.setAttribute(TICKET, ticket);
            try (ticket) { chain.doFilter(req, res); }
        }
        static final String TICKET = "nasquire.loginTicket";
        static LoginAttempts.Ticket ticket(HttpServletRequest req) { return (LoginAttempts.Ticket)req.getAttribute(TICKET); }
    }

    static class OAuthGuard extends OncePerRequestFilter {
        private final OAuthService oauth; private final LoginAttempts attempts; private final ClientAddresses addresses;
        OAuthGuard(OAuthService oauth, LoginAttempts attempts, ClientAddresses addresses) { this.oauth = oauth; this.attempts = attempts; this.addresses = addresses; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String path = req.getServletPath();
            if (path.startsWith("/oauth/") || path.startsWith("/.well-known/oauth-")) {
                res.setHeader("Cache-Control", "no-store");
                if (!oauth.enabled()) { res.setStatus(404); return; }
                if (Set.of("/oauth/register", "/oauth/token", "/oauth/revoke").contains(path)) {
                    String endpoint = path.substring(7);
                    if (!attempts.allowOAuth(endpoint, addresses.address(req), System.currentTimeMillis())) {
                        oauth.audit("rate-limit", 429); res.setHeader("Retry-After", "60"); res.setStatus(429); return;
                    }
                }
            }
            if (path.equals("/oauth/register")) {
                byte[] body = req.getInputStream().readNBytes(24577);
                if (body.length > 24576) { res.setStatus(413); return; }
                var wrapped = new HttpServletRequestWrapper(req) {
                    @Override public ServletInputStream getInputStream() {
                        var input = new java.io.ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() { return input.read(); }
                            public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                            public boolean isFinished() { return input.available() == 0; }
                            public boolean isReady() { return true; }
                            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                };
                chain.doFilter(wrapped, res); return;
            }
            chain.doFilter(req, res);
        }
    }

    static class BearerFilter extends OncePerRequestFilter {
        private final Storage storage;
        private final Set<String> allowedOrigins;
        private final OAuthService oauth;
        BearerFilter(Storage storage, OAuthService oauth, Set<String> allowedOrigins) { this.storage = storage; this.oauth = oauth; this.allowedOrigins = allowedOrigins; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            boolean mcp = req.getServletPath().equals("/mcp");
            String metadata = mcp && oauth.enabled() ? " resource_metadata=\"" + oauth.issuer + "/.well-known/oauth-protected-resource/mcp\"" : "";
            if (mcp && req.getHeader("Origin") != null && !allowedOrigins.contains(req.getHeader("Origin"))) {
                res.setStatus(403); return;
            }
            if (header == null && !mcp) { chain.doFilter(req, res); return; }
            if (header == null || !header.startsWith("Bearer ") || header.length() > 256) {
                if (!metadata.isEmpty()) res.setHeader("WWW-Authenticate", "Bearer" + metadata);
                res.setStatus(401); return;
            }
            var identity = storage.authenticate(hash(header.substring(7)));
            if (identity == null && mcp) identity = oauth.authenticate(hash(header.substring(7)));
            if (identity != null && identity.tokenId().startsWith("oauth:") && identity.sources().isEmpty()) {
                res.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"mcp:read\"" + (metadata.isEmpty() ? "" : "," + metadata)); res.setStatus(403); return;
            }
            if (identity == null) {
                if (!metadata.isEmpty()) res.setHeader("WWW-Authenticate", "Bearer" + metadata);
                storage.audit("invalid-token", req.getMethod() + " " + req.getServletPath(), 401); res.setStatus(401); return;
            }
            req.setAttribute("nasquire.token", identity);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
            var previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(context);
            try {
                if (!mcp && !(req.getMethod().equals("GET") && List.of("/api/search", "/api/document", "/api/download").contains(req.getServletPath()))) {
                    res.setStatus(403); return;
                }
                chain.doFilter(req, res);
            } finally {
                storage.audit(identity.tokenId(), req.getMethod() + " " + req.getServletPath(), res.getStatus());
                SecurityContextHolder.setContext(previous);
            }
        }
    }
}

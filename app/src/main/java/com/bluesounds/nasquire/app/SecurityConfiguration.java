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
    @Bean SecurityFilterChain security(HttpSecurity http, Storage storage, LoginAttempts attempts, ClientAddresses addresses, TotpService totp, @Value("${nasquire.upload-max-bytes:33554432}") long maximum, @Value("${nasquire.mcp-allowed-origins:}") String allowedOrigins) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/assets/**", "/icon.svg", "/LICENSE.txt", "/react-LICENSE.txt", "/LICENSE-EXCEPTIONS.md", "/api/auth", "/api/setup", "/api/login", "/api/login/totp", "/download", "/error").permitAll()
                .requestMatchers("/mcp").authenticated()
                .requestMatchers("/api/search", "/api/document", "/api/download").authenticated()
                .anyRequest().hasRole("ADMIN"))
            .csrf(c -> c.ignoringRequestMatchers(r -> r.getAttribute("nasquire.token") != null)
                    .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestHandler() {
                        private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
                        @Override public void handle(HttpServletRequest req, HttpServletResponse res, java.util.function.Supplier<CsrfToken> token) { delegate.handle(req, res, token); }
                        @Override public String resolveCsrfTokenValue(HttpServletRequest req, CsrfToken token) {
                            return req.getHeader(token.getHeaderName()) == null ? null : delegate.resolveCsrfTokenValue(req, token);
                        }
                    }))
            .addFilterBefore(new MultipartGuard(maximum), CsrfFilter.class)
            .addFilterBefore(new LoginGuard(attempts, addresses, storage), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new BearerFilter(storage, Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet())), CsrfFilter.class)
            .formLogin(f -> f.securityContextRepository(new PasswordContextRepository(totp::enabled)).loginProcessingUrl("/api/login")
                    .successHandler((req, res, auth) -> {
                        if (totp.enabled()) {
                            var context = SecurityContextHolder.createEmptyContext();
                            SecurityContextHolder.setContext(context);
                            new org.springframework.security.web.context.HttpSessionSecurityContextRepository().saveContext(context, req, res);
                            req.getSession().setAttribute("totp.pending", new TotpController.Pending(auth.getName(), System.currentTimeMillis()));
                            storage.audit("anonymous", "login-totp-required", 202); res.setStatus(202);
                        } else {
                            req.getSession().removeAttribute("totp.pending");
                            attempts.succeeded(addresses.address(req), auth.getName()); storage.audit("admin", "login", 200); res.setStatus(200);
                        }
                    })
                    .failureHandler((req, res, ex) -> { attempts.failed(addresses.address(req), req.getParameter("username"), System.currentTimeMillis()); storage.audit("anonymous", "login", 401); res.setStatus(401); }))
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
            if (req.getServletPath().equals("/api/login") && req.getMethod().equals("POST")
                    && !attempts.allowed(addresses.address(req), req.getParameter("username"), System.currentTimeMillis())) {
                storage.audit("anonymous", "login-rate-limit", 429); res.setHeader("Retry-After", "900"); res.setStatus(429); return;
            }
            chain.doFilter(req, res);
        }
    }

    static class BearerFilter extends OncePerRequestFilter {
        private final Storage storage;
        private final Set<String> allowedOrigins;
        BearerFilter(Storage storage, Set<String> allowedOrigins) { this.storage = storage; this.allowedOrigins = allowedOrigins; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            boolean mcp = req.getServletPath().equals("/mcp");
            if (mcp && req.getHeader("Origin") != null && !allowedOrigins.contains(req.getHeader("Origin"))) {
                res.setStatus(403); return;
            }
            if (header == null && !mcp) { chain.doFilter(req, res); return; }
            if (header == null || !header.startsWith("Bearer ") || header.length() > 256) { res.setStatus(401); return; }
            var identity = storage.authenticate(hash(header.substring(7)));
            if (identity == null) { storage.audit("invalid-token", req.getMethod() + " " + req.getServletPath(), 401); res.setStatus(401); return; }
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

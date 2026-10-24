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
    @Bean SecurityFilterChain security(HttpSecurity http, Storage storage, @Value("${nasquire.mcp-allowed-origins:}") String allowedOrigins) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/assets/**", "/icon.svg", "/LICENSE.txt", "/react-LICENSE.txt", "/LICENSE-EXCEPTIONS.md", "/api/auth", "/api/setup", "/api/login", "/download", "/error").permitAll()
                .requestMatchers("/mcp").authenticated()
                .requestMatchers("/api/search", "/api/document", "/api/download").authenticated()
                .anyRequest().hasRole("ADMIN"))
            .csrf(c -> c.ignoringRequestMatchers(r -> r.getAttribute("nasquire.token") != null)
                    .csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .addFilterBefore(new BearerFilter(storage, Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet())), CsrfFilter.class)
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                    .successHandler((req, res, auth) -> { storage.audit("admin", "login", 200); res.setStatus(200); })
                    .failureHandler((req, res, ex) -> { storage.audit("anonymous", "login", 401); res.sendError(401); }))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401))
                    .accessDeniedHandler((req, res, ex) -> res.sendError(403)))
            .requestCache(c -> c.disable());
        return http.build();
    }

    static class BearerFilter extends OncePerRequestFilter {
        private final Storage storage;
        private final Set<String> allowedOrigins;
        BearerFilter(Storage storage, Set<String> allowedOrigins) { this.storage = storage; this.allowedOrigins = allowedOrigins; }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            boolean mcp = req.getServletPath().equals("/mcp");
            if (mcp && req.getHeader("Origin") != null && !allowedOrigins.contains(req.getHeader("Origin"))) {
                res.sendError(403); return;
            }
            if (header == null && !mcp) { chain.doFilter(req, res); return; }
            if (header == null || !header.startsWith("Bearer ") || header.length() > 256) { res.sendError(401); return; }
            var identity = storage.authenticate(hash(header.substring(7)));
            if (identity == null) { storage.audit("invalid-token", req.getMethod() + " " + req.getServletPath(), 401); res.sendError(401); return; }
            req.setAttribute("nasquire.token", identity);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
            var previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(context);
            try {
                if (!mcp && !(req.getMethod().equals("GET") && List.of("/api/search", "/api/document", "/api/download").contains(req.getServletPath()))) {
                    res.sendError(403); return;
                }
                chain.doFilter(req, res);
            } finally {
                storage.audit(identity.tokenId(), req.getMethod() + " " + req.getServletPath(), res.getStatus());
                SecurityContextHolder.setContext(previous);
            }
        }
    }
}

package com.bluesounds.nasquire.app;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class ShareSecurity {
    @Bean @Order(1) SecurityFilterChain publicShares(HttpSecurity http, Storage storage,
            @Value("${nasquire.share-requests-per-minute:60}") int maximum) throws Exception {
        if (maximum < 1 || maximum > 10000) throw new IllegalArgumentException("공유 속도 제한 범위 오류");
        return http.securityMatcher("/s/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.disable()).csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new PublicFilter(maximum, storage), SecurityContextHolderFilter.class).build();
    }
    static final class PublicFilter extends OncePerRequestFilter {
        record Window(long start, int count) { }
        private final Map<String, Window> windows = new LinkedHashMap<>();
        private final int maximum;
        private final Storage storage;
        PublicFilter(int maximum, Storage storage) { this.maximum = maximum; this.storage = storage; }
        synchronized boolean allow(String ip, long now) {
            var window = windows.get(ip);
            if (window == null || now - window.start >= 60000) {
                if (windows.size() >= 4096 && window == null) return false;
                windows.put(ip, new Window(now, 1)); return true;
            }
            if (window.count >= maximum) return false;
            windows.put(ip, new Window(window.start, window.count + 1)); return true;
        }
        synchronized void expire(long now) { windows.entrySet().removeIf(e -> now - e.getValue().start >= 60000); }
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            res.setHeader("X-Content-Type-Options", "nosniff"); res.setHeader("Referrer-Policy", "no-referrer");
            res.setHeader("Cache-Control", "no-store"); res.setHeader("X-Frame-Options", "DENY");
            long now = System.currentTimeMillis(); expire(now);
            // 프록시가 보낸 임의 X-Forwarded-For를 신뢰하지 않는다.
            if (!allow(req.getRemoteAddr(), now)) { storage.audit("share-visitor", "share-rate-limit", 429); res.setHeader("Retry-After", "60"); res.setStatus(429); return; }
            if (!req.getMethod().equals("GET")) { res.setStatus(405); return; }
            chain.doFilter(req, res);
        }
    }
}

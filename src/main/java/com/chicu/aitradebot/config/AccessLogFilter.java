// com/chicu/aitradebot/config/AccessLogFilter.java
package com.chicu.aitradebot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(1)
public class AccessLogFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long dt = System.currentTimeMillis() - t0;
            log.info("HTTP RESPONSE <<< {} {} -> {} ({} ms)",
                    req.getMethod(), req.getRequestURI(), res.getStatus(), dt);
        }
    }
}

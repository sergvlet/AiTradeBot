package com.chicu.aitradebot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
@Order(1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final long SLOW_REQUEST_MS = 700L;
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        long startedAt = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long tookMs = System.currentTimeMillis() - startedAt;
            String method = safeUpper(req.getMethod());
            String uri = safeUri(req.getRequestURI());
            int status = res.getStatus();

            if (status >= 500) {
                log.warn("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
                return;
            }

            if (status >= 400) {
                log.warn("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
                return;
            }

            if (status == 101) {
                if (log.isDebugEnabled()) {
                    log.debug("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
                }
                return;
            }

            if (tookMs >= SLOW_REQUEST_MS) {
                log.info("HTTP {} {} -> {} ({} мс, медленный запрос)", method, uri, status, tookMs);
                return;
            }

            if (WRITE_METHODS.contains(method)) {
                log.info("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
                return;
            }

            if (isNoisyRead(method, uri)) {
                if (log.isDebugEnabled()) {
                    log.debug("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
                }
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("HTTP {} {} -> {} ({} мс)", method, uri, status, tookMs);
            }
        }
    }

    private boolean isNoisyRead(String method, String uri) {
        if (!"GET".equals(method)) {
            return false;
        }

        if (uri == null || uri.isBlank()) {
            return false;
        }

        return uri.endsWith("/config/state")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/images/")
                || uri.startsWith("/webjars/")
                || uri.startsWith("/actuator/")
                || uri.startsWith("/ws/strategy/")
                || "/ws/strategy/info".equals(uri)
                || "/favicon.ico".equals(uri);
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String safeUri(String value) {
        return value == null ? "/" : value.trim();
    }
}

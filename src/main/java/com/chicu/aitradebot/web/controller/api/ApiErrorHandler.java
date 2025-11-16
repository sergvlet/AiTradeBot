package com.chicu.aitradebot.web.controller.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e, HttpServletRequest req) {
        log.warn("400 Bad Request at {}: {}", safePath(req), e.toString());
        return build(400, e, req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                      HttpServletRequest req) {
        log.warn("405 Method Not Allowed at {}: {}", safePath(req), e.getMethod());
        return build(405, e, req);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e,
                                                                    HttpServletRequest req) {
        int code = e.getStatusCode().value();
        if (code >= 500) {
            log.error("{} at {}: {}", code, safePath(req), safeMsg(e), e);
        } else {
            log.warn("{} at {}: {}", code, safePath(req), safeMsg(e));
        }
        return build(code, e, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e, HttpServletRequest req) {
        // общий неожиданный 500
        log.error("500 at {}: {}", safePath(req), safeMsg(e), e);
        return build(500, e, req);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map<String, Object>> build(int status, Exception e, HttpServletRequest req) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("code", status);
        body.put("error", e.getClass().getSimpleName());
        body.put("message", safeMsg(e));
        body.put("path", safePath(req));
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(status).body(body);
    }

    private String safeMsg(Throwable e) {
        String m = (e != null ? e.getMessage() : null);
        return (m != null && !m.isBlank()) ? m : (e != null ? e.getClass().getSimpleName() : "Error");
    }

    private String safePath(HttpServletRequest req) {
        return req != null ? req.getRequestURI() : "/";
    }
}

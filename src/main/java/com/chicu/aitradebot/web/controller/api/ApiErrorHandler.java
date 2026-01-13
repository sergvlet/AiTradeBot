package com.chicu.aitradebot.web.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception ex, HttpServletRequest req) {

        if (isClientAbort(ex)) {
            // Клиент сам закрыл/отменил запрос — это НЕ ошибка сервера
            log.debug("⚠️ Client aborted request: {} {}", req.getMethod(), req.getRequestURI());
            // Ответ уже никому не нужен, но чтобы не было 500 в логике:
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        log.error("500 at {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(500).body(
                java.util.Map.of("ok", false, "error", ex.getClass().getSimpleName(), "message", ex.getMessage())
        );
    }

    private static boolean isClientAbort(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof ClientAbortException) return true;
            if (t instanceof AsyncRequestNotUsableException) return true;

            // универсальные сигнатуры (если не Tomcat / другой контейнер)
            if (t instanceof IOException) {
                String m = String.valueOf(t.getMessage()).toLowerCase();
                if (m.contains("broken pipe") || m.contains("connection reset") || m.contains("forcibly closed")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}

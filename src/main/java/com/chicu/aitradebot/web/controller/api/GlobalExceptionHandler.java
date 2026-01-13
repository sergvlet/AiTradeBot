package com.chicu.aitradebot.web.controller.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {

        String msg = ex.getMessage() == null ? "" : ex.getMessage();

        // Только наш кейс "нет настроек" — чтобы не ломать остальные IllegalStateException
        if (msg.startsWith("StrategySettings not found")) {

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "not_configured");
            body.put("message", "Стратегия ещё не настроена. Открой настройки и сохрани параметры.");
            body.put("details", msg);
            body.put("timestamp", Instant.now().toEpochMilli());

            // можно 404, можно 200. Для UI-страницы лучше 200.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", msg.isBlank() ? "IllegalStateException" : msg);
        body.put("timestamp", Instant.now().toEpochMilli());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

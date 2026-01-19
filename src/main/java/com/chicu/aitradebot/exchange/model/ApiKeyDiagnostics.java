package com.chicu.aitradebot.exchange.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class ApiKeyDiagnostics {

    private boolean ok;              // итог: всё ок
    private String exchange;         // BINANCE / BYBIT
    private String message;          // итоговое сообщение

    private boolean apiKeyValid;
    private boolean secretValid;
    private boolean signatureValid;
    private boolean accountReadable;
    private boolean tradingAllowed;
    private boolean ipAllowed;
    private boolean networkOk;

    private Map<String, Object> extra;

    // ============================================================
    // 🧠 FACTORY-МЕТОДЫ (то, чего не хватало)
    // ============================================================

    public static ApiKeyDiagnostics notConfigured(String exchange, String message) {
        return ApiKeyDiagnostics.builder()
                .ok(false)
                .exchange(exchange)
                .message(message)
                .apiKeyValid(false)
                .secretValid(false)
                .signatureValid(false)
                .accountReadable(false)
                .tradingAllowed(false)
                .ipAllowed(false)
                .networkOk(false)
                .build();
    }

    public static ApiKeyDiagnostics networkError(String exchange, String message) {
        return ApiKeyDiagnostics.builder()
                .ok(false)
                .exchange(exchange)
                .message(message)
                .networkOk(false)
                .build();
    }

    public static ApiKeyDiagnostics success(String exchange, String message) {
        return ApiKeyDiagnostics.builder()
                .ok(true)
                .exchange(exchange)
                .message(message)
                .apiKeyValid(true)
                .secretValid(true)
                .signatureValid(true)
                .accountReadable(true)
                .tradingAllowed(true)
                .ipAllowed(true)
                .networkOk(true)
                .build();
    }
}

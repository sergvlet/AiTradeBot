package com.chicu.aitradebot.exchange.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class ApiKeyDiagnostics {

    private boolean ok;              // все проверки пройдены
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
}

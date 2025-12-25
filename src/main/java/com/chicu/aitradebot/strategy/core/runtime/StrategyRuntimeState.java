package com.chicu.aitradebot.strategy.core.runtime;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 🧠 Runtime-состояние стратегии
 * Хранится ВНЕ стратегии и передаётся через StrategyContext
 * ✔ используется для исполнения
 * ✔ используется для UI / WebSocket
 * ✔ НЕ содержит бизнес-логики (только состояние + безопасные helper-методы)
 */
@Getter
@Setter
public class StrategyRuntimeState {

    // ============================================================
    // ID (ОБЯЗАТЕЛЬНО для исполнения сделок)
    // ============================================================

    private Long chatId;
    private StrategyType type;
    private String symbol;

    // ============================================================
    // POSITION
    // ============================================================

    private boolean openPosition;
    private Instant positionOpenedAt;
    private Instant lastTradeAt;

    // ============================================================
    // ENTRY / TP / SL  (для графика и сделок)
    // ============================================================

    private BigDecimal entryPrice;
    private BigDecimal takeProfit;
    private BigDecimal stopLoss;

    // ============================================================
    // SCALPING WINDOW (визуализация диапазона)
    // ============================================================

    private BigDecimal windowHigh;
    private BigDecimal windowLow;

    // ============================================================
    // UI / DEBUG
    // ============================================================

    private String lastSignal = "NONE";
    private String lastReason = "";
    private Instant lastUpdatedAt;

    // ============================================================
    // HELPERS (потокобезопасные)
    // ============================================================

    public boolean hasOpenPosition() {
        return openPosition;
    }

    public synchronized void openPosition() {
        this.openPosition = true;
        Instant now = Instant.now();
        this.positionOpenedAt = now;
        this.lastTradeAt = now;
        touch();
    }

    public synchronized void closePosition() {
        this.openPosition = false;
        this.positionOpenedAt = null;
        this.lastTradeAt = Instant.now();

        // чистим цены при выходе
        this.entryPrice = null;
        this.takeProfit = null;
        this.stopLoss = null;

        touch();
    }

    public synchronized void touch() {
        this.lastUpdatedAt = Instant.now();
    }
}

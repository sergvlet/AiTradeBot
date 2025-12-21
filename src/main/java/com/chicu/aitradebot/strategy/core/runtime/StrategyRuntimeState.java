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
 * ✔ НЕ содержит логики
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
    private Instant lastUpdatedAt = Instant.now();

    // ============================================================
    // HELPERS
    // ============================================================

    public boolean hasOpenPosition() {
        return openPosition;
    }

    public void openPosition() {
        this.openPosition = true;
        this.positionOpenedAt = Instant.now();
        this.lastTradeAt = Instant.now();
        touch();
    }

    public void closePosition() {
        this.openPosition = false;
        this.positionOpenedAt = null;
        this.lastTradeAt = Instant.now();

        // чистим цены при выходе
        this.entryPrice = null;
        this.takeProfit = null;
        this.stopLoss = null;

        touch();
    }

    public void touch() {
        this.lastUpdatedAt = Instant.now();
    }
}

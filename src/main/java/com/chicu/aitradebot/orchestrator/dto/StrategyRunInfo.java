package com.chicu.aitradebot.orchestrator.dto;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 📊 DTO состояния стратегии для дашборда / API / фасада.

 * Важно:
 * - startedAt / stoppedAt — это "реальные" моменты запуска/остановки (если мы их сохраняем).
 * - updatedAt — момент формирования этого DTO (обновление статуса для UI).
 * - многие поля (equityUsd, totalTrades, totalProfitPct и т.п.) могут быть null, если подсчёт ещё не внедрён.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyRunInfo {

    // === Основное ===
    private Long chatId;
    private StrategyType type;
    private String symbol;
    private boolean active;

    // === Маркет-параметры ===
    private String timeframe;
    private String exchangeName;   // BINANCE / BYBIT / OKX ...
    private NetworkType networkType;

    // === Финансы ===
    private BigDecimal capitalUsd;       // стартовый капитал (из StrategySettings)
    private BigDecimal equityUsd;        // текущая оценка капитала (опционально)
    private BigDecimal totalProfitPct;   // общий PnL в % (опционально)
    private BigDecimal commissionPct;    // комиссия
    private BigDecimal takeProfitPct;    // TP
    private BigDecimal stopLossPct;      // SL
    private BigDecimal riskPerTradePct;  // риск на сделку в %
    private BigDecimal mlConfidence;     // 0..1 (UI при необходимости умножает на 100)

    // === Статистика (опционально) ===
    private long totalTrades;            // количество сделок (если считаем)
    private boolean reinvestProfit;
    private Integer version;             // версия настроек стратегии

    // === Время ===
    private Instant startedAt;           // реальный момент старта (если есть)
    private Instant stoppedAt;           // реальный момент остановки (если есть)
    private Instant updatedAt;           // время формирования/обновления статуса для UI

    // === Сообщения / статус ===
    private String message;
}

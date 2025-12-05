package com.chicu.aitradebot.orchestrator.dto;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 📊 DTO состояния стратегии для дашборда / API / фасада.
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
    private String exchangeName;   // BINANCE / BYBIT
    private NetworkType networkType;

    // === Финансы ===
    private BigDecimal capitalUsd;       // начальный капитал (из настроек)
    private BigDecimal equityUsd;        // текущий капитал (если считаем)
    private BigDecimal totalProfitPct;   // общий PnL %
    private BigDecimal commissionPct;    // комиссия
    private BigDecimal takeProfitPct;    // TP
    private BigDecimal stopLossPct;      // SL
    private BigDecimal riskPerTradePct;  // риск на сделку
    private BigDecimal mlConfidence;     // 0..1 (для ML стратегий)

    // === Бойлерплейт стратегии ===
    private long totalTrades;      // количество сделок (если будем считать)
    private boolean reinvestProfit;
    private Integer version;       // версия настроек стратегии

    // === Время ===
    private Instant startedAt;
    private Instant stoppedAt;

    // === Сообщения / статус ===
    private String message;
}

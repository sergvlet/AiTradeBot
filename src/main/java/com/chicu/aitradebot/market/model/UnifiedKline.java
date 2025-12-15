package com.chicu.aitradebot.market.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Унифицированная модель свечи (kline) для ВСЕХ бирж.
 * Используется MarketStreamService + StrategyLivePublisher.
 */
@Data
@Builder
public class UnifiedKline {

    /** Время открытия свечи (ms epoch) */
    private long openTime;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;

    private BigDecimal volume;

    /** Таймфрейм в привычном виде: "1s", "1m", "15m", "1h" и т.д. */
    private String timeframe;

    /** Нормализованный символ: BTCUSDT, ETHUSDT и т.п. */
    private String symbol;
}

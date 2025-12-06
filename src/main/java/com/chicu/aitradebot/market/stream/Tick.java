package com.chicu.aitradebot.market.stream;

import java.math.BigDecimal;

/**
 * Унифицированный тикер для всех бирж.
 * Используем BigDecimal для точности.
 */
public record Tick(
        String exchange,     // BINANCE / BYBIT / OKX ...
        String symbol,       // BTCUSDT
        BigDecimal price,    // 94800.25 (точный BigDecimal)
        long tsMillis        // время тика
) {}

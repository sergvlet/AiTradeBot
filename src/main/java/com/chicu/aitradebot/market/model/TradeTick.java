package com.chicu.aitradebot.market.model;

import java.time.Instant;

/**
 * 💹 Один тик (сделка) с биржи для сборки секундных свечей.
 */
public record TradeTick(
        String symbol,
        Instant ts,
        double price,
        double qty,
        boolean isBuy
) {}

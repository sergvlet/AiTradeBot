package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeClosedEvent(
        Long chatId,
        StrategyType strategyType,
        String symbol,
        String timeframe,
        String exchange,
        NetworkType network,
        Instant closedAt,
        String exitReason,
        BigDecimal pnlPct,
        BigDecimal exitPrice
) {}

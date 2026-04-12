package com.chicu.aitradebot.market.model;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;

public record MarketTick(
        Long chatId,
        StrategyType type,
        String exchange,
        NetworkType network,
        String symbol,
        String timeframe,
        BigDecimal price,
        BigDecimal qty,
        long ts
) {}

package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

public record StrategyUi(
        StrategyType type,
        boolean active,
        String title,
        String description,
        Long chatId,
        String symbol,
        double totalProfitPct,
        double mlConfidence,
        NetworkType networkType
) {}

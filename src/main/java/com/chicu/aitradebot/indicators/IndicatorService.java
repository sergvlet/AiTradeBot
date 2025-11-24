package com.chicu.aitradebot.indicators;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface IndicatorService {

    IndicatorResponse loadIndicators(
            Long chatId,
            StrategyType type,
            String symbol,
            String timeframe
    );
}

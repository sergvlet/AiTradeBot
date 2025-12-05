package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.web.dto.StrategyChartDto;

public interface WebChartFacade {

    StrategyChartDto buildChart(
            long chatId,
            String strategyType,
            String symbol,
            String timeframe,
            int limit
    );
}

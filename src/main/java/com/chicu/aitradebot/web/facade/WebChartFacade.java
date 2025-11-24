package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.web.dto.StrategyChartDto;

public interface WebChartFacade {

    StrategyChartDto buildChart(long chatId, String strategyType, int limit, String timeframe);

}

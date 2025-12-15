package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.dto.StrategyChartDto;

public interface WebChartFacade {

    /**
     * Сбор полного снимка стратегического графика:
     *  - свечи
     *  - индикаторы
     *  - сделки
     *  - слои стратегии (levels / zone)
     */
    StrategyChartDto buildChart(
            long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe,
            int limit
    );
}

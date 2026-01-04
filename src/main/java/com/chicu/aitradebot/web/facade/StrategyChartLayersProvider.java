package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.dto.StrategyChartDto;

public interface StrategyChartLayersProvider {

    StrategyType type();

    /**
     * Возвращает layers для snapshot (levels/zone/windowZone и т.д.)
     * Никаких preload свечей здесь не делаем — только layers.
     */
    StrategyChartDto.Layers buildLayers(long chatId, String symbol, String timeframe, StrategyChartDto snapshot);
}

package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;

import java.util.List;

public interface WebStrategyFacade {

    List<StrategyUi> getStrategies(Long chatId);

    void toggle(Long chatId, StrategyType type);

    void start(Long chatId, StrategyType type);

    void stop(Long chatId, StrategyType type);

    StrategyRunInfo toggleStrategy(Long chatId,
                                   StrategyType type,
                                   String symbol,
                                   String timeframe);

    StrategyRunInfo getRunInfo(Long chatId, StrategyType type);
}

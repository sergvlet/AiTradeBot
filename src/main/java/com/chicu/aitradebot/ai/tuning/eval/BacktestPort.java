package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface BacktestPort {

    /**
     * Выполнить бэктест стратегии для chatId, используя candidateParams как override параметров.
     * symbol/timeframe можно передать override, иначе адаптер возьмёт их из твоих StrategySettings.
     */
    BacktestMetrics backtest(Long chatId,
                             StrategyType type,
                             String symbolOverride,
                             String timeframeOverride,
                             Map<String, Object> candidateParams,
                             Instant startAt,
                             Instant endAt);
}

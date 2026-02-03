package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface BacktestPort {

    /**
     * ✅ Новый контракт:
     * Выполнить бэктест в конкретном окружении (exchange + network).
     * symbol/timeframe можно передать override, иначе адаптер возьмёт из StrategySettings.
     */
    BacktestMetrics backtest(Long chatId,
                             StrategyType type,
                             String exchange,
                             NetworkType network,
                             String symbolOverride,
                             String timeframeOverride,
                             Map<String, Object> candidateParams,
                             Instant startAt,
                             Instant endAt);

    /**
     * ✅ BACKWARD COMPAT:
     * Старый метод оставляем, чтобы не ломать существующие вызовы.
     * exchange/network резолвятся внутри адаптера (например, из StrategySettings).
     */
    default BacktestMetrics backtest(Long chatId,
                                     StrategyType type,
                                     String symbolOverride,
                                     String timeframeOverride,
                                     Map<String, Object> candidateParams,
                                     Instant startAt,
                                     Instant endAt) {

        return backtest(chatId, type, null, null, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}

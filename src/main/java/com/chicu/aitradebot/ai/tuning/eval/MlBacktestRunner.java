package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

/**
 * Единый контракт для прогонов (реальный и заглушка).
 * Возвращаем BacktestMetrics, потому что весь тюнинг/оценка уже на нём.
 */
public interface MlBacktestRunner {

    BacktestMetrics run(Long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType network,
                        String symbolOverride,
                        String timeframeOverride,
                        Map<String, Object> candidateParams,
                        Instant startAt,
                        Instant endAt);
}

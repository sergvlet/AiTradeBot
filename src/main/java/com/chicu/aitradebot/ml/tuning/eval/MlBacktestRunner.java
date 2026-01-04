package com.chicu.aitradebot.ml.tuning.eval;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface MlBacktestRunner {

    BacktestMetrics run(Long chatId,
                        StrategyType type,
                        String symbolOverride,
                        String timeframeOverride,
                        Map<String, Object> candidateParams,
                        Instant startAt,
                        Instant endAt);
}

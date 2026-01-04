package com.chicu.aitradebot.ml.tuning.eval;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface BacktestService {

    BacktestMetrics run(Long chatId,
                        StrategyType type,
                        String symbol,
                        String timeframe,
                        Map<String, Object> candidateParams,
                        Instant startAt,
                        Instant endAt);
}

package com.chicu.aitradebot.ml.tuning.eval;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.List;

public interface BacktestCandlePort {

    List<CandleBar> load(long chatId,
                         StrategyType type,
                         String symbol,
                         String timeframe,
                         Instant startAt,
                         Instant endAt,
                         int limit);
}

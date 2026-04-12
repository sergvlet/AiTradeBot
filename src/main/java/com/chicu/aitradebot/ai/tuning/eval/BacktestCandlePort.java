package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.List;

public interface BacktestCandlePort {

    /**
     * ✅ Новый контракт: свечи грузим строго по exchange + network.
     */
    List<CandleBar> load(long chatId,
                         StrategyType type,
                         String exchange,
                         NetworkType network,
                         String symbol,
                         String timeframe,
                         Instant startAt,
                         Instant endAt,
                         int limit);

    /**
     * ✅ BACKWARD COMPAT: старые вызовы без exchange/network.
     */
    default List<CandleBar> load(long chatId,
                                 StrategyType type,
                                 String symbol,
                                 String timeframe,
                                 Instant startAt,
                                 Instant endAt,
                                 int limit) {

        return load(chatId, type, null, null, symbol, timeframe, startAt, endAt, limit);
    }
}
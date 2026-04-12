package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface BacktestService {

    /**
     * ✅ Новый контракт (для ML-тюнинга):
     * backtest знает биржу/сеть, чтобы все данные/прогрев/клиент шли строго из нужного окружения.
     */
    BacktestMetrics run(Long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType network,
                        String symbol,
                        String timeframe,
                        Map<String, Object> candidateParams,
                        Instant startAt,
                        Instant endAt);

    /**
     * ✅ BACKWARD COMPAT:
     * старый вызов оставляем, чтобы не ломать существующий код.
     * (exchange/network = null → реализация сама резолвит через StrategyEnvResolver или StrategySettings)
     */
    default BacktestMetrics run(Long chatId,
                                StrategyType type,
                                String symbol,
                                String timeframe,
                                Map<String, Object> candidateParams,
                                Instant startAt,
                                Instant endAt) {

        return run(chatId, type, null, null, symbol, timeframe, candidateParams, startAt, endAt);
    }
}

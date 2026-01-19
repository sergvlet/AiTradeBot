package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface MlBacktestRunner {

    /**
     * ✅ Новый контракт:
     * backtest должен знать биржу и сеть, чтобы warmup/klines шли строго из нужного окружения.
     */
    BacktestMetrics run(Long chatId,
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
     * старый метод оставляем, чтобы не ломать существующие вызовы.
     */
    default BacktestMetrics run(Long chatId,
                               StrategyType type,
                               String symbolOverride,
                               String timeframeOverride,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        return run(chatId, type, null, null, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}

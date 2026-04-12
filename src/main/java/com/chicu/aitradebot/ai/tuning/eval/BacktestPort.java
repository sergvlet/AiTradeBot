package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.time.Instant;
import java.util.Map;

public interface BacktestPort {

    /**
     * ✅ Базовый контракт (обязательный):
     * Выполнить бэктест.
     * symbol/timeframe можно передать override, иначе адаптер/порт возьмёт из StrategySettings.
     */
    BacktestMetrics backtest(Long chatId,
                             StrategyType type,
                             String symbolOverride,
                             String timeframeOverride,
                             Map<String, Object> candidateParams,
                             Instant startAt,
                             Instant endAt);

    /**
     * ✅ Расширенный контракт (опциональный):
     * Позволяет вызывать бэктест в конкретном окружении (exchange + network).
     *
     * По умолчанию делегирует в базовый метод (т.е. может игнорировать env),
     * а реализация, которой важно env — просто override-ит этот метод.
     */
    default BacktestMetrics backtest(Long chatId,
                                     StrategyType type,
                                     String exchange,
                                     NetworkType network,
                                     String symbolOverride,
                                     String timeframeOverride,
                                     Map<String, Object> candidateParams,
                                     Instant startAt,
                                     Instant endAt) {

        return backtest(chatId, type, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
    }
}
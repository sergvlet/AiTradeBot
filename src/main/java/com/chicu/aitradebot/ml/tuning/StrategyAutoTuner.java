package com.chicu.aitradebot.ml.tuning;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface StrategyAutoTuner {

    StrategyType getStrategyType();

    /**
     * Запуск тюнинга стратегии для конкретного chatId.
     * Детали (candles, param space, backtest, xgb) — будут добавляться пошагово.
     */
    TuningResult tune(TuningRequest request);
}

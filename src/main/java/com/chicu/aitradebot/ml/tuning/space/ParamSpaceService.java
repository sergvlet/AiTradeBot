package com.chicu.aitradebot.ml.tuning.space;

import com.chicu.aitradebot.common.enums.StrategyType;

import java.util.Map;

public interface ParamSpaceService {

    StrategyType getStrategyType();

    /**
     * Возвращает пространство параметров для стратегии (только enabled=true).
     * Ключ = paramName.
     */
    Map<String, ParamSpaceItem> loadEnabledSpace();
}

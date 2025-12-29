package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface StrategyAdvancedProvider {

    StrategyType getType();

    /**
     * Возвращает HTML (строкой) для advanced-параметров стратегии
     */
    String buildAdvancedHtml(long chatId, boolean readOnly);
}

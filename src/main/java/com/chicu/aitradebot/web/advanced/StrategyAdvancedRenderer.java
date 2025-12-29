package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;

public interface StrategyAdvancedRenderer {

    StrategyType supports();

    /**
     * @return готовый HTML (bootstrap), который вставится через th:utext
     */
    String render(AdvancedRenderContext ctx);

    /**
     * Обработка submit advanced-параметров
     * (вызывается ТОЛЬКО если mode != AI)
     */
    default void handleSubmit(AdvancedRenderContext context) {
    }

}

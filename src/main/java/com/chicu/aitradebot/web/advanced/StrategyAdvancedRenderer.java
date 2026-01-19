package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface StrategyAdvancedRenderer {

    StrategyType supports();

    String render(AdvancedRenderContext ctx);

    void handleSubmit(AdvancedRenderContext ctx);
}

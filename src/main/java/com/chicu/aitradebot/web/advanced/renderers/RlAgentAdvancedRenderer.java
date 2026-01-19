// src/main/java/com/chicu/aitradebot/web/advanced/renderers/RlAgentAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced.renderers;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.rl.RlAgentStrategySettings;
import org.springframework.stereotype.Component;

@Component
public class RlAgentAdvancedRenderer extends AbstractEntityAdvancedRenderer<RlAgentStrategySettings> {

    public RlAgentAdvancedRenderer() {
        super(RlAgentStrategySettings.class);
    }

    @Override
    public StrategyType supports() {
        return StrategyType.RL_AGENT;
    }
}

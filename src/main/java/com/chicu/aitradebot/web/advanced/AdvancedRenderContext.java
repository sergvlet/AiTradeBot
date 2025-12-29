package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class AdvancedRenderContext {

    Long chatId;
    StrategyType strategyType;

    String exchange;
    NetworkType networkType;

    AdvancedControlMode controlMode;

    /**
     * Все POST-параметры формы (name -> value)
     */
    Map<String, String> params;

    /**
     * true если поля должны быть заблокированы (AI)
     */
    public boolean isReadOnly() {
        return controlMode == AdvancedControlMode.AI;
    }
}

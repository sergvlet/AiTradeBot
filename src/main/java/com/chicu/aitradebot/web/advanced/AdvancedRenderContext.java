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

    public boolean isAi()     { return controlMode == AdvancedControlMode.AI; }
    public boolean isHybrid() { return controlMode == AdvancedControlMode.HYBRID; }
    public boolean isManual() { return controlMode == AdvancedControlMode.MANUAL; }

    /** true если ручные поля запрещены */
    public boolean isReadOnly() { return isAi(); }

    /** true если можно сохранять ручные параметры стратегии */
    public boolean canSubmit() { return !isAi(); }
}
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

    Map<String, String> params;

    public boolean isAi() {
        return controlMode == AdvancedControlMode.AI;
    }

    public boolean isHybrid() {
        return controlMode == AdvancedControlMode.HYBRID;
    }

    public boolean isManual() {
        return controlMode == AdvancedControlMode.MANUAL;
    }

    public boolean isReadOnly() {
        return isAi();
    }

    public boolean canSubmit() {
        return !isAi();
    }
}
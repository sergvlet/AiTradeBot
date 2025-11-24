package com.chicu.aitradebot.indicators;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class IndicatorServiceRegistry {

    private final Map<StrategyType, IndicatorService> registry;

    public IndicatorService get(StrategyType type) {
        return registry.get(type);
    }
}

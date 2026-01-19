package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyAdvancedRegistry {

    private final Map<StrategyType, StrategyAdvancedRenderer> map = new EnumMap<>(StrategyType.class);

    public StrategyAdvancedRegistry(List<StrategyAdvancedRenderer> renderers) {
        if (renderers != null) {
            for (StrategyAdvancedRenderer r : renderers) {
                if (r != null && r.supports() != null) {
                    map.put(r.supports(), r);
                }
            }
        }
    }

    public StrategyAdvancedRenderer get(StrategyType type) {
        return type == null ? null : map.get(type);
    }
}

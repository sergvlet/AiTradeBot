package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyAdvancedRegistry {

    private final List<StrategyAdvancedRenderer> renderers;

    private final Map<StrategyType, StrategyAdvancedRenderer> byType =
            new EnumMap<>(StrategyType.class);

    @PostConstruct
    public void init() {
        for (StrategyAdvancedRenderer r : renderers) {

            StrategyType type = r.supports();

            if (type == null) {
                log.warn("⚠ StrategyAdvancedRenderer {} returned null supports()",
                        r.getClass().getSimpleName());
                continue;
            }

            if (byType.containsKey(type)) {
                throw new IllegalStateException(
                        "Duplicate StrategyAdvancedRenderer for " + type +
                        ": " + r.getClass().getSimpleName()
                );
            }

            byType.put(type, r);

            log.info("🧩 Advanced renderer registered: {} -> {}",
                    type, r.getClass().getSimpleName());
        }
    }

    public StrategyAdvancedRenderer get(StrategyType type) {
        StrategyAdvancedRenderer r = byType.get(type);

        if (r == null) {
            log.debug("ℹ No StrategyAdvancedRenderer for {}", type);
        }

        return r;
    }

    public boolean hasRenderer(StrategyType type) {
        return byType.containsKey(type);
    }
}

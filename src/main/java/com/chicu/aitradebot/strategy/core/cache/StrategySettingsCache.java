package com.chicu.aitradebot.strategy.core.cache;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Глобальный кэш-инвалидатор настроек стратегий (V4)
 */
@Slf4j
@Component
public class StrategySettingsCache {

    /**
     * chatId -> set of invalidated strategies
     */
    private final Map<Long, Set<StrategyType>> invalidated =
            new ConcurrentHashMap<>();

    public void invalidate(long chatId, StrategyType type) {
        invalidated
                .computeIfAbsent(chatId, id -> ConcurrentHashMap.newKeySet())
                .add(type);

        log.info("♻️ Settings cache INVALIDATED chatId={} strategy={}", chatId, type);
    }

    public boolean isInvalidated(long chatId, StrategyType type) {
        return invalidated.containsKey(chatId)
                && invalidated.get(chatId).contains(type);
    }

    public void clear(long chatId, StrategyType type) {
        Set<StrategyType> set = invalidated.get(chatId);
        if (set != null) {
            set.remove(type);
            if (set.isEmpty()) {
                invalidated.remove(chatId);
            }
        }
    }
}

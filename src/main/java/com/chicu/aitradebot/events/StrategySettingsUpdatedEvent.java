package com.chicu.aitradebot.events;

import com.chicu.aitradebot.common.enums.StrategyType;

/**
 * Событие о том, что StrategySettings изменились (после коммита).
 * source — кто/что инициировало изменение (ui, tuner, ml, system и т.д.).
 */
public record StrategySettingsUpdatedEvent(long chatId, StrategyType type, String source) {

    /**
     * ✅ Backward-compat: старые вызовы new StrategySettingsUpdatedEvent(chatId, type)
     * (source будет "update").
     */
    public StrategySettingsUpdatedEvent(long chatId, StrategyType type) {
        this(chatId, type, "update");
    }

    /**
     * ✅ Backward-compat: старые вызовы с Long (например из сервисов/контроллеров).
     */
    public StrategySettingsUpdatedEvent(Long chatId, StrategyType type) {
        this(chatId != null ? chatId : 0L, type, "update");
    }

    /**
     * ✅ Удобный фабричный метод для явного source.
     */
    public static StrategySettingsUpdatedEvent of(long chatId, StrategyType type, String source) {
        return new StrategySettingsUpdatedEvent(chatId, type, source);
    }
}
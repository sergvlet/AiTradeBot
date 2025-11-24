package com.chicu.aitradebot.strategy.core;

/**
 * Минимальный интерфейс провайдера настроек стратегии.
 *
 * Нужен для совместимости со старыми сервисами настроек
 * (например SmartFusionStrategySettingsServiceImpl и т.п.).
 *
 * Позже можно будет расширить/заменить в рамках v4.
 */
public interface StrategySettingsProvider<T> {

    /**
     * Загрузить настройки стратегии для указанного chatId.
     */
    T load(long chatId);
}

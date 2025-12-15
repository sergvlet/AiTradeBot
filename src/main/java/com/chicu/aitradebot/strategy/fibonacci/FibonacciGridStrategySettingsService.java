package com.chicu.aitradebot.strategy.fibonacci;

public interface FibonacciGridStrategySettingsService {

    /**
     * Получить настройки или создать дефолтные.
     */
    FibonacciGridStrategySettings getOrCreate(Long chatId);

    /**
     * Сохранить настройки.
     */
    FibonacciGridStrategySettings save(FibonacciGridStrategySettings settings);

    /**
     * Обновить настройки (частичное обновление).
     */
    FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings dto);

    FibonacciGridStrategySettings getLatest(Long chatId);

}

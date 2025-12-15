package com.chicu.aitradebot.strategy.scalping;

/**
 * Сервис работы с настройками скальпинговой стратегии для конкретного chatId.
 */
public interface ScalpingStrategySettingsService {

    /**
     * Найти настройки по chatId или создать дефолтные, если записи ещё нет.
     */
    ScalpingStrategySettings getOrCreate(Long chatId);

    /**
     * Сохранить (insert/update) настройки.
     */
    ScalpingStrategySettings save(ScalpingStrategySettings settings);

    /**
     * Обновить существующие настройки по chatId данными из dto.
     * Обычно используется из UI/Telegram.
     */
    ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings dto);
}

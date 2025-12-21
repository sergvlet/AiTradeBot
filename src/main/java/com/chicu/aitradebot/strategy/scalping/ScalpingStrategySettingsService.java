package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.strategy.core.SettingsSnapshot;

/**
 * Сервис работы с настройками скальпинговой стратегии для конкретного chatId.
 */
public interface ScalpingStrategySettingsService {

    // =====================================================
    // JPA / UI / TELEGRAM
    // =====================================================

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

    // =====================================================
    // V4 STRATEGY (❗ ТОЛЬКО ЭТО ВИДИТ СТРАТЕГИЯ)
    // =====================================================

    /**
     * Immutable snapshot настроек для исполнения стратегии.
     * ❗ НЕ JPA, ❗ БЕЗ EntityManager
     */
    SettingsSnapshot getSnapshot(long chatId);
}

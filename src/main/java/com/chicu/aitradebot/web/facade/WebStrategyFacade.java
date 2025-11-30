package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.util.List;

/**
 * WebStrategyFacade — единая точка управления стратегиями из Web/UI.
 *
 * Web не имеет права напрямую управлять стратегиями.
 * Всё делается только через этот фасад.
 */
public interface WebStrategyFacade {

    /** Получить список стратегий пользователя */
    List<StrategyUi> getStrategies(Long chatId);

    /** Запустить стратегию */
    void start(Long chatId, StrategyType strategyType);

    /** Остановить стратегию */
    void stop(Long chatId, StrategyType strategyType);

    /** Вкл/Выкл */
    void toggle(Long chatId, StrategyType strategyType);

    // =============================================================
    // DTO → используется в strategies.html
    // =============================================================
    record StrategyUi(
            StrategyType strategyType,   // ENUM (для ссылок / кнопок)
            boolean active,              // состояние
            String title,                // UI-заголовок
            String description,          // UI-описание
            Long chatId,
            String symbol,
            double totalProfitPct,
            double mlConfidence,
            NetworkType networkType      // ⚠ было отсутствовало — добавлено
    ) {}
}

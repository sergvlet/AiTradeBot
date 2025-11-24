package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import java.util.List;

/**
 * WebStrategyFacade — единая точка управления стратегиями из Web/UI.
 *
 * Web не имеет права:
 *  - запускать стратегии напрямую
 *  - останавливать стратегии напрямую
 *  - обращаться к StrategyFacade / Scheduler
 *  - работать с реальными Strategy-классами
 *
 * Только через этот фасад.
 */
public interface WebStrategyFacade {

    /**
     * Получить список стратегий пользователя.
     */
    List<StrategyUi> getStrategies(Long chatId);

    /**
     * Запустить стратегию.
     */
    void start(Long chatId, StrategyType strategyType);

    /**
     * Остановить стратегию.
     */
    void stop(Long chatId, StrategyType strategyType);

    /**
     * Вкл/Выкл стратегию (удобно для UI).
     */
    void toggle(Long chatId, StrategyType strategyType);

    // =============================================================
    // DTO для UI — полностью совместим с шаблоном strategies.html
    // =============================================================
    record StrategyUi(
            StrategyType strategyType,   // ENUM (используется в ссылках и data параметрах)
            boolean active,              // статус
            String title,                // отображаемое имя стратегии
            String description,          // краткое описание
            Long chatId,                 // привязка к пользователю
            String symbol,               // актив
            double totalProfitPct,       // накопленный PnL %
            double mlConfidence,         // уверенность ML (0..1)
            NetworkType networkType      // ✅ ДОБАВЛЕНО — требуется шаблону
    ) {}
}

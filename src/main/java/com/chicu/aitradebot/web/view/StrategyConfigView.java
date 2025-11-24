package com.chicu.aitradebot.web.view;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * View-модель для страницы настройки стратегии (strategy-config.html).
 * Держит только то, что нужно шаблону.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfigView {

    /** Тип стратегии (SMART_FUSION, SCALPING, FIBONACCI_GRID и т.д.) */
    private StrategyType strategyType;

    /** Название для UI ("Smart Fusion AI", "Scalping v2.0" и т.п.) */
    private String strategyName;

    /** Описание стратегии (короткий текст) */
    private String description;

    /** Текущий пользователь / владелец стратегии */
    private Long chatId;

    /** Основной символ стратегии (BTCUSDT, ETHUSDT и т.д.) */
    private String symbol;
}

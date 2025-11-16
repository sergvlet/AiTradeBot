package com.chicu.aitradebot.web.model;

import lombok.*;
import java.math.BigDecimal;

/**
 * 🌐 DTO для отображения стратегий в веб-интерфейсе.
 * Используется страницей /strategies и контроллером StrategyController.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyViewModel {

    private Long id;

    private Long chatId;

    /** Тип стратегии (например SMART_FUSION, RSI_EMA и т.д.) */
    private String strategyType;

    /** Человеко-читаемое имя стратегии */
    private String strategyName;

    /** Символ (пара) */
    private String symbol;

    /** Активна ли стратегия */
    private boolean active;

    /** Суммарный профит в % */
    private BigDecimal totalProfitPct;

    /** ML-доверие (0..1) */
    private BigDecimal mlConfidence;

    /** URL страницы настроек */
    private String settingsUrl;

    /** URL страницы подробностей */
    private String detailsUrl;
}

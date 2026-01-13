// src/main/java/com/chicu/aitradebot/strategy/fibonacci_grid/FibonacciGridStrategySettings.java
package com.chicu.aitradebot.strategy.fibonacci_grid;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fibonacci_grid_strategy_settings")
public class FibonacciGridStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Кол-во уровней сетки (покупки вниз от базовой цены).
     * Пример: 6
     */
    @Column(nullable = false)
    private Integer gridLevels;

    /**
     * Шаг между уровнями в процентах (0.5 = 0.5%)
     * Пример: 0.5
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal distancePct;

    /**
     * Объём одной покупки (в USD/USDT или "как трактует TradeExecutionService").
     * Если у тебя TradeExecutionService сам считает qty от StrategySettings.capitalUsd — можешь оставить 0/NULL.
     */
    @Column(precision = 38, scale = 18)
    private BigDecimal orderVolume;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;

        if (gridLevels == null) gridLevels = 6;
        if (distancePct == null) distancePct = new BigDecimal("0.5");
        // orderVolume может быть null — тогда объём берётся из StrategySettings/TradeExecutionService
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (gridLevels == null) gridLevels = 6;
        if (distancePct == null) distancePct = new BigDecimal("0.5");
    }
}

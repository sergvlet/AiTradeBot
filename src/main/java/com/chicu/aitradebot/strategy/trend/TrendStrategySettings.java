// src/main/java/com/chicu/aitradebot/strategy/trend/TrendStrategySettings.java
package com.chicu.aitradebot.strategy.trend;

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
@Table(name = "trend_strategy_settings")
public class TrendStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chatId;

    /**
     * Быстрая EMA по тикам (на основе price update).
     */
    @Column(nullable = false)
    private Integer emaFastPeriod;

    /**
     * Медленная EMA по тикам.
     */
    @Column(nullable = false)
    private Integer emaSlowPeriod;

    /**
     * Минимальная разница между EMA в %, чтобы считать тренд достаточным.
     * Например 0.10 = 0.10%
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal trendThresholdPct;

    /**
     * Защита от “дребезга”: минимум миллисекунд между входами/выходами.
     */
    @Column(nullable = false)
    private Integer cooldownMs;

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

        if (emaFastPeriod == null) emaFastPeriod = 9;
        if (emaSlowPeriod == null) emaSlowPeriod = 21;
        if (trendThresholdPct == null) trendThresholdPct = new BigDecimal("0.10"); // 0.10%
        if (cooldownMs == null) cooldownMs = 1500;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (emaFastPeriod == null) emaFastPeriod = 9;
        if (emaSlowPeriod == null) emaSlowPeriod = 21;
        if (trendThresholdPct == null) trendThresholdPct = new BigDecimal("0.10");
        if (cooldownMs == null) cooldownMs = 1500;
    }
}

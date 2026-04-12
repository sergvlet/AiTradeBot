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
@Table(
        name = "fibonacci_grid_strategy_settings",
        indexes = {
                @Index(name = "ix_fibo_grid_settings_chat", columnList = "chat_id")
        }
)
public class FibonacciGridStrategySettings {

    private static final BigDecimal DEF_DISTANCE_PCT = new BigDecimal("0.5");
    private static final BigDecimal DEF_TP_PCT = new BigDecimal("0.80");
    private static final BigDecimal DEF_SL_PCT = new BigDecimal("1.20");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /**
     * Кол-во уровней сетки вниз от базовой цены.
     */
    @Column(nullable = false)
    private Integer gridLevels;

    /**
     * Шаг между уровнями в процентах.
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal distancePct;

    /**
     * TP для каждого входа.
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal takeProfitPct;

    /**
     * SL для каждого входа.
     */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal stopLossPct;

    /**
     * Объём одной покупки, если исполнение стратегии его использует.
     * Если null, объём берётся из общих настроек исполнения.
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
        normalize();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalize();
    }

    private void normalize() {
        if (gridLevels == null || gridLevels < 1) gridLevels = 6;
        if (distancePct == null || distancePct.signum() <= 0) distancePct = DEF_DISTANCE_PCT;
        if (takeProfitPct == null || takeProfitPct.signum() <= 0) takeProfitPct = DEF_TP_PCT;
        if (stopLossPct == null || stopLossPct.signum() <= 0) stopLossPct = DEF_SL_PCT;
    }
}

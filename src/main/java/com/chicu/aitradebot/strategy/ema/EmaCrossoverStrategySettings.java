package com.chicu.aitradebot.strategy.ema;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Настройки стратегии EMA_CROSSOVER.
 *
 * Для прод-режима TP/SL должны храниться в таблице стратегии,
 * чтобы live-торговля, ML-подготовка и визуализация использовали
 * один и тот же источник истины.
 */
@Entity
@Table(
        name = "ema_crossover_strategy_settings",
        indexes = @Index(name = "ix_ema_crossover_chat", columnList = "chat_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmaCrossoverStrategySettings {

    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("0.80");
    private static final BigDecimal MIN_PCT = new BigDecimal("0.01");
    private static final BigDecimal MAX_PCT = new BigDecimal("50.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Builder.Default
    @Column(name = "ema_fast", nullable = false)
    private Integer emaFast = 9;

    @Builder.Default
    @Column(name = "ema_slow", nullable = false)
    private Integer emaSlow = 21;

    @Builder.Default
    @Column(name = "confirm_bars", nullable = false)
    private Integer confirmBars = 1;

    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.08d;

    @Builder.Default
    @Column(name = "take_profit_pct", precision = 10, scale = 4, nullable = false)
    private BigDecimal takeProfitPct = DEFAULT_TP_PCT;

    @Builder.Default
    @Column(name = "stop_loss_pct", precision = 10, scale = 4, nullable = false)
    private BigDecimal stopLossPct = DEFAULT_SL_PCT;

    @Version
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        normalize();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        normalize();
        updatedAt = Instant.now();
    }

    private void normalize() {
        if (chatId == null || chatId <= 0) {
            throw new IllegalArgumentException("chatId must be positive");
        }

        int fast = (emaFast != null ? emaFast : 9);
        int slow = (emaSlow != null ? emaSlow : 21);
        int confirm = (confirmBars != null ? confirmBars : 1);
        double spread = (maxSpreadPct != null ? maxSpreadPct : 0.08d);
        BigDecimal tp = sanitizePct(takeProfitPct, DEFAULT_TP_PCT);
        BigDecimal sl = sanitizePct(stopLossPct, DEFAULT_SL_PCT);

        if (fast < 1) fast = 1;
        if (fast > 300) fast = 300;

        if (slow < 2) slow = 2;
        if (slow <= fast) slow = fast + 1;
        if (slow > 600) slow = 600;

        if (confirm < 1) confirm = 1;
        if (confirm > 10) confirm = 10;

        if (!Double.isFinite(spread) || spread < 0.0d) spread = 0.0d;
        if (spread > 100.0d) spread = 100.0d;

        emaFast = fast;
        emaSlow = slow;
        confirmBars = confirm;
        maxSpreadPct = spread;
        takeProfitPct = tp;
        stopLossPct = sl;
    }

    private static BigDecimal sanitizePct(BigDecimal value, BigDecimal def) {
        BigDecimal pct = value != null ? value : def;
        if (pct.signum() <= 0) pct = def;
        if (pct.compareTo(MIN_PCT) < 0) pct = MIN_PCT;
        if (pct.compareTo(MAX_PCT) > 0) pct = MAX_PCT;
        return pct.setScale(4, RoundingMode.HALF_UP);
    }
}

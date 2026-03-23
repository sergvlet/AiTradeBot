package com.chicu.aitradebot.strategy.windowscalping;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "window_scalping_strategy_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_window_scalping_settings_chat",
                columnNames = {"chat_id"}
        ),
        indexes = {
                @Index(name = "ix_window_scalping_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WindowScalpingStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // =====================================================
    // TP / SL (fallback static, в %)
    // =====================================================

    /**
     * Fallback TP в процентах.
     * Используется если auto TP/SL выключен или динамика не смогла рассчитаться.
     */
    @Builder.Default
    @Column(name = "take_profit_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal takeProfitPct = new BigDecimal("0.60");

    /**
     * Fallback SL в процентах.
     * Используется если auto TP/SL выключен или динамика не смогла рассчитаться.
     */
    @Builder.Default
    @Column(name = "stop_loss_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal stopLossPct = new BigDecimal("0.35");

    // =====================================================
    // AUTO TP / SL
    // =====================================================

    /** Включить автоподстройку TP/SL под текущий диапазон окна. */
    @Builder.Default
    @Column(name = "auto_tp_sl_enabled", nullable = false)
    private Boolean autoTpSlEnabled = Boolean.TRUE;

    /** SL = rangePct * autoSlFromRangeFactor */
    @Builder.Default
    @Column(name = "auto_sl_from_range_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlFromRangeFactor = new BigDecimal("1.80");

    /** TP = max(rangePct * autoTpFromRangeFactor, SL * autoMinRiskReward) */
    @Builder.Default
    @Column(name = "auto_tp_from_range_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpFromRangeFactor = new BigDecimal("5.50");

    /** Минимальный RR для динамического TP. */
    @Builder.Default
    @Column(name = "auto_min_risk_reward", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoMinRiskReward = new BigDecimal("2.40");

    /** Нижняя граница динамического SL, %. */
    @Builder.Default
    @Column(name = "auto_sl_min_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlMinPct = new BigDecimal("0.04");

    /** Верхняя граница динамического SL, %. */
    @Builder.Default
    @Column(name = "auto_sl_max_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoSlMaxPct = new BigDecimal("0.18");

    /** Нижняя граница динамического TP, %. */
    @Builder.Default
    @Column(name = "auto_tp_min_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMinPct = new BigDecimal("0.10");

    /** Верхняя граница динамического TP, %. */
    @Builder.Default
    @Column(name = "auto_tp_max_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMaxPct = new BigDecimal("0.80");

    /** Мультипликатор TP для сильного ML-сигнала. */
    @Builder.Default
    @Column(name = "auto_tp_ml_boost_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpMlBoostFactor = new BigDecimal("1.15");

    /** Мультипликатор TP для слабого сигнала около порога. */
    @Builder.Default
    @Column(name = "auto_tp_weak_signal_factor", nullable = false, precision = 19, scale = 8)
    private BigDecimal autoTpWeakSignalFactor = new BigDecimal("0.90");

    // =====================================================
    // WINDOW
    // =====================================================

    /** Размер окна (кол-во тиков/баров для high/low) */
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 30;

    /** Вход "у низа" в % диапазона окна. */
    @Builder.Default
    @Column(name = "entry_from_low_pct", nullable = false)
    private Double entryFromLowPct = 20.0;

    /** Зона "у верха" в % диапазона окна. */
    @Builder.Default
    @Column(name = "entry_from_high_pct", nullable = false)
    private Double entryFromHighPct = 20.0;

    /** Минимальная ширина диапазона окна в %. */
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.25;

    /** Максимальный спред (%) */
    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.08;

    // =====================================================
    // TECH
    // =====================================================

    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}

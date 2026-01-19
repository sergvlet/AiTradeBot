package com.chicu.aitradebot.strategy.windowscalping;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "window_scalping_strategy_settings",
        indexes = @Index(name = "ix_window_scalping_chat", columnList = "chat_id")
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
    // ✅ TP/SL ДЛЯ ЭТОЙ СТРАТЕГИИ (в %)
    // =====================================================

    /** Take Profit в процентах (например 0.60 = 0.60%) */
    @Builder.Default
    @Column(name = "take_profit_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal takeProfitPct = new BigDecimal("0.60");

    /** Stop Loss в процентах (например 0.35 = 0.35%) */
    @Builder.Default
    @Column(name = "stop_loss_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal stopLossPct = new BigDecimal("0.35");

    // =====================================================
    // ✅ ТОЛЬКО ПОЛЯ WINDOW SCALPING
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
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}

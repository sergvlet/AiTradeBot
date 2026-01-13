package com.chicu.aitradebot.strategy.meanreversion;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "mean_reversion_strategy_settings",
        indexes = @Index(name = "ix_mean_reversion_chat", columnList = "chat_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeanReversionStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // =====================================================
    // 🔹 BOLLINGER BANDS
    // =====================================================

    @Builder.Default
    @Column(name = "bb_period", nullable = false)
    private Integer bbPeriod = 20;

    @Builder.Default
    @Column(name = "bb_stddev", nullable = false)
    private Double bbStdDev = 2.0;

    /** Насколько цена должна выйти за полосу (в %) */
    @Builder.Default
    @Column(name = "entry_deviation_pct", nullable = false)
    private Double entryDeviationPct = 0.15;

    /** Приближение к средней для выхода (%) */
    @Builder.Default
    @Column(name = "exit_to_mean_pct", nullable = false)
    private Double exitToMeanPct = 0.05;

    // =====================================================
    // 🔹 RSI ФИЛЬТР
    // =====================================================

    @Builder.Default
    @Column(name = "rsi_period", nullable = false)
    private Integer rsiPeriod = 14;

    @Builder.Default
    @Column(name = "rsi_buy_below", nullable = false)
    private Double rsiBuyBelow = 30.0;

    @Builder.Default
    @Column(name = "rsi_sell_above", nullable = false)
    private Double rsiSellAbove = 70.0;

    // =====================================================
    // ТЕХ.ПОЛЯ
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

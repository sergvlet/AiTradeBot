package com.chicu.aitradebot.strategy.momentum;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "momentum_strategy_settings",
        indexes = @Index(name = "ix_momentum_chat", columnList = "chat_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MomentumStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // =====================================================
    // ✅ ТОЛЬКО ПАРАМЕТРЫ MOMENTUM
    // =====================================================

    /** Окно (в тиках/свечах) для оценки импульса */
    @Builder.Default
    @Column(name = "lookback_bars", nullable = false)
    private Integer lookbackBars = 20;

    /** Минимальный импульс в % (например 0.6 = +0.6%) */
    @Builder.Default
    @Column(name = "min_price_change_pct", nullable = false)
    private Double minPriceChangePct = 0.6;

    /** (на будущее) объём к среднему — если подключишь данные по объёму */
    @Builder.Default
    @Column(name = "volume_to_average", nullable = false)
    private Double volumeToAverage = 1.5;

    /** (на будущее) фильтр спреда — если будет источник спреда */
    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.08;

    /** Сколько баров подтверждения после сигнала (минимально 1) */
    @Builder.Default
    @Column(name = "confirm_bars", nullable = false)
    private Integer confirmBars = 1;

    // =====================================================
    // ТЕХ.ПОЛЯ
    // =====================================================

    @Version
    @Column(name = "version", nullable = false)
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

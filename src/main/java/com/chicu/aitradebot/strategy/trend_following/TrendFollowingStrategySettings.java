package com.chicu.aitradebot.strategy.trend_following;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "trend_following_strategy_settings",
        indexes = @Index(name = "ix_trend_following_chat", columnList = "chat_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendFollowingStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // =====================================================
    // 🔹 ПАРАМЕТРЫ ТРЕНДА
    // =====================================================

    /** Быстрая EMA */
    @Builder.Default
    @Column(name = "ema_fast", nullable = false)
    private Integer emaFast = 20;

    /** Медленная EMA */
    @Builder.Default
    @Column(name = "ema_slow", nullable = false)
    private Integer emaSlow = 50;

    /** EMA глобального тренда */
    @Builder.Default
    @Column(name = "ema_trend", nullable = false)
    private Integer emaTrend = 200;

    /** Минимальная дистанция EMA fast / slow (%) */
    @Builder.Default
    @Column(name = "min_ema_diff_pct", nullable = false)
    private Double minEmaDiffPct = 0.15;

    /** Минимальный наклон трендовой EMA (%) */
    @Builder.Default
    @Column(name = "min_trend_slope_pct", nullable = false)
    private Double minTrendSlopePct = 0.0;

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

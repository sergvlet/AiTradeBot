package com.chicu.aitradebot.strategy.ema;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // =====================================================
    // 🔹 ПАРАМЕТРЫ EMA CROSSOVER
    // =====================================================

    /** Быстрая EMA */
    @Builder.Default
    @Column(name = "ema_fast", nullable = false)
    private Integer emaFast = 9;

    /** Медленная EMA */
    @Builder.Default
    @Column(name = "ema_slow", nullable = false)
    private Integer emaSlow = 21;

    /** Сколько баров подтверждения после пересечения */
    @Builder.Default
    @Column(name = "confirm_bars", nullable = false)
    private Integer confirmBars = 1;

    /** Максимальный допустимый спред (%) */
    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.08;

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

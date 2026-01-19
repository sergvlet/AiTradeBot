package com.chicu.aitradebot.strategy.windowscalping;

import jakarta.persistence.*;
import lombok.*;

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
    // ✅ ТОЛЬКО ПОЛЯ WINDOW SCALPING
    // =====================================================

    /** Размер окна (кол-во тиков/баров для high/low) */
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 30;

    /**
     * Вход "у низа" в % диапазона окна.
     * Пример: 20.0 = вход в нижних 20% диапазона.
     */
    @Builder.Default
    @Column(name = "entry_from_low_pct", nullable = false)
    private Double entryFromLowPct = 20.0;

    /**
     * Зона "у верха" в % диапазона окна.
     * Пример: 20.0 = верхние 20% диапазона.
     */
    @Builder.Default
    @Column(name = "entry_from_high_pct", nullable = false)
    private Double entryFromHighPct = 20.0;

    /**
     * Минимальная ширина диапазона окна в %.
     * Если окно слишком узкое — не торгуем.
     */
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.25;

    /**
     * Максимальный спред (%)

     */
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

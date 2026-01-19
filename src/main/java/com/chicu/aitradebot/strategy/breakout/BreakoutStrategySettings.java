package com.chicu.aitradebot.strategy.breakout;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "breakout_strategy_settings",
        indexes = @Index(name = "ix_breakout_chat", columnList = "chat_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BreakoutStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // ==========================
    // ✅ ТОЛЬКО ПОЛЯ BREAKOUT
    // ==========================

    /** Сколько свечей берём для диапазона high/low */
    @Builder.Default
    @Column(name = "range_lookback", nullable = false)
    private Integer rangeLookback = 50;

    /** Буфер пробоя (%) над high (чтобы не ловить микропрокол) */
    @Builder.Default
    @Column(name = "breakout_buffer_pct", nullable = false)
    private Double breakoutBufferPct = 0.08;

    /** Не торговать слишком узкий диапазон (%) */
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.25;

    // тех.поля (как у тебя обычно в settings сущностях)
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

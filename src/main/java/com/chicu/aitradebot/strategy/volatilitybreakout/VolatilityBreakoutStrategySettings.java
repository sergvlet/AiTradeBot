package com.chicu.aitradebot.strategy.volatilitybreakout;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "volatility_breakout_strategy_settings",
        indexes = {
                @Index(name = "ix_vb_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VolatilityBreakoutStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /**
     * Окно для оценки волатильности (кол-во тиков/цен).
     * Для твоей WS-модели это норм: 40..200.
     */
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 60;

    /**
     * Порог пробоя в % от "базовой" волатильности.
     * Пример: 1.6 => нужно расширение диапазона в 1.6 раза.
     */
    @Builder.Default
    @Column(name = "breakout_multiplier", nullable = false)
    private Double breakoutMultiplier = 1.6;

    /**
     * Минимальная волатильность (range%) чтобы вообще торговать.
     * Пример: 0.25 => 0.25%
     */
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.25;

    /**
     * Фильтр спреда в % (если есть в лайве/котировках).
     * Если у тебя сейчас нет real spread — оставь 0.
     */
    @Builder.Default
    @Column(name = "max_spread_pct", nullable = false)
    private Double maxSpreadPct = 0.0;

    /**
     * Куда входить при пробое:
     * - true: только LONG (спот)
     * - false: если потом будет фьюч/шорт — можно расширить
     */
    @Builder.Default
    @Column(name = "spot_long_only", nullable = false)
    private boolean spotLongOnly = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    protected void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

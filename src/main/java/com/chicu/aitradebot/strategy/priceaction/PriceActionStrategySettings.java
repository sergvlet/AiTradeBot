package com.chicu.aitradebot.strategy.priceaction;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "price_action_strategy_settings",
        indexes = {
                @Index(name = "ix_price_action_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceActionStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // окно для high/low + оценки структуры
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 120;

    // минимальный диапазон окна (в %), иначе "флэт"
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.35;

    // "пробой" high/low в % от диапазона, чтобы считать breakout структуры
    @Builder.Default
    @Column(name = "breakout_of_range_pct", nullable = false)
    private Double breakoutOfRangePct = 2.0;

    // фильтр: если тень (wick) слишком большая — избегаем входа (в % от range)
    @Builder.Default
    @Column(name = "max_wick_pct_of_range", nullable = false)
    private Double maxWickPctOfRange = 55.0;

    // подтверждение: сколько тиков подряд цена должна удержаться за уровнем
    @Builder.Default
    @Column(name = "confirm_ticks", nullable = false)
    private Integer confirmTicks = 3;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

package com.chicu.aitradebot.strategy.fibonacciretrace;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "fibonacci_retrace_strategy_settings",
        indexes = {
                @Index(name = "ix_fibo_retrace_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FibonacciRetraceStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // окно для поиска swing high/low
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 240;

    // минимальный диапазон, чтобы сетка имела смысл
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.45;

    // основной уровень ретрейса для входа (например 0.618)
    @Builder.Default
    @Column(name = "entry_level", nullable = false)
    private Double entryLevel = 0.618;

    // допуск вокруг уровня в процентах от цены (например 0.10% = 0.10)
    @Builder.Default
    @Column(name = "entry_tolerance_pct", nullable = false)
    private Double entryTolerancePct = 0.10;

    // если цена пробивает swing low ниже на X% — считаем сценарий сломан
    @Builder.Default
    @Column(name = "invalidate_below_low_pct", nullable = false)
    private Double invalidateBelowLowPct = 0.15;

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

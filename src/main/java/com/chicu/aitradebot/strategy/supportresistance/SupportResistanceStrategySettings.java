package com.chicu.aitradebot.strategy.supportresistance;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "support_resistance_strategy_settings",
        indexes = {
                @Index(name = "ix_sr_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportResistanceStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    // сколько тиков (или цен) держим в окне
    @Builder.Default
    @Column(name = "window_size", nullable = false)
    private Integer windowSize = 240;

    // минимальный "диапазон" (high-low)/low * 100, чтобы SR имел смысл
    @Builder.Default
    @Column(name = "min_range_pct", nullable = false)
    private Double minRangePct = 0.35;

    // вход от поддержки: если цена в пределах X% от support -> BUY
    @Builder.Default
    @Column(name = "entry_from_support_pct", nullable = false)
    private Double entryFromSupportPct = 0.15;

    // вход на пробой: если цена выше resistance на X% -> BUY
    @Builder.Default
    @Column(name = "breakout_above_resistance_pct", nullable = false)
    private Double breakoutAboveResistancePct = 0.12;

    @Builder.Default
    @Column(name = "enabled_breakout", nullable = false)
    private boolean enabledBreakout = true;

    @Builder.Default
    @Column(name = "enabled_bounce", nullable = false)
    private boolean enabledBounce = true;

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

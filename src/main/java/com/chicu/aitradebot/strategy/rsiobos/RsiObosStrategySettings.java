package com.chicu.aitradebot.strategy.rsiobos;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "rsi_obos_strategy_settings",
        indexes = {
                @Index(name = "ix_rsi_obos_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RsiObosStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** RSI период */
    @Builder.Default
    @Column(name = "rsi_period", nullable = false)
    private Integer rsiPeriod = 14;

    /** Покупать, когда RSI <= buyBelow */
    @Builder.Default
    @Column(name = "buy_below", nullable = false)
    private Double buyBelow = 30.0;

    /** (опционально) Не входить, если RSI >= blockAbove (перекупленность) */
    @Builder.Default
    @Column(name = "block_above", nullable = false)
    private Double blockAbove = 70.0;

    /** Спот: только LONG */
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

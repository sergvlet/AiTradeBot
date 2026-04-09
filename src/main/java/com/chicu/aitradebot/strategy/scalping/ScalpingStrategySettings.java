package com.chicu.aitradebot.strategy.scalping;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "scalping_strategy_settings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_scalping_settings_chat", columnNames = "chat_id")
        },
        indexes = {
                @Index(name = "ix_scalping_settings_chat", columnList = "chat_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ScalpingStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Builder.Default
    @Column(nullable = false)
    private Integer windowSize = 60;

    /**
     * Минимальный импульс в процентах.
     * Пример: 0.08 = 0.08%
     */
    @Builder.Default
    @Column(nullable = false)
    private Double minImpulsePct = 0.08d;

    /**
     * emaDiff = (emaFast - emaSlow) / price * 100
     */
    @Builder.Default
    @Column(nullable = false)
    private Double emaDiffThreshold = 0.05d;

    @Builder.Default
    @Column(nullable = false)
    private Double volumeRatio = 1.00d;

    @Builder.Default
    @Column(nullable = false)
    private Double spreadLimitPct = 0.35d;

    @Builder.Default
    @Column(nullable = false)
    private Double atrPctRange = 0.90d;

    /**
     * Базовый нижний RSI-фильтр.
     * Должен быть мягким для частых входов.
     */
    @Builder.Default
    @Column(nullable = false)
    private Double rsiFilter = 38.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double riskRewardMin = 1.10d;

    @Builder.Default
    @Column(nullable = false)
    private Double orderVolume = 20.0d;

    /**
     * Для скальпинга TP должен быть маленьким.
     */
    @Builder.Default
    @Column(nullable = false)
    private Double takeProfitPct = 0.28d;

    @Builder.Default
    @Column(nullable = false)
    private Double stopLossPct = 0.18d;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String symbol = "BTCUSDT";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String timeframe = "1m";

    @Builder.Default
    @Column(nullable = false)
    private Integer cachedCandlesLimit = 1000;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = false;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        normalize();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
        normalize();
    }

    public void normalize() {
        if (windowSize == null || windowSize < 6) {
            windowSize = 60;
        } else if (windowSize > 120) {
            windowSize = 120;
        }

        if (minImpulsePct == null || minImpulsePct <= 0) {
            minImpulsePct = 0.08d;
        } else if (minImpulsePct > 5.0d) {
            minImpulsePct = 5.0d;
        }

        if (emaDiffThreshold == null || emaDiffThreshold < 0) {
            emaDiffThreshold = 0.05d;
        } else if (emaDiffThreshold > 5.0d) {
            emaDiffThreshold = 5.0d;
        }

        if (volumeRatio == null || volumeRatio <= 0) {
            volumeRatio = 1.00d;
        } else if (volumeRatio > 10.0d) {
            volumeRatio = 10.0d;
        }

        if (spreadLimitPct == null || spreadLimitPct <= 0) {
            spreadLimitPct = 0.35d;
        } else if (spreadLimitPct > 10.0d) {
            spreadLimitPct = 10.0d;
        }

        if (atrPctRange == null || atrPctRange <= 0) {
            atrPctRange = 0.90d;
        } else if (atrPctRange > 10.0d) {
            atrPctRange = 10.0d;
        }

        if (rsiFilter == null || rsiFilter < 1 || rsiFilter > 99) {
            rsiFilter = 38.0d;
        }

        if (riskRewardMin == null || riskRewardMin <= 0) {
            riskRewardMin = 1.10d;
        } else if (riskRewardMin > 20.0d) {
            riskRewardMin = 20.0d;
        }

        if (orderVolume == null || orderVolume <= 0) {
            orderVolume = 20.0d;
        }

        if (takeProfitPct == null || takeProfitPct <= 0) {
            takeProfitPct = 0.28d;
        } else if (takeProfitPct > 10.0d) {
            takeProfitPct = 10.0d;
        }

        if (stopLossPct == null || stopLossPct <= 0) {
            stopLossPct = 0.18d;
        } else if (stopLossPct > 10.0d) {
            stopLossPct = 10.0d;
        }

        if (symbol == null || symbol.isBlank()) {
            symbol = "BTCUSDT";
        } else {
            symbol = symbol.trim().toUpperCase();
        }

        if (timeframe == null || timeframe.isBlank()) {
            timeframe = "1m";
        } else {
            timeframe = timeframe.trim().toLowerCase();
        }

        if (cachedCandlesLimit == null || cachedCandlesLimit < 50) {
            cachedCandlesLimit = 1000;
        } else if (cachedCandlesLimit > 5000) {
            cachedCandlesLimit = 5000;
        }

        if (active == null) {
            active = false;
        }
    }
}

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

    // -------------------------------------------------
    // legacy / technical compatibility
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Integer windowSize = 36;

    @Builder.Default
    @Column(nullable = false)
    private Double minImpulsePct = 0.035d;

    @Builder.Default
    @Column(nullable = false)
    private Double emaDiffThreshold = 0.018d;

    @Builder.Default
    @Column(nullable = false)
    private Double volumeRatio = 0.90d;

    @Builder.Default
    @Column(nullable = false)
    private Double spreadLimitPct = 0.12d;

    @Builder.Default
    @Column(nullable = false)
    private Double atrPctRange = 0.80d;

    @Builder.Default
    @Column(nullable = false)
    private Double rsiFilter = 38.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double riskRewardMin = 1.05d;

    @Builder.Default
    @Column(nullable = false)
    private Double orderVolume = 20.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double takeProfitPct = 0.28d;

    @Builder.Default
    @Column(nullable = false)
    private Double stopLossPct = 0.16d;

    // -------------------------------------------------
    // regime block
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Boolean regimeAutoEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean allowTrendTrades = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean allowRangeTrades = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean allowBreakoutTrades = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean allowCounterTrendTrades = false;

    @Builder.Default
    @Column(nullable = false)
    private Double chaosBlockThreshold = 70.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double squeezeThreshold = 78.0d;

    // -------------------------------------------------
    // trend pullback block
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Double trendMinScore = 58.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double pullbackMaxDepthPct = 0.90d;

    @Builder.Default
    @Column(nullable = false)
    private Double pullbackEntryBufferPct = 0.30d;

    @Builder.Default
    @Column(nullable = false)
    private Double trendTpPct = 0.28d;

    @Builder.Default
    @Column(nullable = false)
    private Double trendSlPct = 0.16d;

    @Builder.Default
    @Column(nullable = false)
    private Double trendBreakEvenPct = 0.12d;

    @Builder.Default
    @Column(nullable = false)
    private Integer trendMaxHoldSec = 420;

    // -------------------------------------------------
    // range block
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Double rangeMinScore = 52.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double rangeEntryFromLowPct = 0.55d;

    @Builder.Default
    @Column(nullable = false)
    private Double rangeExitToMidPct = 0.50d;

    @Builder.Default
    @Column(nullable = false)
    private Double rangeTpPct = 0.16d;

    @Builder.Default
    @Column(nullable = false)
    private Double rangeSlPct = 0.12d;

    @Builder.Default
    @Column(nullable = false)
    private Integer rangeMaxHoldSec = 180;

    // -------------------------------------------------
    // breakout block
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Double breakoutMinScore = 58.0d;

    @Builder.Default
    @Column(nullable = false)
    private Double breakoutVolumeFactor = 1.10d;

    @Builder.Default
    @Column(nullable = false)
    private Double breakoutTpPct = 0.34d;

    @Builder.Default
    @Column(nullable = false)
    private Double breakoutSlPct = 0.18d;

    // -------------------------------------------------
    // execution block
    // -------------------------------------------------
    @Builder.Default
    @Column(nullable = false)
    private Double maxSpreadPct = 0.15d;

    @Builder.Default
    @Column(nullable = false)
    private Double minAtrPct = 0.02d;

    @Builder.Default
    @Column(nullable = false)
    private Double maxAtrPct = 0.80d;

    @Builder.Default
    @Column(nullable = false)
    private Double minVolumeRatio = 0.60d;

    @Builder.Default
    @Column(nullable = false)
    private Double minRiskReward = 1.05d;

    @Builder.Default
    @Column(nullable = false)
    private Integer cooldownAfterStopSec = 45;

    @Builder.Default
    @Column(nullable = false)
    private Integer cooldownAfterExitSec = 12;

    @Builder.Default
    @Column(nullable = false)
    private Integer maxConsecutiveStops = 3;

    @Builder.Default
    @Column(nullable = false)
    private Integer reentryLockSec = 18;

    @Builder.Default
    @Column(nullable = false)
    private Boolean emergencyChaosExitEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean partialExitEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Double partialExitPct = 0.50d;

    @Builder.Default
    @Column(nullable = false)
    private Double partialExitTriggerPct = 0.18d;

    @Builder.Default
    @Column(nullable = false)
    private Boolean useIntrabarConfirmation = true;

    @Builder.Default
    @Column(nullable = false)
    private Integer microWindowSize = 8;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String symbol = "BTCUSDT";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String timeframe = "1m";

    @Builder.Default
    @Column(nullable = false)
    private Integer cachedCandlesLimit = 1200;

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
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        normalize();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
        normalize();
    }

    public void normalize() {
        windowSize = clampInt(windowSize, 8, 240, 36);
        microWindowSize = clampInt(microWindowSize, 4, 64, 8);
        cachedCandlesLimit = clampInt(cachedCandlesLimit, 80, 5000, 1200);

        minImpulsePct = clampDouble(minImpulsePct, 0.001d, 10.0d, 0.035d);
        emaDiffThreshold = clampDouble(emaDiffThreshold, 0.001d, 10.0d, 0.018d);
        volumeRatio = clampDouble(volumeRatio, 0.10d, 10.0d, 0.90d);
        spreadLimitPct = clampDouble(spreadLimitPct, 0.01d, 10.0d, 0.12d);
        atrPctRange = clampDouble(atrPctRange, 0.01d, 10.0d, 0.80d);
        rsiFilter = clampDouble(rsiFilter, 1.0d, 99.0d, 38.0d);
        riskRewardMin = clampDouble(riskRewardMin, 0.10d, 20.0d, 1.05d);
        orderVolume = clampDouble(orderVolume, 1.0d, 1_000_000.0d, 20.0d);
        takeProfitPct = clampDouble(takeProfitPct, 0.01d, 20.0d, 0.28d);
        stopLossPct = clampDouble(stopLossPct, 0.01d, 20.0d, 0.16d);

        chaosBlockThreshold = clampDouble(chaosBlockThreshold, 1.0d, 100.0d, 70.0d);
        squeezeThreshold = clampDouble(squeezeThreshold, 1.0d, 100.0d, 78.0d);
        trendMinScore = clampDouble(trendMinScore, 1.0d, 100.0d, 58.0d);
        pullbackMaxDepthPct = clampDouble(pullbackMaxDepthPct, 0.05d, 5.0d, 0.90d);
        pullbackEntryBufferPct = clampDouble(pullbackEntryBufferPct, 0.01d, 5.0d, 0.30d);
        trendTpPct = clampDouble(trendTpPct, 0.01d, 20.0d, 0.28d);
        trendSlPct = clampDouble(trendSlPct, 0.01d, 20.0d, 0.16d);
        trendBreakEvenPct = clampDouble(trendBreakEvenPct, 0.01d, 20.0d, 0.12d);
        trendMaxHoldSec = clampInt(trendMaxHoldSec, 10, 86_400, 420);

        rangeMinScore = clampDouble(rangeMinScore, 1.0d, 100.0d, 52.0d);
        rangeEntryFromLowPct = clampDouble(rangeEntryFromLowPct, 0.01d, 5.0d, 0.55d);
        rangeExitToMidPct = clampDouble(rangeExitToMidPct, 0.01d, 5.0d, 0.50d);
        rangeTpPct = clampDouble(rangeTpPct, 0.01d, 20.0d, 0.16d);
        rangeSlPct = clampDouble(rangeSlPct, 0.01d, 20.0d, 0.12d);
        rangeMaxHoldSec = clampInt(rangeMaxHoldSec, 10, 86_400, 180);

        breakoutMinScore = clampDouble(breakoutMinScore, 1.0d, 100.0d, 58.0d);
        breakoutVolumeFactor = clampDouble(breakoutVolumeFactor, 0.10d, 10.0d, 1.10d);
        breakoutTpPct = clampDouble(breakoutTpPct, 0.01d, 20.0d, 0.34d);
        breakoutSlPct = clampDouble(breakoutSlPct, 0.01d, 20.0d, 0.18d);

        maxSpreadPct = clampDouble(maxSpreadPct, 0.01d, 20.0d, 0.15d);
        minAtrPct = clampDouble(minAtrPct, 0.001d, 20.0d, 0.02d);
        maxAtrPct = clampDouble(maxAtrPct, 0.01d, 20.0d, 0.80d);
        minVolumeRatio = clampDouble(minVolumeRatio, 0.10d, 10.0d, 0.60d);
        minRiskReward = clampDouble(minRiskReward, 0.10d, 20.0d, 1.05d);
        cooldownAfterStopSec = clampInt(cooldownAfterStopSec, 0, 86_400, 45);
        cooldownAfterExitSec = clampInt(cooldownAfterExitSec, 0, 86_400, 12);
        maxConsecutiveStops = clampInt(maxConsecutiveStops, 1, 100, 3);
        reentryLockSec = clampInt(reentryLockSec, 0, 86_400, 18);
        partialExitPct = clampDouble(partialExitPct, 0.05d, 0.95d, 0.50d);
        partialExitTriggerPct = clampDouble(partialExitTriggerPct, 0.01d, 20.0d, 0.18d);

        regimeAutoEnabled = bool(regimeAutoEnabled, true);
        allowTrendTrades = bool(allowTrendTrades, true);
        allowRangeTrades = bool(allowRangeTrades, true);
        allowBreakoutTrades = bool(allowBreakoutTrades, true);
        allowCounterTrendTrades = bool(allowCounterTrendTrades, false);
        emergencyChaosExitEnabled = bool(emergencyChaosExitEnabled, true);
        partialExitEnabled = bool(partialExitEnabled, true);
        useIntrabarConfirmation = bool(useIntrabarConfirmation, true);
        active = bool(active, false);

        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        symbol = symbol.trim().toUpperCase();
        if (timeframe == null || timeframe.isBlank()) timeframe = "1m";
        timeframe = timeframe.trim().toLowerCase();
    }

    private static Integer clampInt(Integer value, int min, int max, int fallback) {
        if (value == null) return fallback;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static Double clampDouble(Double value, double min, double max, double fallback) {
        if (value == null) return fallback;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static Boolean bool(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}




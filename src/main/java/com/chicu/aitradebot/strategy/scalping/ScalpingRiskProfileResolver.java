package com.chicu.aitradebot.strategy.scalping;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ScalpingRiskProfileResolver {

    public ScalpingRiskProfile resolve(ScalpingMarketRegimeSnapshot snapshot,
                                       EntryDecision decision,
                                       ScalpingStrategySettings settings) {
        if (decision == null || settings == null) {
            return new ScalpingRiskProfile(scale(0.20d), scale(0.14d), scale(0.10d), 180, scale(1.0d));
        }

        return switch (decision.setupType()) {
            case TREND_PULLBACK -> new ScalpingRiskProfile(
                    scale(firstPositive(settings.getTrendTpPct(), settings.getTakeProfitPct(), 0.28d)),
                    scale(firstPositive(settings.getTrendSlPct(), settings.getStopLossPct(), 0.16d)),
                    scale(firstPositive(settings.getTrendBreakEvenPct(), 0.12d)),
                    settings.getTrendMaxHoldSec() != null ? settings.getTrendMaxHoldSec() : 420,
                    resolveRiskScale(snapshot, decision, 1.00d)
            );
            case RANGE_BOUNCE -> new ScalpingRiskProfile(
                    scale(firstPositive(settings.getRangeTpPct(), 0.16d)),
                    scale(firstPositive(settings.getRangeSlPct(), 0.12d)),
                    scale(0.08d),
                    settings.getRangeMaxHoldSec() != null ? settings.getRangeMaxHoldSec() : 180,
                    resolveRiskScale(snapshot, decision, 0.75d)
            );
            case BREAKOUT_CONTINUATION -> new ScalpingRiskProfile(
                    scale(firstPositive(settings.getBreakoutTpPct(), 0.34d)),
                    scale(firstPositive(settings.getBreakoutSlPct(), 0.18d)),
                    scale(firstPositive(settings.getTrendBreakEvenPct(), 0.14d)),
                    settings.getTrendMaxHoldSec() != null ? Math.min(600, settings.getTrendMaxHoldSec() + 120) : 480,
                    resolveRiskScale(snapshot, decision, 0.90d)
            );
            default -> new ScalpingRiskProfile(scale(0.20d), scale(0.14d), scale(0.10d), 240, scale(1.0d));
        };
    }

    private BigDecimal resolveRiskScale(ScalpingMarketRegimeSnapshot snapshot,
                                        EntryDecision decision,
                                        double base) {
        double scale = base;
        if (snapshot != null) {
            scale += Math.max(0.0d, snapshot.trendScore().doubleValue() - 60.0d) * 0.005d;
            scale -= Math.max(0.0d, snapshot.chaosScore().doubleValue() - 25.0d) * 0.01d;
        }
        if (decision != null && decision.score() != null) {
            scale += Math.max(0.0d, decision.score().doubleValue() - 50.0d) * 0.002d;
        }
        return scale(Math.max(0.35d, Math.min(1.35d, scale)));
    }

    private static double firstPositive(Double primary, Double secondary, double fallback) {
        if (primary != null && primary > 0) return primary;
        if (secondary != null && secondary > 0) return secondary;
        return fallback;
    }

    private static double firstPositive(Double primary, double fallback) {
        if (primary != null && primary > 0) return primary;
        return fallback;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }
}



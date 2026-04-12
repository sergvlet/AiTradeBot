package com.chicu.aitradebot.strategy.scalping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Deque;

@Slf4j
@Component
public class ScalpingMarketRegimeDetector {

    public ScalpingMarketRegimeSnapshot detect(Deque<ScalpingFeatureCalculator.CandleInput> candles,
                                               ScalpingFeatureSnapshot features,
                                               ScalpingStrategySettings settings,
                                               Instant now) {
        if (features == null || settings == null || candles == null || candles.size() < 6) {
            return snapshot(ScalpingMarketRegime.NO_TRADE, features, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, now, "мало данных для классификации");
        }

        BigDecimal trendUpScore = classifyTrendUp(features, settings);
        BigDecimal trendDownScore = classifyTrendDown(features, settings);
        BigDecimal rangeScore = classifyRange(features, settings);
        BigDecimal chaosScore = classifyChaos(features, settings);
        BigDecimal volatilityScore = classifyVolatility(features);
        BigDecimal squeezeScore = features.squeezeScore() != null ? features.squeezeScore() : BigDecimal.ZERO;
        BigDecimal microTrendScore = classifyMicroTrend(features);

        BigDecimal maxSpread = bd(settings.getMaxSpreadPct(), settings.getSpreadLimitPct(), 0.12d);
        BigDecimal minAtr = bd(settings.getMinAtrPct(), 0.03d);

        if (features.spreadPct() != null && features.spreadPct().compareTo(maxSpread.multiply(new BigDecimal("1.80"))) > 0) {
            return snapshot(ScalpingMarketRegime.NO_TRADE, features, trendUpScore.max(trendDownScore), rangeScore, chaosScore,
                    volatilityScore, microTrendScore, squeezeScore, now, "спред слишком широкий для входа");
        }

        if (features.atrPct() != null && features.atrPct().compareTo(minAtr.multiply(new BigDecimal("0.50"))) < 0) {
            return snapshot(ScalpingMarketRegime.NO_TRADE, features, trendUpScore.max(trendDownScore), rangeScore, chaosScore,
                    volatilityScore, microTrendScore, squeezeScore, now, "волатильность слишком низкая");
        }

        BigDecimal chaosThreshold = scale(Math.max(70.0d, bd(settings.getChaosBlockThreshold(), 62.0d).doubleValue()));
        BigDecimal squeezeThreshold = scale(Math.max(78.0d, bd(settings.getSqueezeThreshold(), 72.0d).doubleValue()));
        BigDecimal trendMinScore = bd(settings.getTrendMinScore(), 58.0d);
        BigDecimal rangeMinScore = bd(settings.getRangeMinScore(), 52.0d);

        boolean strongTrendUp = trendUpScore.compareTo(trendMinScore) >= 0
                && trendUpScore.subtract(trendDownScore).compareTo(scale(4.0d)) >= 0;
        boolean strongTrendDown = trendDownScore.compareTo(trendMinScore) >= 0
                && trendDownScore.subtract(trendUpScore).compareTo(scale(4.0d)) >= 0;
        boolean strongRange = rangeScore.compareTo(rangeMinScore) >= 0;
        boolean squeezeHigh = squeezeScore.compareTo(squeezeThreshold) >= 0;
        boolean squeezeBreakoutReady = squeezeHigh
                && trendUpScore.compareTo(trendMinScore.add(scale(4.0d))) >= 0
                && gt(features.emaFast(), features.emaSlow())
                && safe(features.breakoutPressure()) >= 1.10d
                && safe(features.priceFromWindowHigh()) <= 0.40d;
        boolean chaosHardBlock = chaosScore.compareTo(chaosThreshold.add(scale(18.0d))) >= 0
                && !strongTrendUp
                && !strongRange;
        boolean chaosSoftBlock = chaosScore.compareTo(chaosThreshold) >= 0
                && !strongTrendUp
                && !strongRange
                && !squeezeBreakoutReady;

        ScalpingMarketRegime regime;
        String reason;

        if (chaosHardBlock) {
            regime = ScalpingMarketRegime.CHAOS;
            reason = "хаотичная структура рынка";
        } else if (strongTrendUp) {
            regime = ScalpingMarketRegime.TREND_UP;
            reason = squeezeBreakoutReady
                    ? "сжатие готово к пробою вверх"
                    : "восходящий тренд подтверждён";
        } else if (strongTrendDown && !strongRange) {
            regime = ScalpingMarketRegime.TREND_DOWN;
            reason = "нисходящий тренд, для спота вход запрещён";
        } else if (strongRange && (!squeezeHigh || safe(features.breakoutPressure()) < 1.40d)) {
            regime = ScalpingMarketRegime.RANGE;
            reason = "боковик с рабочими границами";
        } else if (squeezeBreakoutReady) {
            regime = ScalpingMarketRegime.TREND_UP;
            reason = "сжатие с бычьим продолжением";
        } else if (squeezeHigh && !strongRange) {
            regime = ScalpingMarketRegime.SQUEEZE;
            reason = "рынок сжался, нужен подтверждённый выход";
        } else if (strongRange) {
            regime = ScalpingMarketRegime.RANGE;
            reason = "боковик с рабочими границами";
        } else if (chaosSoftBlock) {
            regime = ScalpingMarketRegime.CHAOS;
            reason = "хаотичная структура рынка";
        } else {
            regime = ScalpingMarketRegime.NO_TRADE;
            reason = "режим не подтверждён";
        }

        return snapshot(regime,
                features,
                trendUpScore.max(trendDownScore),
                rangeScore,
                chaosScore,
                volatilityScore,
                microTrendScore,
                squeezeScore,
                now,
                reason);
    }

    private BigDecimal classifyTrendUp(ScalpingFeatureSnapshot f, ScalpingStrategySettings s) {
        double score = 0.0d;
        if (gt(f.emaFast(), f.emaSlow())) score += 24.0d;
        if (gt(f.emaSlopeFast(), BigDecimal.ZERO)) score += 14.0d;
        if (gte(f.emaSlopeSlow(), BigDecimal.ZERO)) score += 10.0d;
        score += Math.max(0.0d, 8.0d - safe(f.priceFromWindowHigh())) * 2.0d;
        score += Math.max(0.0d, safe(f.volumeRatio()) - 0.9d) * 12.0d;
        score += Math.max(0.0d, safe(f.adxLike()) - 18.0d) * 0.70d;
        score += Math.max(0.0d, 65.0d - safe(f.rsi())) * 0.35d;
        score += Math.max(0.0d, safe(f.breakoutPressure())) * 0.90d;
        score += Math.max(0.0d, safe(f.bullishStructureScore())) * 1.80d;
        score -= Math.max(0.0d, safe(f.spreadPct()) - bd(s.getMaxSpreadPct(), s.getSpreadLimitPct(), 0.12d).doubleValue()) * 50.0d;
        return scale(clamp100(score));
    }

    private BigDecimal classifyTrendDown(ScalpingFeatureSnapshot f, ScalpingStrategySettings s) {
        double score = 0.0d;
        if (lt(f.emaFast(), f.emaSlow())) score += 24.0d;
        if (lt(f.emaSlopeFast(), BigDecimal.ZERO)) score += 14.0d;
        if (lte(f.emaSlopeSlow(), BigDecimal.ZERO)) score += 10.0d;
        score += Math.max(0.0d, 8.0d - safe(f.priceFromWindowLow())) * 1.8d;
        score += Math.max(0.0d, safe(f.adxLike()) - 18.0d) * 0.70d;
        score += Math.max(0.0d, 52.0d - safe(f.rsi())) * 0.80d;
        score += Math.max(0.0d, -safe(f.priceChangePct())) * 5.0d;
        score -= Math.max(0.0d, safe(f.spreadPct()) - bd(s.getMaxSpreadPct(), s.getSpreadLimitPct(), 0.12d).doubleValue()) * 30.0d;
        return scale(clamp100(score));
    }

    private BigDecimal classifyRange(ScalpingFeatureSnapshot f, ScalpingStrategySettings s) {
        double score = 0.0d;
        score += Math.max(0.0d, 0.18d - Math.abs(safe(f.emaDiff()))) * 120.0d;
        score += Math.max(0.0d, 0.12d - Math.abs(safe(f.emaSlopeFast()))) * 140.0d;
        score += Math.max(0.0d, 0.08d - Math.abs(safe(f.emaSlopeSlow()))) * 160.0d;
        score += Math.max(0.0d, 0.85d - Math.abs(safe(f.priceFromWindowLow()) - safe(f.priceFromWindowHigh()))) * 18.0d;
        score += Math.max(0.0d, 0.80d - Math.abs(safe(f.rsi()) - 50.0d) / 50.0d) * 15.0d;
        score -= Math.max(0.0d, safe(f.breakoutPressure()) - 1.5d) * 18.0d;
        return scale(clamp100(score));
    }

    private BigDecimal classifyChaos(ScalpingFeatureSnapshot f, ScalpingStrategySettings s) {
        double score = 0.0d;
        score += Math.max(0.0d, safe(f.atrPct()) - bd(s.getMaxAtrPct(), s.getAtrPctRange(), 0.80d).doubleValue()) * 34.0d;
        score += Math.max(0.0d, safe(f.spreadPct()) - bd(s.getMaxSpreadPct(), s.getSpreadLimitPct(), 0.12d).doubleValue()) * 55.0d;
        score += Math.max(0.0d, safe(f.wickBodyRatio()) - 2.60d) * 12.0d;
        score += Math.max(0.0d, 0.28d - safe(f.candleEfficiency())) * 38.0d;
        score += Math.max(0.0d, 40.0d - safe(f.adxLike())) * 0.22d;
        score -= Math.max(0.0d, safe(f.bullishStructureScore()) - 4.0d) * 1.20d;
        score -= Math.max(0.0d, safe(f.breakoutPressure()) - 0.80d) * 1.40d;
        score -= Math.max(0.0d, safe(f.volumeRatio()) - 0.90d) * 10.0d;
        score -= Math.max(0.0d, safe(f.adxLike()) - 22.0d) * 0.45d;
        return scale(clamp100(score));
    }

    private BigDecimal classifyVolatility(ScalpingFeatureSnapshot f) {
        double score = Math.max(0.0d, safe(f.atrPct()) * 35.0d + safe(f.windowRange()) * 0.35d + safe(f.spreadPct()) * 28.0d);
        return scale(clamp100(score));
    }

    private BigDecimal classifyMicroTrend(ScalpingFeatureSnapshot f) {
        double score = 0.0d;
        score += Math.max(0.0d, safe(f.priceChangePct())) * 6.0d;
        score += Math.max(0.0d, safe(f.emaSlopeFast())) * 30.0d;
        score += Math.max(0.0d, safe(f.breakoutPressure())) * 2.0d;
        return scale(clamp100(score));
    }

    private ScalpingMarketRegimeSnapshot snapshot(ScalpingMarketRegime regime,
                                                  ScalpingFeatureSnapshot f,
                                                  BigDecimal trendScore,
                                                  BigDecimal rangeScore,
                                                  BigDecimal chaosScore,
                                                  BigDecimal volatilityScore,
                                                  BigDecimal microTrendScore,
                                                  BigDecimal squeezeScore,
                                                  Instant now,
                                                  String reason) {
        BigDecimal emaSpreadPct = BigDecimal.ZERO;
        if (f != null && f.emaFast() != null && f.emaSlow() != null && f.emaSlow().signum() > 0) {
            emaSpreadPct = f.emaFast().subtract(f.emaSlow())
                    .divide(f.emaSlow(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        return new ScalpingMarketRegimeSnapshot(
                regime,
                nz(trendScore),
                nz(rangeScore),
                nz(chaosScore),
                nz(volatilityScore),
                nz(microTrendScore),
                f != null ? nz(f.emaFast()) : BigDecimal.ZERO,
                f != null ? nz(f.emaSlow()) : BigDecimal.ZERO,
                emaSpreadPct,
                f != null ? nz(f.emaSlopeFast()) : BigDecimal.ZERO,
                f != null ? nz(f.emaSlopeSlow()) : BigDecimal.ZERO,
                f != null ? nz(f.atrPct()) : BigDecimal.ZERO,
                f != null ? nz(f.rsi()) : BigDecimal.ZERO,
                f != null ? nz(f.adxLike()) : BigDecimal.ZERO,
                f != null ? nz(f.windowRange()) : BigDecimal.ZERO,
                f != null ? nz(f.distanceFromLowPct()) : BigDecimal.ZERO,
                f != null ? nz(f.distanceFromHighPct()) : BigDecimal.ZERO,
                f != null ? nz(f.volumeRatio()) : BigDecimal.ZERO,
                f != null ? nz(f.spreadPct()) : BigDecimal.ZERO,
                f != null ? nz(f.vwapDistancePct()) : BigDecimal.ZERO,
                nz(squeezeScore),
                now,
                reason
        );
    }

    private static BigDecimal bd(Double primary, Double secondary, double fallback) {
        if (primary != null && primary > 0) return scale(primary);
        if (secondary != null && secondary > 0) return scale(secondary);
        return scale(fallback);
    }

    private static BigDecimal bd(Double primary, double fallback) {
        if (primary != null && primary > 0) return scale(primary);
        return scale(fallback);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : v.setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean gt(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) > 0; }
    private static boolean gte(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) >= 0; }
    private static boolean lt(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) < 0; }
    private static boolean lte(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) <= 0; }
    private static double safe(BigDecimal v) { return v == null ? 0.0d : v.doubleValue(); }
    private static double clamp100(double value) { return Math.max(0.0d, Math.min(100.0d, value)); }
    private static BigDecimal scale(double value) { return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP); }
}

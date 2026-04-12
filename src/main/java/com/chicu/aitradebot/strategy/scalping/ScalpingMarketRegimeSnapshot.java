package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record ScalpingMarketRegimeSnapshot(
        ScalpingMarketRegime regime,
        BigDecimal trendScore,
        BigDecimal rangeScore,
        BigDecimal chaosScore,
        BigDecimal volatilityScore,
        BigDecimal microTrendScore,
        BigDecimal emaFast,
        BigDecimal emaSlow,
        BigDecimal emaSpreadPct,
        BigDecimal emaSlopeFast,
        BigDecimal emaSlopeSlow,
        BigDecimal atrPct,
        BigDecimal rsi,
        BigDecimal adxLike,
        BigDecimal windowRangePct,
        BigDecimal distanceFromLowPct,
        BigDecimal distanceFromHighPct,
        BigDecimal volumeRatio,
        BigDecimal spreadPct,
        BigDecimal vwapDistancePct,
        BigDecimal squeezeScore,
        Instant timestamp,
        String reason
) {

    public boolean isSpotTradable() {
        return regime == ScalpingMarketRegime.TREND_UP || regime == ScalpingMarketRegime.RANGE;
    }

    public String shortLabel() {
        return regime + " | trend=" + fmt(trendScore)
                + " range=" + fmt(rangeScore)
                + " chaos=" + fmt(chaosScore)
                + " squeeze=" + fmt(squeezeScore)
                + (reason != null && !reason.isBlank() ? " | " + reason : "");
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "null";
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}

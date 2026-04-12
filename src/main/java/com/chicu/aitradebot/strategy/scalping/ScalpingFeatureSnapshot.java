package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ScalpingFeatureSnapshot(
        Instant timestamp,
        BigDecimal lastPrice,
        BigDecimal windowLow,
        BigDecimal windowHigh,
        BigDecimal priceChangePct,
        BigDecimal emaDiff,
        BigDecimal volumeToAverage,
        BigDecimal spreadPct,
        BigDecimal atrPct,
        BigDecimal windowRange,
        BigDecimal priceFromWindowLow,
        BigDecimal priceFromWindowHigh,
        BigDecimal rsi,
        BigDecimal riskRewardRatio,
        BigDecimal score,
        boolean volumeProxy,
        BigDecimal emaFast,
        BigDecimal emaSlow,
        BigDecimal emaSlopeFast,
        BigDecimal emaSlopeSlow,
        BigDecimal adxLike,
        BigDecimal volumeRatio,
        BigDecimal vwapDistancePct,
        BigDecimal wickBodyRatio,
        BigDecimal candleEfficiency,
        BigDecimal microPullbackDepthPct,
        BigDecimal squeezeScore,
        BigDecimal distanceFromLowPct,
        BigDecimal distanceFromHighPct,
        BigDecimal breakoutPressure,
        BigDecimal bullishStructureScore
) {

    public Map<String, Object> toMlFeatures() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("priceChangePct", asDouble(priceChangePct));
        features.put("emaDiff", asDouble(emaDiff));
        features.put("volumeToAverage", asDouble(volumeToAverage));
        features.put("spreadPct", asDouble(spreadPct));
        features.put("ATR_pct", asDouble(atrPct));
        features.put("windowRange", asDouble(windowRange));
        features.put("priceFromWindowLow", asDouble(priceFromWindowLow));
        features.put("priceFromWindowHigh", asDouble(priceFromWindowHigh));
        features.put("RSI", asDouble(rsi));
        features.put("riskRewardRatio", asDouble(riskRewardRatio));
        features.put("emaFast", asDouble(emaFast));
        features.put("emaSlow", asDouble(emaSlow));
        features.put("emaSlopeFast", asDouble(emaSlopeFast));
        features.put("emaSlopeSlow", asDouble(emaSlopeSlow));
        features.put("adxLike", asDouble(adxLike));
        features.put("volumeRatio", asDouble(volumeRatio));
        features.put("vwapDistancePct", asDouble(vwapDistancePct));
        features.put("wickBodyRatio", asDouble(wickBodyRatio));
        features.put("candleEfficiency", asDouble(candleEfficiency));
        features.put("microPullbackDepthPct", asDouble(microPullbackDepthPct));
        features.put("squeezeScore", asDouble(squeezeScore));
        features.put("distanceFromLowPct", asDouble(distanceFromLowPct));
        features.put("distanceFromHighPct", asDouble(distanceFromHighPct));
        features.put("breakoutPressure", asDouble(breakoutPressure));
        features.put("bullishStructureScore", asDouble(bullishStructureScore));
        return features;
    }

    private static Double asDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}



package com.chicu.aitradebot.strategy.scalping;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BreakoutContinuationEntryEngine {

    public EntryDecision evaluate(ScalpingMarketRegime previousRegime,
                                  ScalpingMarketRegimeSnapshot snapshot,
                                  ScalpingFeatureSnapshot features,
                                  ScalpingStrategySettings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot == null || features == null || settings == null) {
            return EntryDecision.block(ScalpingMarketRegime.NO_TRADE, ScalpingSetupType.BREAKOUT_CONTINUATION, "нет данных для breakout", map);
        }

        map.putAll(features.toMlFeatures());
        map.put("regime", snapshot.regime().name());
        map.put("previousRegime", previousRegime != null ? previousRegime.name() : null);

        if (!Boolean.TRUE.equals(settings.getAllowBreakoutTrades())) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "breakout-сделки отключены в настройках", map);
        }

        double squeezeThreshold = Math.max(78.0d, settings.getSqueezeThreshold() != null ? settings.getSqueezeThreshold() : 72.0d);
        double trendMinScore = settings.getTrendMinScore() != null ? settings.getTrendMinScore() : 58.0d;
        double minScore = settings.getBreakoutMinScore() != null ? settings.getBreakoutMinScore() : 61.0d;
        double minVolumeFactor = settings.getBreakoutVolumeFactor() != null ? settings.getBreakoutVolumeFactor() : 1.20d;

        boolean hadCompression = previousRegime == ScalpingMarketRegime.SQUEEZE
                || snapshot.regime() == ScalpingMarketRegime.SQUEEZE
                || safe(snapshot.squeezeScore()) >= Math.max(68.0d, squeezeThreshold - 4.0d)
                || (previousRegime == ScalpingMarketRegime.RANGE && safe(snapshot.rangeScore()) >= (settings.getRangeMinScore() != null ? settings.getRangeMinScore() : 52.0d));
        if (!hadCompression) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "до этого не было сжатия или накопления", map);
        }

        boolean bullishDirection = snapshot.regime() == ScalpingMarketRegime.TREND_UP
                || (snapshot.regime() == ScalpingMarketRegime.SQUEEZE
                && safe(snapshot.trendScore()) >= (trendMinScore + 4.0d)
                && gt(features.emaFast(), features.emaSlow())
                && safe(features.breakoutPressure()) >= 1.10d)
                || (snapshot.regime() == ScalpingMarketRegime.CHAOS
                && safe(snapshot.trendScore()) >= (trendMinScore + 8.0d)
                && safe(snapshot.chaosScore()) <= ((settings.getChaosBlockThreshold() != null ? settings.getChaosBlockThreshold() : 62.0d) + 12.0d)
                && gt(features.emaFast(), features.emaSlow())
                && safe(features.breakoutPressure()) >= 1.40d);
        if (!bullishDirection) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "нет направленного импульса вверх после сжатия", map);
        }

        if (safe(snapshot.trendScore()) < minScore) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "breakout score слишком низкий", map);
        }
        if (safe(features.volumeRatio()) < Math.max(0.90d, minVolumeFactor - 0.10d)) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "объём не подтверждает пробой", map);
        }
        if (safe(features.priceFromWindowHigh()) > 0.35d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "цена не закрепилась у верхней границы", map);
        }
        if (safe(features.breakoutPressure()) < 1.10d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "давление пробоя ещё слабое", map);
        }
        if (safe(features.wickBodyRatio()) > 3.80d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, "свеча пробоя слишком рваная", map);
        }

        double score = safe(snapshot.trendScore()) * 0.55d;
        score += safe(features.volumeRatio()) * 16.0d;
        score += Math.max(0.0d, 0.30d - safe(features.priceFromWindowHigh())) * 28.0d;
        score += Math.max(0.0d, safe(features.breakoutPressure())) * 2.2d;
        score += Math.max(0.0d, safe(features.bullishStructureScore())) * 1.4d;
        score += Math.max(0.0d, 3.5d - safe(features.wickBodyRatio())) * 4.5d;

        return EntryDecision.allow(snapshot.regime(), ScalpingSetupType.BREAKOUT_CONTINUATION, BigDecimal.valueOf(score),
                "сжатие отработано, пробой подтверждён объёмом", map);
    }

    private static boolean gt(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) > 0;
    }

    private static double safe(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }
}

package com.chicu.aitradebot.strategy.scalping;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TrendPullbackEntryEngine {

    public EntryDecision evaluate(ScalpingMarketRegimeSnapshot snapshot,
                                  ScalpingFeatureSnapshot features,
                                  ScalpingStrategySettings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot == null || features == null || settings == null) {
            return EntryDecision.block(ScalpingMarketRegime.NO_TRADE, ScalpingSetupType.TREND_PULLBACK, "нет данных для trend pullback", map);
        }

        map.putAll(features.toMlFeatures());
        map.put("regime", snapshot.regime().name());

        if (!Boolean.TRUE.equals(settings.getAllowTrendTrades())) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "trend-сделки отключены в настройках", map);
        }
        if (snapshot.regime() != ScalpingMarketRegime.TREND_UP) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "рынок не в TREND_UP", map);
        }

        double minScore = settings.getTrendMinScore() != null ? settings.getTrendMinScore() : 58.0d;
        double pullbackMaxDepth = settings.getPullbackMaxDepthPct() != null ? settings.getPullbackMaxDepthPct() : 0.90d;
        double entryBuffer = settings.getPullbackEntryBufferPct() != null ? settings.getPullbackEntryBufferPct() : 0.30d;
        double pullbackDistanceLimit = Math.max(0.75d, entryBuffer + 0.55d);
        double trendBoost = Math.max(0.0d, snapshot.trendScore().doubleValue() - minScore);
        if (trendBoost >= 8.0d) {
            pullbackDistanceLimit = Math.min(1.30d, pullbackDistanceLimit + 0.12d);
        }
        if (features.breakoutPressure().doubleValue() >= 2.20d) {
            pullbackDistanceLimit = Math.min(1.35d, pullbackDistanceLimit + 0.08d);
        }
        if (features.volumeRatio().doubleValue() >= 1.10d) {
            pullbackDistanceLimit = Math.min(1.40d, pullbackDistanceLimit + 0.05d);
        }

        double score = 0.0d;
        score += snapshot.trendScore().doubleValue() * 0.65d;
        score += Math.max(0.0d, 2.0d - Math.abs(features.microPullbackDepthPct().doubleValue() - 0.35d)) * 12.0d;
        score += Math.max(0.0d, 0.55d - features.priceFromWindowHigh().doubleValue()) * 18.0d;
        score += Math.max(0.0d, features.bullishStructureScore().doubleValue()) * 3.2d;
        score += Math.max(0.0d, features.breakoutPressure().doubleValue()) * 1.4d;
        score += Math.max(0.0d, 68.0d - features.rsi().doubleValue()) * 0.20d;

        if (snapshot.trendScore().doubleValue() < minScore) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "trend score слишком низкий", map);
        }
        if (features.microPullbackDepthPct().doubleValue() > pullbackMaxDepth) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "откат слишком глубокий", map);
        }
        if (features.priceFromWindowHigh().doubleValue() > pullbackDistanceLimit) {
            map.put("pullbackDistanceLimit", pullbackDistanceLimit);
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "вход ещё слишком далеко от точки возврата", map);
        }
        if (features.rsi().doubleValue() >= 78.0d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "RSI перегрет, входим невыгодно", map);
        }
        if (features.emaFast() != null && features.lastPrice() != null && features.lastPrice().compareTo(features.emaSlow()) < 0) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, "цена ушла ниже базовой средней", map);
        }

        return EntryDecision.allow(snapshot.regime(), ScalpingSetupType.TREND_PULLBACK, BigDecimal.valueOf(score),
                "откат в тренде завершён, можно искать продолжение", map);
    }
}



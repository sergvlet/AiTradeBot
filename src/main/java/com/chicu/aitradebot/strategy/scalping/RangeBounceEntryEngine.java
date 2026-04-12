package com.chicu.aitradebot.strategy.scalping;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RangeBounceEntryEngine {

    public EntryDecision evaluate(ScalpingMarketRegimeSnapshot snapshot,
                                  ScalpingFeatureSnapshot features,
                                  ScalpingStrategySettings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot == null || features == null || settings == null) {
            return EntryDecision.block(ScalpingMarketRegime.NO_TRADE, ScalpingSetupType.RANGE_BOUNCE, "нет данных для боковика", map);
        }

        map.putAll(features.toMlFeatures());
        map.put("regime", snapshot.regime().name());

        if (!Boolean.TRUE.equals(settings.getAllowRangeTrades())) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "range-сделки отключены в настройках", map);
        }
        if (snapshot.regime() != ScalpingMarketRegime.RANGE) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "рынок не в RANGE", map);
        }

        double minScore = settings.getRangeMinScore() != null ? settings.getRangeMinScore() : 52.0d;
        double entryFromLow = settings.getRangeEntryFromLowPct() != null ? settings.getRangeEntryFromLowPct() : 0.55d;

        if (snapshot.rangeScore().doubleValue() < minScore) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "range score слишком низкий", map);
        }
        if (features.priceFromWindowLow().doubleValue() > entryFromLow) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "цена ещё не у нижней границы диапазона", map);
        }
        if (features.rsi().doubleValue() > 58.0d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "RSI слишком высокий для range bounce", map);
        }
        if (features.breakoutPressure().doubleValue() > 3.20d) {
            return EntryDecision.block(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, "похоже на пробой, а не на отскок", map);
        }

        double score = snapshot.rangeScore().doubleValue() * 0.70d;
        score += Math.max(0.0d, 0.70d - features.priceFromWindowLow().doubleValue()) * 22.0d;
        score += Math.max(0.0d, 55.0d - features.rsi().doubleValue()) * 0.35d;
        score += Math.max(0.0d, 0.25d - Math.abs(features.emaDiff().doubleValue())) * 60.0d;

        return EntryDecision.allow(snapshot.regime(), ScalpingSetupType.RANGE_BOUNCE, BigDecimal.valueOf(score),
                "отскок от нижней границы диапазона подтверждён", map);
    }
}



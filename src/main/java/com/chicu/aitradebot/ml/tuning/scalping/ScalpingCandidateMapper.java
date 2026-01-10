package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ScalpingCandidateMapper {

    /**
     * Канонические ключи = ml_tuning_space.param_name
     */
    public Map<String, Object> toParamMap(ScalpingStrategySettings scalping, StrategySettings common) {

        Map<String, Object> m = new LinkedHashMap<>();

        // --- SCALPING (локальные параметры стратегии)
        if (scalping != null) {
            m.put("windowSize", scalping.getWindowSize());

            // ✅ приводим к BigDecimal стабильно (и для BigDecimal, и для Double/Integer)
            m.put("priceChangeThreshold", toBd(scalping.getPriceChangeThreshold()));
            m.put("spreadThreshold", toBd(scalping.getSpreadThreshold()));
        }

        // --- COMMON (unified StrategySettings)
        // В unified больше нет TP/SL: они живут в таблице конкретной стратегии.
        // Поэтому здесь держим только реальные общие лимиты/флаги.
        if (common != null) {
            m.put("riskPerTradePct", common.getRiskPerTradePct());
            m.put("dailyLossLimitPct", common.getDailyLossLimitPct());
            m.put("reinvestProfit", common.isReinvestProfit());

            // общий бюджет-лимит (если используешь в симуляторе/тюнинге)
            m.put("maxExposureUsd", common.getMaxExposureUsd());
            m.put("maxExposurePct", common.getMaxExposurePct());
        }

        return m;
    }

    /**
     * ✅ Универсальная конвертация Number -> BigDecimal
     * (работает и если поле уже BigDecimal, и если оно Double)
     */
    private static BigDecimal toBd(Number v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        return BigDecimal.valueOf(v.doubleValue());
    }
}

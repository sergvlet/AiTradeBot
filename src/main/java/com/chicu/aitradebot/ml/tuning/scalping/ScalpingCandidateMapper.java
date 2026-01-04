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

            // Double -> BigDecimal (чтобы Guard/score работали стабильно)
            m.put("priceChangeThreshold", toBd(scalping.getPriceChangeThreshold()));
            m.put("spreadThreshold", toBd(scalping.getSpreadThreshold()));
        }

        // --- COMMON (общие настройки стратегии)
        if (common != null) {
            m.put("takeProfitPct", common.getTakeProfitPct());
            m.put("stopLossPct", common.getStopLossPct());

            // это не "orderVolume", а риск. Его можно позже тюнить отдельным параметром.
            m.put("riskPerTradePct", common.getRiskPerTradePct());
            m.put("dailyLossLimitPct", common.getDailyLossLimitPct());
        }

        return m;
    }

    private static BigDecimal toBd(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }
}

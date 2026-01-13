// src/main/java/com/chicu/aitradebot/strategy/smartfusion/DefaultSmartFusionMlService.java
package com.chicu.aitradebot.strategy.smartfusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Заглушка для старта контекста.
 * Позже заменишь на реальный ML (Python/XGBoost/registry + AutoTuner).
 */
@Slf4j
@Service
public class DefaultSmartFusionMlService implements SmartFusionMlService {

    /**
     * Старый стиль: отдаём полный скоринг (pb/ps/conf/modelKey).
     * Оставляем, чтобы не ломать существующий код.
     */
    public SmartFusionMlScore score(Long chatId,
                                    String symbol,
                                    String timeframe,
                                    SmartFusionFeatures features) {

        // аккуратная "почти нейтральная" заглушка
        double pb = ThreadLocalRandom.current().nextDouble(0.45, 0.55);
        double ps = ThreadLocalRandom.current().nextDouble(0.45, 0.55);

        double conf = Math.min(1.0, Math.max(0.0, Math.abs(pb - 0.5) * 2.0));

        SmartFusionMlScore out = new SmartFusionMlScore(
                BigDecimal.valueOf(conf),
                BigDecimal.valueOf(pb),
                BigDecimal.valueOf(ps),
                "smartfusion_stub"
        );

        if (log.isDebugEnabled()) {
            log.debug("[SmartFusionML][STUB] chatId={} sym={} tf={} pb={} ps={} conf={}",
                    chatId, symbol, timeframe,
                    out.getProbBuy(), out.getProbSell(), out.getConfidence());
        }

        return out;
    }

    /**
     * Новый контракт под SmartFusionStrategyV4: вернуть уверенность BUY [0..1].
     * Просто маппим pb из score().
     */
    @Override
    public double predictBuyConfidence(Long chatId,
                                       String symbol,
                                       String timeframe,
                                       String modelKey,
                                       SmartFusionFeatures features) {
        SmartFusionMlScore sc = score(chatId, symbol, timeframe, features);
        return sc != null && sc.getProbBuy() != null ? clamp01(sc.getProbBuy()) : 0.0;
    }

    @Override
    public double predictSellConfidence(Long chatId,
                                        String symbol,
                                        String timeframe,
                                        String modelKey,
                                        SmartFusionFeatures features) {
        SmartFusionMlScore sc = score(chatId, symbol, timeframe, features);
        return sc != null && sc.getProbSell() != null ? clamp01(sc.getProbSell()) : 0.0;
    }

    private static double clamp01(BigDecimal v) {
        if (v == null) return 0.0;
        double d = v.doubleValue();
        if (!Double.isFinite(d)) return 0.0;
        if (d < 0.0) return 0.0;
        if (d > 1.0) return 1.0;
        return d;
    }
}

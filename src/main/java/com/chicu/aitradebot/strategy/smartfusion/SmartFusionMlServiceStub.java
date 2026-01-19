// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionMlServiceStub.java
package com.chicu.aitradebot.strategy.smartfusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Заглушка ML.
 * Чтобы приложение СТАРТОВАЛО, пока не подключён реальный Python/XGBoost.
 */
@Slf4j
@Service
@Primary
public class SmartFusionMlServiceStub implements SmartFusionMlService {

    @Override
    public double predictBuyConfidence(Long chatId,
                                      String symbol,
                                      String timeframe,
                                      String modelKey,
                                      SmartFusionFeatures features) {

        // ✅ стабильная заглушка: "нейтрально"
        // Можно позже заменить на реальный вызов Python.
        double out = 0.50;

        // лёгкий трейс (не спамим)
        if (log.isDebugEnabled()) {
            log.debug("[SmartFusionML][STUB] chatId={} sym={} tf={} modelKey={} -> buyConf={}",
                    chatId, symbol, timeframe, modelKey, out);
        }
        return out;
    }

    @Override
    public double predictSellConfidence(Long chatId,
                                       String symbol,
                                       String timeframe,
                                       String modelKey,
                                       SmartFusionFeatures features) {
        return 0.50;
    }
}

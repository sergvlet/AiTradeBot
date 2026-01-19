// src/main/java/com/chicu/aitradebot/strategy/ai/MlSignalInferenceService.java
package com.chicu.aitradebot.strategy.ml;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Тонкий порт, чтобы стратегия не знала "как" предсказывать.
 * Реализацию можешь сделать через python, java, http, что угодно.
 */
public interface MlSignalInferenceService {

    record Prediction(
            String label,          // "BUY" | "SELL" | "HOLD"
            double confidence,     // 0..1
            String reason
    ) {}

    Prediction predict(
            long chatId,
            String modelKey,
            String symbol,
            String timeframe,
            BigDecimal lastPrice,
            Instant ts
    );
}

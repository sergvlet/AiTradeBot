// src/main/java/com/chicu/aitradebot/strategy/ml/MlPrediction.java
package com.chicu.aitradebot.strategy.ml;

/**
 * probBuy/probSell/confidence — в диапазоне [0..1]
 */
public record MlPrediction(
        double probBuy,
        double probSell,
        double confidence,
        String modelKey
) { }

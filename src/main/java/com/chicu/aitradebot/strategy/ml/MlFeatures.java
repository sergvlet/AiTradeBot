// src/main/java/com/chicu/aitradebot/strategy/ml/MlFeatures.java
package com.chicu.aitradebot.strategy.ml;

import com.chicu.aitradebot.strategy.core.CandleProvider;

import java.math.BigDecimal;
import java.util.List;

public record MlFeatures(
        double lastClose,
        double ret1,
        double ret5,
        double volMean
) {
    public static MlFeatures fromCandles(List<CandleProvider.Candle> candles, BigDecimal lastPrice) {
        int n = candles.size();
        double last = candles.get(n - 1).close();
        double c1 = candles.get(Math.max(0, n - 2)).close();
        double c5 = candles.get(Math.max(0, n - 6)).close();

        double ret1 = (c1 != 0.0) ? (last / c1 - 1.0) : 0.0;
        double ret5 = (c5 != 0.0) ? (last / c5 - 1.0) : 0.0;

        int m = Math.min(50, n);
        double sumVol = 0.0;
        for (int i = n - m; i < n; i++) sumVol += candles.get(i).volume();
        double volMean = sumVol / (double) m;

        return new MlFeatures(last, ret1, ret5, volMean);
    }
}
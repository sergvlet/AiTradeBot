// src/main/java/com/chicu/aitradebot/strategy/rl/RlState.java
package com.chicu.aitradebot.strategy.rl;

import com.chicu.aitradebot.strategy.core.CandleProvider;

import java.math.BigDecimal;
import java.util.List;

public record RlState(
        double lastClose,
        double ret1,
        double ret10,
        double volMean
) {
    public static RlState fromCandles(List<CandleProvider.Candle> candles, BigDecimal price) {
        int n = candles.size();
        double last = candles.get(n - 1).close();
        double c1 = candles.get(Math.max(0, n - 2)).close();
        double c10 = candles.get(Math.max(0, n - 11)).close();

        double ret1 = (c1 != 0.0) ? (last / c1 - 1.0) : 0.0;
        double ret10 = (c10 != 0.0) ? (last / c10 - 1.0) : 0.0;

        int m = Math.min(50, n);
        double sumVol = 0.0;
        for (int i = n - m; i < n; i++) sumVol += candles.get(i).volume();
        double volMean = sumVol / (double) m;

        return new RlState(last, ret1, ret10, volMean);
    }
}
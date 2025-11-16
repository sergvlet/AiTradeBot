package com.chicu.aitradebot.strategy.core;

import java.time.Instant;
import java.util.List;

public interface CandleProvider {
    record Candle(Instant ts, double open, double high, double low, double close, double volume) {}
    List<Candle> getRecentCandles(long chatId, String symbol, String timeframe, int limit);
}

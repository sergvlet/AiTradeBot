package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LiveCandleAggregator {

    private final StrategyLivePublisher live;
    private final CandleProvider candleProvider;

    public LiveCandleAggregator(
            StrategyLivePublisher live,
            CandleProvider candleProvider
    ) {
        this.live = live;
        this.candleProvider = candleProvider;
    }

    // ============================================================
    // KEY
    // ============================================================
    private record Key(
            Long chatId,
            StrategyType strategy,
            String symbol,
            String timeframe
    ) {}

    // ============================================================
    // INTERNAL CANDLE
    // ============================================================
    private static class Candle {
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        double volume;
        Instant start;
    }

    private final Map<Key, Candle> candles = new ConcurrentHashMap<>();

    // ============================================================
    // MAIN ENTRY
    // ============================================================
    public void onPriceTick(Long chatId,
                            StrategyType strategy,
                            String symbol,
                            String timeframe,
                            long timeframeMillis,
                            BigDecimal price,
                            Instant ts) {

        if (price == null || ts == null) return;

        symbol = symbol.toUpperCase();
        timeframe = timeframe.toLowerCase();

        Key key = new Key(chatId, strategy, symbol, timeframe);

        long now = ts.toEpochMilli();
        long bucketStartMs = now - (now % timeframeMillis);
        Instant bucketStart = Instant.ofEpochMilli(bucketStartMs);

        Candle c = candles.get(key);

        // ========================================================
        // FIRST CANDLE
        // ========================================================
        if (c == null) {
            candles.put(key, newCandle(bucketStart, price));
            return;
        }

        // ========================================================
        // CLOSE OLD CANDLES (catch-up)
        // ========================================================
        while (c.start.toEpochMilli() < bucketStartMs) {
            closeAndPublish(chatId, strategy, symbol, timeframe, timeframeMillis, c);
            c = newCandle(
                    c.start.plusMillis(timeframeMillis),
                    c.close
            );
            candles.put(key, c);
        }

        // ========================================================
        // UPDATE CURRENT
        // ========================================================
        c.high = c.high.max(price);
        c.low  = c.low.min(price);
        c.close = price;
        c.volume += 1.0; // ⚠ approximate tick volume
    }

    // ============================================================
    // FLUSH
    // ============================================================
    public void flush(Long chatId,
                      StrategyType strategy,
                      String symbol,
                      String timeframe,
                      long timeframeMillis) {

        Key key = new Key(
                chatId,
                strategy,
                symbol.toUpperCase(),
                timeframe.toLowerCase()
        );

        Candle c = candles.remove(key);
        if (c != null) {
            closeAndPublish(chatId, strategy, symbol, timeframe, timeframeMillis, c);
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private Candle newCandle(Instant start, BigDecimal price) {
        Candle c = new Candle();
        c.start = start;
        c.open = price;
        c.high = price;
        c.low = price;
        c.close = price;
        c.volume = 1.0;
        return c;
    }

    private void closeAndPublish(Long chatId,
                                 StrategyType strategy,
                                 String symbol,
                                 String timeframe,
                                 long timeframeMillis,
                                 Candle c) {

        Instant closeTime = c.start.plusMillis(timeframeMillis);

        // UI
        live.pushCandleOhlc(
                chatId,
                strategy,
                symbol,
                timeframe,
                c.open,
                c.high,
                c.low,
                c.close,
                BigDecimal.valueOf(c.volume),
                closeTime
        );

        // STRATEGIES
        candleProvider.addCandle(
                chatId,
                symbol,
                timeframe,
                c.start,
                c.open.doubleValue(),
                c.high.doubleValue(),
                c.low.doubleValue(),
                c.close.doubleValue(),
                c.volume
        );

        log.debug(
                "🕯 Candle closed [{} {} {}] O={} H={} L={} C={} V={}",
                chatId, symbol, timeframe,
                c.open, c.high, c.low, c.close, c.volume
        );
    }
}

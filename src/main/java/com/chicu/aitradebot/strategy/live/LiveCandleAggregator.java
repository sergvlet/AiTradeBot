package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🔥 LIVE v4 Candle Aggregator
 * Собирает OHLC из price ticks по timeframe и:
 *  - отправляет свечи в UI (StrategyLivePublisher)
 *  - сохраняет свечи в CandleProvider
 * ❗ volume НЕ хардкодится в стратегии
 */
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
    // KEY (record сам генерирует equals/hashCode)
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
        double volume;     // ✅ double, не null
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

        Key key = new Key(chatId, strategy, symbol, timeframe);
        long now = ts.toEpochMilli();

        Candle c = candles.get(key);

        // =======================
        // NEW CANDLE
        // =======================
        if (c == null || now - c.start.toEpochMilli() >= timeframeMillis) {

            if (c != null) {
                closeAndPublish(chatId, strategy, symbol, timeframe, timeframeMillis, c);
            }

            Candle nc = new Candle();
            nc.start  = Instant.ofEpochMilli(now - (now % timeframeMillis));
            nc.open   = price;
            nc.high   = price;
            nc.low    = price;
            nc.close  = price;
            nc.volume = 0.0;

            candles.put(key, nc);
            return;
        }

        // =======================
        // UPDATE CURRENT
        // =======================
        c.high  = c.high.max(price);
        c.low   = c.low.min(price);
        c.close = price;
    }

    // ============================================================
    // FLUSH (STOP)
    // ============================================================
    public void flush(Long chatId,
                      StrategyType strategy,
                      String symbol,
                      String timeframe,
                      long timeframeMillis) {

        Key key = new Key(chatId, strategy, symbol, timeframe);
        Candle c = candles.remove(key);

        if (c != null) {
            closeAndPublish(chatId, strategy, symbol, timeframe, timeframeMillis, c);
        }
    }

    // ============================================================
    // CLOSE + SAVE
    // ============================================================
    private void closeAndPublish(Long chatId,
                                 StrategyType strategy,
                                 String symbol,
                                 String timeframe,
                                 long timeframeMillis,
                                 Candle c) {

        Instant closeTime = c.start.plusMillis(timeframeMillis);

        // 🔥 UI
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

        // 💾 STRATEGIES (RSI / EMA / ML)
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

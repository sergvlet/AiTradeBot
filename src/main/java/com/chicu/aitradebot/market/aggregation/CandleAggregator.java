package com.chicu.aitradebot.market.aggregation;

import com.chicu.aitradebot.market.MarketTickListener;
import com.chicu.aitradebot.market.ws.RealtimeStreamService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CandleAggregator implements MarketTickListener {

    private static final long PUSH_DELAY = 300;

    private final RealtimeStreamService stream;
    private final Map<String, Map<String, LiveCandle>> live = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPush = new ConcurrentHashMap<>();
    // текущие свечи по таймфреймам
    private final Map<String, StrategyChartDto.CandleDto> current = new HashMap<>();

    private final Map<String, Long> tfMap = Map.of(
            "1s", 1000L,
            "3s", 3000L,
            "5s", 5000L,
            "15s", 15000L,
            "1m", 60000L,
            "3m", 180000L
    );
    @Getter
    @Setter
    private String symbol;
    public CandleAggregator(RealtimeStreamService stream) {
        this.stream = stream;
    }

    @Override
    public void onTick(String symbol, double volume, long ts, double price) {

        for (var tf : tfMap.entrySet()) {
            String code = tf.getKey();
            long frame = tf.getValue();

            long bucket = ts - (ts % frame);

            Map<String, LiveCandle> map =
                    live.computeIfAbsent(symbol, x -> new ConcurrentHashMap<>());

            LiveCandle c = map.get(code);

            // ---- закрытие свечи ----
            if (c == null || c.bucket != bucket) {

                if (c != null) {
                    CandleProvider.Candle closed = new CandleProvider.Candle(
                            c.bucket, c.open, c.high, c.low, c.close, c.volume
                    );
                    stream.sendCandle(symbol, code, closed);
                }

                c = new LiveCandle(bucket, price);
                map.put(code, c);
                continue;
            }

            // ---- обновление ----
            c.close = price;
            if (price > c.high) c.high = price;
            if (price < c.low)  c.low = price;
            c.volume += volume;

            long now = System.currentTimeMillis();
            long last = lastPush.getOrDefault(code, 0L);
            if (now - last < PUSH_DELAY) continue;
            lastPush.put(code, now);

            CandleProvider.Candle liveC = new CandleProvider.Candle(
                    bucket, c.open, c.high, c.low, c.close, c.volume
            );

            stream.sendCandle(symbol, code, liveC);
        }
    }

    private static class LiveCandle {
        long bucket;
        double open, high, low, close, volume;

        LiveCandle(long bucket, double price) {
            this.bucket = bucket;
            this.open = this.high = this.low = this.close = price;
            this.volume = 0;
        }
    }
    public void init(String symbol) {
        log.info("🔄 CandleAggregator initialized for symbol {}", symbol);
        this.symbol = symbol;
        current.clear();
    }

}

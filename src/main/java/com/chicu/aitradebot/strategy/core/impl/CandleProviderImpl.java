package com.chicu.aitradebot.strategy.core.impl;

import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleProviderImpl implements CandleProvider {

    private final MarketStreamManager manager;

    /**
     * 🔥 LIVE candles buffer
     * key = chatId|symbol|timeframe
     * value = ordered list of CandleProvider.Candle
     */
    private final Map<String, Deque<Candle>> liveCandles = new ConcurrentHashMap<>();

    private static final int MAX_LIVE_BUFFER = 1500; // защита памяти, НЕ хардкод стратегии

    // ============================================================
    // ADD LIVE CANDLE (из LiveCandleAggregator)
    // ============================================================
    @Override
    public void addCandle(
            long chatId,
            String symbol,
            String timeframe,
            Instant time,
            double open,
            double high,
            double low,
            double close,
            double volume
    ) {
        String key = key(chatId, symbol, timeframe);

        Deque<Candle> deque =
                liveCandles.computeIfAbsent(key, k -> new ArrayDeque<>());

        Candle candle = Candle.fromInstant(
                time,
                open,
                high,
                low,
                close,
                volume
        );

        // дедуп по времени (на случай повторного close)
        if (!deque.isEmpty() && deque.getLast().time() == candle.time()) {
            deque.removeLast();
        }

        deque.addLast(candle);

        // ограничение памяти
        while (deque.size() > MAX_LIVE_BUFFER) {
            deque.removeFirst();
        }
    }

    // ============================================================
    // GET RECENT CANDLES (LIVE → HISTORY fallback)
    // ============================================================
    @Override
    public List<Candle> getRecentCandles(
            long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        try {
            String sym = normalize(symbol);
            String tf  = normalize(timeframe);
            if (sym.isEmpty() || tf.isEmpty() || limit <= 0) {
                return List.of();
            }

            String key = key(chatId, sym, tf);

            List<Candle> result = new ArrayList<>(limit);

            // 1️⃣ LIVE candles (приоритет)
            Deque<Candle> live = liveCandles.get(key);
            if (live != null && !live.isEmpty()) {
                int from = Math.max(0, live.size() - limit);
                Iterator<Candle> it = live.iterator();
                int idx = 0;
                while (it.hasNext()) {
                    Candle c = it.next();
                    if (idx++ >= from) {
                        result.add(c);
                    }
                }
            }

            // если LIVE достаточно — выходим
            if (result.size() >= limit) {
                return result;
            }

            // 2️⃣ HISTORY fallback
            int need = limit - result.size();

            List<com.chicu.aitradebot.market.model.Candle> hist =
                    manager.getCandles(sym, tf, need);

            for (com.chicu.aitradebot.market.model.Candle c : hist) {
                result.add(new Candle(
                        c.getTime(),
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume()
                ));
            }

            // финальная сортировка по времени
            result.sort(Comparator.comparingLong(Candle::time));

            // финальный лимит
            if (result.size() > limit) {
                return result.subList(result.size() - limit, result.size());
            }

            return result;

        } catch (Exception e) {
            log.error("❌ getRecentCandles error [{} {}]: {}", symbol, timeframe, e.getMessage(), e);
            return List.of();
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private String key(long chatId, String symbol, String timeframe) {
        return chatId + "|" + symbol.toUpperCase() + "|" + timeframe.toUpperCase();
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}

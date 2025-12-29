package com.chicu.aitradebot.strategy.core.impl;

import com.chicu.aitradebot.market.CandleResampler;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleProviderImpl implements CandleProvider {

    private static final int BASE_FETCH_MULTIPLIER = 5;
    private static final String BASE_TF = "1m";

    private final MarketStreamManager manager;

    // ============================================================
    // ADD LIVE CANDLE (ТОЛЬКО MARKET)
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
        if (time == null || symbol == null) return;

        String tf = normalizeTf(timeframe);
        if (!BASE_TF.equals(tf)) return;

        // ⬇⬇⬇ ЯВНО MARKET CANDLE
        com.chicu.aitradebot.market.model.Candle marketCandle =
                new com.chicu.aitradebot.market.model.Candle(
                        time.toEpochMilli(),
                        open,
                        high,
                        low,
                        close,
                        volume,
                        true
                );

        manager.addCandle(symbol, BASE_TF, marketCandle);
    }

    // ============================================================
    // READ (Market → Resample → Core)
    // ============================================================
    @Override
    public List<CandleProvider.Candle> getRecentCandles(
            long chatId,
            String symbol,
            String timeframe,
            int limit
    ) {
        if (symbol == null || limit <= 0) {
            return List.of();
        }

        String tf = normalizeTf(timeframe);

        // ⬇⬇⬇ ЯВНО MARKET CANDLE
        List<com.chicu.aitradebot.market.model.Candle> base =
                manager.getCandles(
                        symbol,
                        BASE_TF,
                        limit * BASE_FETCH_MULTIPLIER
                );

        if (base.isEmpty()) {
            return List.of();
        }

        // ⬇⬇⬇ ВСЁ ЕЩЁ MARKET CANDLE
        List<com.chicu.aitradebot.market.model.Candle> resampled =
                BASE_TF.equals(tf)
                        ? base
                        : CandleResampler.resample(base, tf);

        int from = Math.max(0, resampled.size() - limit);

        // ⬇⬇⬇ КОНВЕРТАЦИЯ В CORE
        return resampled.subList(from, resampled.size())
                .stream()
                .map(this::toCore)
                .toList();
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private String normalizeTf(String tf) {
        if (tf == null || tf.isBlank()) return BASE_TF;

        tf = tf.trim().toLowerCase(Locale.ROOT);
        if (tf.startsWith("kline_")) tf = tf.substring(6);

        return tf;
    }

    // 🔥 ЕДИНСТВЕННОЕ МЕСТО СОЗДАНИЯ CandleProvider.Candle
    private CandleProvider.Candle toCore(
            com.chicu.aitradebot.market.model.Candle c
    ) {
        return new CandleProvider.Candle(
                c.getTime(),
                c.getOpen(),
                c.getHigh(),
                c.getLow(),
                c.getClose(),
                c.getVolume()
        );
    }
}

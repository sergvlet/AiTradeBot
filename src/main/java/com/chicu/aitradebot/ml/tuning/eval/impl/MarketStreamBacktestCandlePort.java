package com.chicu.aitradebot.ml.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.ml.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ml.tuning.eval.CandleBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamBacktestCandlePort implements BacktestCandlePort {

    private final MarketStreamManager streamManager;

    @Override
    public List<CandleBar> load(long chatId,
                                StrategyType type,
                                String symbol,
                                String timeframe,
                                Instant startAt,
                                Instant endAt,
                                int limit) {

        if (symbol == null || timeframe == null || startAt == null || endAt == null) return List.of();

        String s = symbol.toUpperCase(Locale.ROOT);
        String tf = timeframe.toLowerCase(Locale.ROOT);

        // streamManager.getCandles(...) у тебя отдаёт "последние N" (часто newest-first).
        // Берём limit и потом фильтруем по времени.
        List<Candle> raw = streamManager.getCandles(s, tf, Math.max(1, limit));
        if (raw == null || raw.isEmpty()) return List.of();

        // Чтобы бэктест шёл правильно — делаем ascending по времени
        List<Candle> copy = new ArrayList<>(raw);
        // если кеш уже ascending — reverse не навредит? может навредить.
        // Поэтому сортируем по openTime (time).
        copy.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));

        long from = startAt.toEpochMilli();
        long to = endAt.toEpochMilli();

        List<CandleBar> out = new ArrayList<>(copy.size());
        for (Candle c : copy) {
            long t = c.getTime();
            if (t < from || t > to) continue;

            out.add(new CandleBar(
                    Instant.ofEpochMilli(t),
                    bd(c.getOpen()),
                    bd(c.getHigh()),
                    bd(c.getLow()),
                    bd(c.getClose()),
                    bd(c.getVolume())
            ));
        }

        if (out.isEmpty()) {
            log.warn("🧪 Backtest candles empty from cache (symbol={}, tf={}, range={}..{})",
                    s, tf, startAt, endAt);
        }

        return out;
    }

    private static BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }
}

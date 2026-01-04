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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridBacktestCandlePort implements BacktestCandlePort {

    private final MarketStreamManager streamManager;
    private final HistoryWarmupService warmupService;

    @Override
    public List<CandleBar> load(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               Instant startAt,
                               Instant endAt,
                               int limit) {

        String s = symbol.toUpperCase(Locale.ROOT);
        String tf = timeframe.toLowerCase(Locale.ROOT);

        List<CandleBar> fromCache = readFromCache(s, tf, startAt, endAt, limit);

        // если данных мало — прогреваем REST-историей и читаем заново
        if (fromCache.size() < Math.min(200, Math.max(50, limit / 10))) {
            warmupService.warmup(chatId, type, s, tf, startAt.toEpochMilli(), endAt.toEpochMilli(), limit);
            fromCache = readFromCache(s, tf, startAt, endAt, limit);
        }

        return fromCache;
    }

    private List<CandleBar> readFromCache(String symbol,
                                          String timeframe,
                                          Instant startAt,
                                          Instant endAt,
                                          int limit) {

        List<Candle> raw = streamManager.getCandles(symbol, timeframe, Math.max(1, limit));
        if (raw == null || raw.isEmpty()) return List.of();

        long from = startAt.toEpochMilli();
        long to = endAt.toEpochMilli();

        // приводим к возрастающему времени
        List<Candle> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingLong(Candle::getTime));

        List<CandleBar> out = new ArrayList<>();
        for (Candle c : sorted) {
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
        return out;
    }

    private static BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }
}

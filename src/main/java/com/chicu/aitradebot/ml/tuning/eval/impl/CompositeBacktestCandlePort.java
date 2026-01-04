package com.chicu.aitradebot.ml.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ml.tuning.eval.CandleBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Primary
@Service("compositeBacktestCandlePort")
public class CompositeBacktestCandlePort implements BacktestCandlePort {

    private final BacktestCandlePort marketStream;
    private final BacktestCandlePort hybrid;

    public CompositeBacktestCandlePort(
            @Qualifier("marketStreamBacktestCandlePort") BacktestCandlePort marketStream,
            @Qualifier("hybridBacktestCandlePort") BacktestCandlePort hybrid
    ) {
        this.marketStream = marketStream;
        this.hybrid = hybrid;
    }

    @Override
    public List<CandleBar> load(long chatId,
                                StrategyType type,
                                String symbol,
                                String timeframe,
                                Instant startAt,
                                Instant endAt,
                                int limit) {

        // 1) быстрый источник (кеш/WS)
        List<CandleBar> cached = safeLoad(marketStream, chatId, type, symbol, timeframe, startAt, endAt, limit);
        if (isGoodEnough(cached, limit)) return cached;

        // 2) надёжный источник (REST/гибрид)
        List<CandleBar> hist = safeLoad(hybrid, chatId, type, symbol, timeframe, startAt, endAt, limit);
        if (isGoodEnough(hist, limit)) return hist;

        // 3) fallback
        List<CandleBar> best = size(hist) >= size(cached) ? hist : cached;

        if (best == null || best.isEmpty()) {
            log.warn("🧪 Backtest candles: both sources empty (chatId={}, type={}, symbol={}, tf={}, limit={})",
                    chatId, type, symbol, timeframe, limit);
        }

        return best != null ? best : List.of();
    }

    private static List<CandleBar> safeLoad(BacktestCandlePort port,
                                            long chatId,
                                            StrategyType type,
                                            String symbol,
                                            String timeframe,
                                            Instant startAt,
                                            Instant endAt,
                                            int limit) {
        try {
            return port.load(chatId, type, symbol, timeframe, startAt, endAt, limit);
        } catch (Exception e) {
            log.warn("BacktestCandlePort failed: {} -> {}", port.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    private static boolean isGoodEnough(List<CandleBar> bars, int limit) {
        if (bars == null || bars.isEmpty()) return false;
        int min = Math.max(50, Math.min(limit, 200));
        return bars.size() >= min;
    }

    private static int size(List<CandleBar> v) {
        return v != null ? v.size() : 0;
    }
}

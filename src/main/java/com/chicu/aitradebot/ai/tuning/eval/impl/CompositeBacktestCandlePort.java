package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
                               String exchange,
                               NetworkType network,
                               String symbol,
                               String timeframe,
                               Instant startAt,
                               Instant endAt,
                               int limit) {

        // ❗ НЕ подставляем дефолтную сеть/биржу здесь.
        // Если exchange/network=null — порты сами резолвят env через StrategyEnvResolver.
        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        // 1) быстрый источник (кеш/WS)
        List<CandleBar> cached = safeLoad(marketStream, chatId, type, ex, net, symbol, timeframe, startAt, endAt, limit);
        if (isGoodEnough(cached, limit)) return cached;

        // 2) надёжный источник (REST/гибрид)
        List<CandleBar> hist = safeLoad(hybrid, chatId, type, ex, net, symbol, timeframe, startAt, endAt, limit);
        if (isGoodEnough(hist, limit)) return hist;

        // 3) fallback: берём что больше
        List<CandleBar> best = size(hist) >= size(cached) ? hist : cached;

        if (best == null || best.isEmpty()) {
            log.warn("🧪 Backtest candles: both sources empty (chatId={}, type={}, ex={}, net={}, symbol={}, tf={}, limit={})",
                    chatId, type, safe(ex), String.valueOf(net), safe(symbol), safe(timeframe), limit);
        }

        return best != null ? best : List.of();
    }

    private List<CandleBar> safeLoad(BacktestCandlePort port,
                                    long chatId,
                                    StrategyType type,
                                    String exchange,
                                    NetworkType network,
                                    String symbol,
                                    String timeframe,
                                    Instant startAt,
                                    Instant endAt,
                                    int limit) {
        try {
            return port.load(chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
        } catch (Exception e) {
            log.warn("🧪 BacktestCandlePort failed: {} -> {}", port.getClass().getSimpleName(), safeMsg(e));
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

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String safe(String s) {
        if (s == null) return "null";
        String x = s.trim();
        if (x.isEmpty()) return "null";
        return x.length() > 64 ? x.substring(0, 64) : x;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }
}

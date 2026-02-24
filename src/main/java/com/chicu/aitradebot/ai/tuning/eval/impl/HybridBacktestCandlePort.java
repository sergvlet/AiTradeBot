package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class HybridBacktestCandlePort implements BacktestCandlePort {

    private final ObjectProvider<BacktestCandlePort> ports;

    @Value("${ai.backtest.hybrid.min-cache-candles:150}")
    private int minCacheCandles;

    public List<CandleBar> load(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               Instant startAt,
                               Instant endAt,
                               int limit) {

        return load(chatId, type, null, null, symbol, timeframe, startAt, endAt, limit);
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

        if (chatId <= 0 || type == null) return List.of();
        if (symbol == null || symbol.isBlank()) return List.of();
        if (timeframe == null || timeframe.isBlank()) return List.of();
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return List.of();
        if (limit <= 0) return List.of();

        List<BacktestCandlePort> all = ports != null ? ports.orderedStream().toList() : List.of();
        if (all.isEmpty()) return List.of();

        BacktestCandlePort cachePort = pickByName(all, "MarketStreamBacktestCandlePort");
        BacktestCandlePort fallbackPort = pickFallbackPort(all, cachePort);

        List<CandleBar> cached = tryLoad(cachePort, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);

        int minOk = Math.max(1, Math.min(limit, Math.max(1, minCacheCandles)));
        if (cached.size() >= minOk) return cached;

        List<CandleBar> fallback = tryLoad(fallbackPort, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);

        if (!fallback.isEmpty() && cached.isEmpty()) return fallback;
        if (!fallback.isEmpty() && fallback.size() > cached.size()) return fallback;

        if (!cached.isEmpty() && !fallback.isEmpty()) {
            return mergeDedupSorted(cached, fallback, startAt, endAt, limit);
        }

        return cached;
    }

    private BacktestCandlePort pickFallbackPort(List<BacktestCandlePort> all, BacktestCandlePort cache) {
        BacktestCandlePort p = pickByPredicate(all, cache, sn ->
                sn.contains("History") || sn.contains("Warmup") || sn.contains("Exchange") || sn.contains("Http"));
        if (p != null) return p;

        for (BacktestCandlePort x : all) {
            if (x == null) continue;
            if (x == this) continue;
            if (x == cache) continue;

            String sn = x.getClass().getSimpleName();
            if (HybridBacktestCandlePort.class.getSimpleName().equals(sn)) continue;
            if (CompositeBacktestCandlePort.class.getSimpleName().equals(sn)) continue;

            return x;
        }
        return null;
    }

    private BacktestCandlePort pickByName(List<BacktestCandlePort> all, String simpleName) {
        if (all == null || all.isEmpty() || simpleName == null) return null;
        for (BacktestCandlePort p : all) {
            if (p == null) continue;
            if (p == this) continue;
            String sn = p.getClass().getSimpleName();
            if (HybridBacktestCandlePort.class.getSimpleName().equals(sn)) continue;
            if (CompositeBacktestCandlePort.class.getSimpleName().equals(sn)) continue;
            if (sn.equals(simpleName)) return p;
        }
        return null;
    }

    private BacktestCandlePort pickByPredicate(List<BacktestCandlePort> all, BacktestCandlePort skip, java.util.function.Predicate<String> pred) {
        if (all == null || all.isEmpty() || pred == null) return null;
        for (BacktestCandlePort p : all) {
            if (p == null) continue;
            if (p == this) continue;
            if (p == skip) continue;

            String sn = p.getClass().getSimpleName();
            if (HybridBacktestCandlePort.class.getSimpleName().equals(sn)) continue;
            if (CompositeBacktestCandlePort.class.getSimpleName().equals(sn)) continue;

            if (pred.test(sn)) return p;
        }
        return null;
    }

    private List<CandleBar> tryLoad(BacktestCandlePort port,
                                   long chatId,
                                   StrategyType type,
                                   String exchange,
                                   NetworkType network,
                                   String symbol,
                                   String timeframe,
                                   Instant startAt,
                                   Instant endAt,
                                   int limit) {

        if (port == null) return List.of();

        try {
            List<CandleBar> out = invokePort(port, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
            return out != null ? out : List.of();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("HybridBacktestCandlePort: {} failed: {}", port.getClass().getSimpleName(), e.getMessage());
            }
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<CandleBar> invokePort(BacktestCandlePort port,
                                      long chatId,
                                      StrategyType type,
                                      String exchange,
                                      NetworkType network,
                                      String symbol,
                                      String timeframe,
                                      Instant startAt,
                                      Instant endAt,
                                      int limit) throws Exception {

        Method mNew = findMethod(port.getClass(),
                "load",
                long.class, StrategyType.class, String.class, NetworkType.class,
                String.class, String.class, Instant.class, Instant.class, int.class);

        if (mNew != null) {
            Object res = mNew.invoke(port, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
            return (res instanceof List<?> l) ? (List<CandleBar>) l : List.of();
        }

        Method mOld = findMethod(port.getClass(),
                "load",
                long.class, StrategyType.class,
                String.class, String.class,
                Instant.class, Instant.class, int.class);

        if (mOld != null) {
            Object res = mOld.invoke(port, chatId, type, symbol, timeframe, startAt, endAt, limit);
            return (res instanceof List<?> l) ? (List<CandleBar>) l : List.of();
        }

        return List.of();
    }

    private Method findMethod(Class<?> c, String name, Class<?>... sig) {
        try { return c.getMethod(name, sig); } catch (Exception ignored) { return null; }
    }

    private static List<CandleBar> mergeDedupSorted(List<CandleBar> a,
                                                    List<CandleBar> b,
                                                    Instant startAt,
                                                    Instant endAt,
                                                    int limit) {

        long startMs = startAt != null ? startAt.toEpochMilli() : Long.MIN_VALUE;
        long endMs = endAt != null ? endAt.toEpochMilli() : Long.MAX_VALUE;

        TreeMap<Long, CandleBar> map = new TreeMap<>();
        putAll(map, a, startMs, endMs);
        putAll(map, b, startMs, endMs);

        if (map.isEmpty()) return List.of();

        List<CandleBar> out = new ArrayList<>(map.values());
        if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
        return out;
    }

    private static void putAll(TreeMap<Long, CandleBar> map, List<CandleBar> src, long startMs, long endMs) {
        if (src == null) return;
        for (CandleBar c : src) {
            if (c == null || c.openTime() == null) continue;
            long t = c.openTime().toEpochMilli();
            if (t < startMs || t > endMs) continue;
            map.putIfAbsent(t, c);
        }
    }
}
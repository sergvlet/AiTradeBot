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
import java.util.List;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class HybridBacktestCandlePort implements BacktestCandlePort {

    private final ObjectProvider<BacktestCandlePort> ports;

    /** сколько свечей считаем “достаточно”, чтобы не дергать fallback */
    @Value("${ai.backtest.hybrid.min-cache-candles:150}")
    private int minCacheCandles;

    // =====================================================
    // ✅ OLD SIGNATURE (может быть в твоём интерфейсе)
    // =====================================================

    public List<CandleBar> load(long chatId,
                                StrategyType type,
                                String symbol,
                                String timeframe,
                                Instant startAt,
                                Instant endAt,
                                int limit) {

        return load(chatId, type, null, null, symbol, timeframe, startAt, endAt, limit);
    }

    // =====================================================
    // ✅ NEW SIGNATURE (exchange/network) — ТОЧНО есть в интерфейсе по ошибке компилятора
    // =====================================================

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

        BacktestCandlePort cachePort = pickPort(all, "MarketStreamBacktestCandlePort");
        BacktestCandlePort fallbackPort = pickFallback(all, cachePort);

        // 1) cache first
        List<CandleBar> cached = tryLoad(cachePort, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
        if (cached != null && cached.size() >= Math.max(1, minCacheCandles)) {
            return cached;
        }

        // 2) fallback
        List<CandleBar> fallback = tryLoad(fallbackPort, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }

        // 3) если fallback пустой — отдаем cache что есть
        return cached != null ? cached : List.of();
    }

    // =====================================================
    // internal
    // =====================================================

    private BacktestCandlePort pickPort(List<BacktestCandlePort> all, String simpleName) {
        if (all == null || all.isEmpty() || simpleName == null) return null;
        for (BacktestCandlePort p : all) {
            if (p == null) continue;
            if (p == this) continue;
            if (p.getClass() == HybridBacktestCandlePort.class) continue;
            if (p.getClass().getSimpleName().equals(simpleName)) return p;
        }
        return null;
    }

    private BacktestCandlePort pickFallback(List<BacktestCandlePort> all, BacktestCandlePort cache) {
        if (all == null || all.isEmpty()) return null;
        for (BacktestCandlePort p : all) {
            if (p == null) continue;
            if (p == this) continue;
            if (p == cache) continue;
            if (p.getClass() == HybridBacktestCandlePort.class) continue;
            if (p.getClass() == CompositeBacktestCandlePort.class) continue; // избегаем циклов
            return p;
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
            return invokePort(port, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
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

        // 1) try NEW
        Method mNew = findMethod(port.getClass(),
                "load",
                long.class, StrategyType.class, String.class, NetworkType.class,
                String.class, String.class, Instant.class, Instant.class, int.class);

        if (mNew != null) {
            Object res = mNew.invoke(port, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
            return (res instanceof List<?> l) ? (List<CandleBar>) l : List.of();
        }

        // 2) fallback OLD
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
        try {
            return c.getMethod(name, sig);
        } catch (Exception ignored) {
            return null;
        }
    }
}
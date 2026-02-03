package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.MarketStreamManager;
import com.chicu.aitradebot.market.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service("hybridBacktestCandlePort")
@RequiredArgsConstructor
public class HybridBacktestCandlePort implements BacktestCandlePort {

    private final MarketStreamManager streamManager;
    private final HistoryWarmupService warmupService;
    private final StrategyEnvResolver envResolver;

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

        if (chatId <= 0) return List.of();
        if (type == null) return List.of();
        if (symbol == null || symbol.isBlank()) return List.of();
        if (timeframe == null || timeframe.isBlank()) return List.of();
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return List.of();
        if (limit <= 0) return List.of();

        String s = symbol.trim().toUpperCase(Locale.ROOT);
        String tf = timeframe.trim().toLowerCase(Locale.ROOT);

        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        // ✅ если пришли без env — резолвим строго из StrategySettings
        if (ex == null || net == null) {
            StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
            if (ex == null) ex = normalizeExchangeOrNull(env.exchangeName());
            if (net == null) net = env.networkType();
        }

        if (ex == null || net == null) {
            if (log.isDebugEnabled()) {
                log.debug("🧪 HybridBacktestCandlePort: env unresolved chatId={} type={} ex={} net={}",
                        chatId, type, exchange, network);
            }
            return List.of();
        }

        // 1) читаем из кеша/стрима (СТРОГО по ex/net если streamManager это поддерживает)
        List<CandleBar> fromCache = readFromCache(ex, net, s, tf, startAt, endAt, limit);

        // 2) если мало — прогреваем REST-историей и читаем снова
        int minEnough = Math.min(200, Math.max(50, limit / 10));
        if (fromCache.size() < minEnough) {
            try {
                int warmed = warmupService.warmup(
                        chatId,
                        type,
                        ex,
                        net,
                        s,
                        tf,
                        startAt.toEpochMilli(),
                        endAt.toEpochMilli(),
                        limit
                );

                if (log.isDebugEnabled()) {
                    log.debug("🧪 Hybrid warmup done chatId={} type={} ex={} net={} sym={} tf={} warmed={} limit={} range={}..{}",
                            chatId, type, ex, net, s, tf, warmed, limit, startAt, endAt);
                }
            } catch (Exception e) {
                log.warn("🧪 Hybrid warmup fail chatId={} type={} ex={} net={} sym={} tf={} err={}",
                        chatId, type, ex, net, s, tf, safeMsg(e));
            }

            fromCache = readFromCache(ex, net, s, tf, startAt, endAt, limit);
        }

        return fromCache;
    }

    private List<CandleBar> readFromCache(String exchange,
                                          NetworkType network,
                                          String symbol,
                                          String timeframe,
                                          Instant startAt,
                                          Instant endAt,
                                          int limit) {

        List<Candle> raw = safeGetCandles(exchange, network, symbol, timeframe, Math.max(1, limit));
        if (raw == null || raw.isEmpty()) return List.of();

        long from = startAt.toEpochMilli();
        long toExcl = endAt.toEpochMilli(); // ✅ end exclusive

        // ascending по времени
        List<Candle> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingLong(Candle::getTime));

        List<CandleBar> out = new ArrayList<>(sorted.size());
        for (Candle c : sorted) {
            long t = c.getTime();
            if (t < from || t >= toExcl) continue;

            out.add(new CandleBar(
                    Instant.ofEpochMilli(t),
                    bd(c.getOpen()),
                    bd(c.getHigh()),
                    bd(c.getLow()),
                    bd(c.getClose()),
                    bd(c.getVolume())
            ));
        }

        if (out.isEmpty()) return List.of();

        // уважим limit
        if (out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }

        return out;
    }

    // =====================================================
    // streamManager compat (new/old signatures)
    // =====================================================

    @SuppressWarnings("unchecked")
    private List<Candle> safeGetCandles(String exchange,
                                       NetworkType network,
                                       String symbol,
                                       String timeframe,
                                       int limit) {

        try {
            // 1) new signature: getCandles(String ex, NetworkType net, String sym, String tf, int limit)
            Method m5 = findMethod("getCandles", 5);
            if (m5 != null) {
                Object res = m5.invoke(streamManager, exchange, network, symbol, timeframe, limit);
                return (res instanceof List<?> l) ? (List<Candle>) l : List.of();
            }

            // 2) old signature: getCandles(String sym, String tf, int limit)
            Method m3 = findMethod("getCandles", 3);
            if (m3 != null) {
                Object res = m3.invoke(streamManager, symbol, timeframe, limit);
                return (res instanceof List<?> l) ? (List<Candle>) l : List.of();
            }

            return List.of();
        } catch (Exception e) {
            log.warn("🧪 Hybrid cache read failed (ex={}, net={}, sym={}, tf={}): {}",
                    exchange, network, symbol, timeframe, safeMsg(e));
            return List.of();
        }
    }

    private Method findMethod(String name, int paramCount) {
        for (Method m : streamManager.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramCount) continue;
            return m;
        }
        return null;
    }

    // =====================================================
    // utils
    // =====================================================

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }
}

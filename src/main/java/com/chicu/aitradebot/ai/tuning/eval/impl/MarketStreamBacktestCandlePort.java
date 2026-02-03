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
import java.util.List;
import java.util.Locale;

@Slf4j
@Service("marketStreamBacktestCandlePort")
@RequiredArgsConstructor
public class MarketStreamBacktestCandlePort implements BacktestCandlePort {

    private final MarketStreamManager streamManager;
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

        // ✅ НЕ подставляем дефолтный MAINNET/BINANCE: если null — резолвим из настроек стратегии.
        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        if (ex == null || net == null) {
            StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
            if (ex == null) ex = normalizeExchangeOrNull(env.exchangeName());
            if (net == null) net = env.networkType();
        }

        if (ex == null || net == null) return List.of();

        // streamManager может быть старым (getCandles(symbol, tf, limit)) или новым (getCandles(exchange, network, symbol, tf, limit))
        List<Candle> raw = safeGetCandles(ex, net, s, tf, Math.max(1, limit));
        if (raw == null || raw.isEmpty()) return List.of();

        // ascending по времени (чтобы бэктест шёл корректно)
        List<Candle> copy = new ArrayList<>(raw);
        copy.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));

        long from = startAt.toEpochMilli();
        long toExcl = endAt.toEpochMilli(); // endAt EXCLUSIVE

        List<CandleBar> out = new ArrayList<>(copy.size());
        for (Candle c : copy) {
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

        if (out.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("🧪 Backtest candles empty from cache (chatId={}, type={}, ex={}, net={}, sym={}, tf={}, range={}..{})",
                        chatId, type, ex, net, s, tf, startAt, endAt);
            }
            return List.of();
        }

        // уважим limit (если вдруг влезло больше)
        if (out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }

        return out;
    }

    // =====================================================
    // streamManager compat
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
            log.warn("🧪 MarketStream candles failed (ex={}, net={}, sym={}, tf={}): {}",
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

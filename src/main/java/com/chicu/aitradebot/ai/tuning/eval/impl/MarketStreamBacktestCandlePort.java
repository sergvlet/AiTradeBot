package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamBacktestCandlePort implements BacktestCandlePort {

    private final ObjectProvider<MarketDataStreamService> marketStream;

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

        String sym = normSymbol(symbol);
        String tf = normTf(timeframe);

        if (sym == null || tf == null) return List.of();
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return List.of();
        if (limit <= 0) return List.of();

        MarketDataStreamService svc = marketStream != null ? marketStream.getIfAvailable() : null;
        if (svc == null) return List.of();

        List<?> raw = tryGetCachedCandles(svc, chatId, type, exchange, network, sym, tf, limit);
        if (raw == null || raw.isEmpty()) return List.of();

        long startMs = startAt.toEpochMilli();
        long endMs = endAt.toEpochMilli();
        long tfMs = timeframeToMillis(tf);

        TreeMap<Long, CandleBar> map = new TreeMap<>();

        for (Object c : raw) {
            if (c == null) continue;

            Long openMs = extractOpenTimeMs(c);
            if (openMs == null || openMs <= 0) {
                Long closeMs = extractCloseTimeMs(c);
                if (closeMs == null || closeMs <= 0) continue;
                openMs = closeMs - tfMs;
                if (openMs <= 0) continue;
            }

            if (openMs < startMs || openMs > endMs) continue;

            BigDecimal open  = asBigDecimal(readAny(c, "getOpen", "open", "getO", "o"));
            BigDecimal high  = asBigDecimal(readAny(c, "getHigh", "high", "getH", "h"));
            BigDecimal low   = asBigDecimal(readAny(c, "getLow",  "low",  "getL", "l"));
            BigDecimal close = asBigDecimal(readAny(c, "getClose","close","getC", "c"));
            BigDecimal vol   = asBigDecimal(readAny(c, "getVolume","volume", "getVol", "vol", "getV", "v"));

            if (!isValidOhlc(open, high, low, close)) continue;

            CandleBar bar = new CandleBar(Instant.ofEpochMilli(openMs), open, high, low, close, vol);
            map.putIfAbsent(openMs, bar);
        }

        if (map.isEmpty()) return List.of();

        List<CandleBar> out = new ArrayList<>(map.values());
        if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
        return out;
    }

    private List<?> tryGetCachedCandles(MarketDataStreamService svc,
                                        long chatId,
                                        StrategyType type,
                                        String exchange,
                                        NetworkType network,
                                        String symbol,
                                        String timeframe,
                                        int limit) {

        List<?> v;

        v = tryInvokeList(svc, "getCachedCandles",
                new Object[]{chatId, type, exchange, network, symbol, timeframe, limit},
                new Class<?>[]{long.class, StrategyType.class, String.class, NetworkType.class, String.class, String.class, int.class});
        if (notEmpty(v)) return v;

        v = tryInvokeList(svc, "getCachedCandles",
                new Object[]{chatId, type, symbol, timeframe, limit},
                new Class<?>[]{long.class, StrategyType.class, String.class, String.class, int.class});
        if (notEmpty(v)) return v;

        v = tryInvokeList(svc, "getCandles",
                new Object[]{chatId, type, exchange, network, symbol, timeframe, limit},
                new Class<?>[]{long.class, StrategyType.class, String.class, NetworkType.class, String.class, String.class, int.class});
        if (notEmpty(v)) return v;

        v = tryInvokeList(svc, "getCandles",
                new Object[]{chatId, type, symbol, timeframe, limit},
                new Class<?>[]{long.class, StrategyType.class, String.class, String.class, int.class});
        if (notEmpty(v)) return v;

        String[] hints = {"candle", "kline", "cache"};
        for (Method m : svc.getClass().getMethods()) {
            try {
                if (m.getParameterCount() < 2) continue;

                String name = m.getName().toLowerCase(Locale.ROOT);
                boolean okName = false;
                for (String h : hints) {
                    if (name.contains(h)) { okName = true; break; }
                }
                if (!okName) continue;

                if (!List.class.isAssignableFrom(m.getReturnType())
                        && !java.util.Collection.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }

                Object[] args = buildArgsFor(m.getParameterTypes(), chatId, type, exchange, network, symbol, timeframe, limit);
                if (args == null) continue;

                Object res = m.invoke(svc, args);
                if (res instanceof List<?> list && !list.isEmpty()) return list;
                if (res instanceof java.util.Collection<?> col && !col.isEmpty()) return new ArrayList<>(col);

            } catch (Exception ignore) {
            }
        }

        return List.of();
    }

    private Object[] buildArgsFor(Class<?>[] pts,
                                  long chatId,
                                  StrategyType type,
                                  String exchange,
                                  NetworkType network,
                                  String symbol,
                                  String timeframe,
                                  int limit) {

        if (pts == null) return null;

        String netName = network != null ? network.name() : null;
        String[] s1 = new String[]{symbol, timeframe, exchange, netName};
        String[] s2 = new String[]{exchange, netName, symbol, timeframe};
        String[] s3 = new String[]{symbol, exchange, timeframe, netName};
        String[][] variants = new String[][]{s1, s2, s3};

        for (String[] order : variants) {
            int si = 0;

            Object[] args = new Object[pts.length];
            boolean ok = true;

            for (int i = 0; i < pts.length; i++) {
                Class<?> p = pts[i];

                if (p == long.class || p == Long.class) { args[i] = chatId; continue; }
                if (p == int.class || p == Integer.class) { args[i] = limit; continue; }
                if (p == StrategyType.class) { args[i] = type; continue; }
                if (p == NetworkType.class) { args[i] = network; continue; }

                if (p == String.class) {
                    if (si >= order.length) { ok = false; break; }
                    args[i] = order[si++];
                    continue;
                }

                ok = false;
                break;
            }

            if (ok) return args;
        }

        return null;
    }

    private List<?> tryInvokeList(Object target, String method, Object[] args, Class<?>[] sig) {
        try {
            Method m = target.getClass().getMethod(method, sig);
            Object res = m.invoke(target, args);
            if (res instanceof List<?> l) return l;
            if (res instanceof java.util.Collection<?> c) return new ArrayList<>(c);
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean notEmpty(List<?> v) {
        return v != null && !v.isEmpty();
    }

    private static Long extractOpenTimeMs(Object c) {
        return asLong(readAny(c,
                "getOpenTimeMs", "openTimeMs",
                "getOpenTime", "openTime",
                "getStartTimeMs", "startTimeMs",
                "getStartTime", "startTime",
                "getTimeMs", "timeMs",
                "getTime", "time"
        ));
    }

    private static Long extractCloseTimeMs(Object c) {
        return asLong(readAny(c,
                "getCloseTimeMs", "closeTimeMs",
                "getCloseTime", "closeTime",
                "getEndTimeMs", "endTimeMs",
                "getEndTime", "endTime",
                "getT", "t"
        ));
    }

    private static long timeframeToMillis(String tf) {
        if (tf == null || tf.isBlank()) return TimeUnit.MINUTES.toMillis(1);

        String s = tf.trim().toLowerCase(Locale.ROOT);
        try {
            int n = Integer.parseInt(s.substring(0, s.length() - 1));
            char u = s.charAt(s.length() - 1);

            return switch (u) {
                case 'm' -> TimeUnit.MINUTES.toMillis(n);
                case 'h' -> TimeUnit.HOURS.toMillis(n);
                case 'd' -> TimeUnit.DAYS.toMillis(n);
                case 'w' -> TimeUnit.DAYS.toMillis(7L * n);
                default -> TimeUnit.MINUTES.toMillis(1);
            };
        } catch (Exception ignored) {
            return TimeUnit.MINUTES.toMillis(1);
        }
    }

    private static boolean isValidOhlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        if (open == null || high == null || low == null || close == null) return false;
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) return false;

        if (low.compareTo(high) > 0) return false;
        if (open.compareTo(low) < 0 || open.compareTo(high) > 0) return false;
        if (close.compareTo(low) < 0 || close.compareTo(high) > 0) return false;

        return true;
    }

    private static Object readAny(Object obj, String... gettersOrFields) {
        if (obj == null || gettersOrFields == null) return null;

        Class<?> c = obj.getClass();

        for (String n : gettersOrFields) {
            if (n == null || n.isBlank()) continue;

            try {
                Method m = c.getMethod(n);
                if (m.getParameterCount() == 0) {
                    Object v = m.invoke(obj);
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}

            try {
                var f = c.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return v;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignored) {}
        }
        if (v instanceof Instant inst) return inst.toEpochMilli();
        return null;
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (v instanceof String s) {
            String x = s.trim();
            if (x.isEmpty()) return null;
            try { return new BigDecimal(x.replace(",", ".")); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTf(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
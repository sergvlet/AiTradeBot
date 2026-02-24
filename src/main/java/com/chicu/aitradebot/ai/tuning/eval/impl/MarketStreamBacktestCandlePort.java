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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamBacktestCandlePort implements BacktestCandlePort {

    private final ObjectProvider<MarketDataStreamService> marketStream;

    // =====================================================
    // OLD SIGNATURE
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
    // NEW SIGNATURE
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
        symbol = normSymbol(symbol);
        timeframe = normTf(timeframe);
        if (symbol == null || timeframe == null) return List.of();
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return List.of();
        if (limit <= 0) return List.of();

        MarketDataStreamService svc = marketStream != null ? marketStream.getIfAvailable() : null;
        if (svc == null) return List.of();

        List<?> raw = tryGetCachedCandles(svc, chatId, type, exchange, network, symbol, timeframe, limit);
        if (raw == null || raw.isEmpty()) return List.of();

        long startMs = startAt.toEpochMilli();
        long endMs = endAt.toEpochMilli();

        List<CandleBar> out = new ArrayList<>(Math.min(raw.size(), limit));

        for (Object c : raw) {
            if (c == null) continue;

            Long tMs = asLong(readAny(c,
                    "getOpenTime", "openTime",
                    "getStartTime", "startTime",
                    "getTime", "time",
                    "getCloseTime", "closeTime",
                    "getTimestamp", "timestamp"
            ));
            if (tMs == null || tMs <= 0) continue;
            if (tMs < startMs || tMs > endMs) continue;

            BigDecimal open = asBigDecimal(readAny(c, "getOpen", "open"));
            BigDecimal high = asBigDecimal(readAny(c, "getHigh", "high"));
            BigDecimal low  = asBigDecimal(readAny(c, "getLow",  "low"));
            BigDecimal close= asBigDecimal(readAny(c, "getClose","close"));
            BigDecimal vol  = asBigDecimal(readAny(c, "getVolume","volume", "getVol", "vol"));

            if (open == null || high == null || low == null || close == null) continue;

            CandleBar bar = buildCandleBar(tMs, timeframe, open, high, low, close, vol);
            if (bar != null) out.add(bar);
        }

        if (out.isEmpty()) return List.of();

        // сортировка по времени (важно для бэктеста)
        out.sort(Comparator.comparingLong(this::extractTimeMsSafe));

        // ограничение
        if (out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }

        return out;
    }

    // =====================================================
    // CACHE GET (safe reflection)
    // =====================================================

    private List<?> tryGetCachedCandles(MarketDataStreamService svc,
                                        long chatId,
                                        StrategyType type,
                                        String exchange,
                                        NetworkType network,
                                        String symbol,
                                        String timeframe,
                                        int limit) {

        // 1) пробуем “ожидаемые” методы по именам + сигнатурам
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

        // 2) fallback: ищем ЛЮБОЙ публичный метод, который возвращает List/Collection и содержит candle/kline/cache
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
                // intentionally ignore
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

        // строки кладём в разных порядках (но типы не ломаем!)
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

                if (p == long.class || p == Long.class) {
                    args[i] = chatId;
                    continue;
                }
                if (p == int.class || p == Integer.class) {
                    args[i] = limit;
                    continue;
                }
                if (p == StrategyType.class) {
                    args[i] = type;
                    continue;
                }
                if (p == NetworkType.class) {
                    args[i] = network;
                    continue;
                }
                if (p == String.class) {
                    if (si >= order.length) { ok = false; break; }
                    args[i] = order[si++];
                    continue;
                }

                // неизвестный параметр — не трогаем этот метод
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

    // =====================================================
    // CandleBar build (reflection-safe)
    // =====================================================

    private CandleBar buildCandleBar(long timeMs,
                                     String timeframe,
                                     BigDecimal open,
                                     BigDecimal high,
                                     BigDecimal low,
                                     BigDecimal close,
                                     BigDecimal volume) {

        try {
            // 1) builder()
            Method builder = CandleBar.class.getMethod("builder");
            Object b = builder.invoke(null);

            call1(b, "time", timeMs);
            call1(b, "timeMs", timeMs);
            call1(b, "ts", timeMs);
            call1(b, "timestamp", timeMs);
            call1(b, "openTime", timeMs);
            call1(b, "openTimeMs", timeMs);

            call1(b, "timeframe", timeframe);
            call1(b, "tf", timeframe);

            call1(b, "open", open);
            call1(b, "high", high);
            call1(b, "low", low);
            call1(b, "close", close);
            call1(b, "volume", volume);

            Method build = b.getClass().getMethod("build");
            Object res = build.invoke(b);
            return (res instanceof CandleBar cb) ? cb : null;

        } catch (Exception ignoreBuilder) {
            // 2) constructors
            try {
                for (Constructor<?> c : CandleBar.class.getDeclaredConstructors()) {
                    Class<?>[] pts = c.getParameterTypes();
                    Object[] a = new Object[pts.length];

                    int bdIdx = 0;
                    BigDecimal[] bds = new BigDecimal[]{open, high, low, close, volume};

                    boolean ok = true;

                    for (int i = 0; i < pts.length; i++) {
                        Class<?> p = pts[i];

                        if (p == long.class || p == Long.class) { a[i] = timeMs; continue; }
                        if (p == Instant.class) { a[i] = Instant.ofEpochMilli(timeMs); continue; }
                        if (p == String.class) { a[i] = timeframe; continue; }

                        if (p == BigDecimal.class) {
                            a[i] = (bdIdx < bds.length) ? bds[bdIdx++] : null;
                            continue;
                        }

                        if (p == double.class || p == Double.class) {
                            double dv = (bdIdx < bds.length && bds[bdIdx] != null) ? bds[bdIdx].doubleValue() : 0.0;
                            bdIdx++;
                            a[i] = dv;
                            continue;
                        }

                        ok = false;
                        break;
                    }

                    if (!ok) continue;

                    c.setAccessible(true);
                    Object res = c.newInstance(a);
                    return (res instanceof CandleBar cb) ? cb : null;
                }
            } catch (Exception ignored) {
                // ignore
            }
        }

        return null;
    }

    private void call1(Object target, String name, Object arg) {
        if (target == null || name == null) return;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];

                Object coerced = coerce(arg, pt);
                if (coerced == null && pt.isPrimitive()) continue;

                m.invoke(target, coerced);
                return;
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private Object coerce(Object v, Class<?> pt) {
        if (pt == null) return null;
        if (v == null) return null;
        if (pt.isInstance(v)) return v;

        if (pt == long.class || pt == Long.class) return (v instanceof Number n) ? n.longValue() : null;
        if (pt == int.class || pt == Integer.class) return (v instanceof Number n) ? n.intValue() : null;

        if (pt == String.class) return String.valueOf(v);

        if (pt == BigDecimal.class) {
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception ignore) { return null; }
        }

        if (pt == double.class || pt == Double.class) {
            if (v instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignore) { return null; }
        }

        return null;
    }

    private long extractTimeMsSafe(CandleBar cb) {
        try {
            // пробуем типичные getter’ы
            Object v = tryCall0(cb, "getTime");
            if (v == null) v = tryCall0(cb, "getTimeMs");
            if (v == null) v = tryCall0(cb, "getTimestamp");
            if (v == null) v = tryCall0(cb, "getOpenTime");
            if (v instanceof Number n) return n.longValue();
        } catch (Exception ignore) {
            // ignore
        }
        return 0L;
    }

    private Object tryCall0(Object target, String m) {
        try {
            Method mm = target.getClass().getMethod(m);
            return mm.invoke(target);
        } catch (Exception ignore) {
            return null;
        }
    }

    // =====================================================
    // small utils
    // =====================================================

    private static String normSymbol(String s) {
        if (s == null) return null;
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String normTf(String s) {
        if (s == null) return null;
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static Object readAny(Object target, String... names) {
        if (target == null || names == null) return null;
        Class<?> c = target.getClass();

        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            try {
                Method m = c.getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignore) {
                // field-like name -> try getX()
                try {
                    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    Method m2 = c.getMethod("get" + cap);
                    return m2.invoke(target);
                } catch (Exception ignore2) {
                    // ignore
                }
            } catch (Exception ignore) {
                // ignore
            }
        }

        return null;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Instant it) return it.toEpochMilli();
        if (v instanceof java.util.Date d) return d.getTime();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
}
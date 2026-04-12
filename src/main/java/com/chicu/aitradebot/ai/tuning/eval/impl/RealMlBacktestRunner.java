package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реальный раннер бэктеста для ML/тюнинга.
 *
 * Если свечей недостаточно (кэш пустой/не прогрет) — делаем warmup через HistoryWarmupService
 * и повторяем backtest.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class RealMlBacktestRunner implements MlBacktestRunner {

    private final BacktestPort backtestPort;
    private final StrategySettingsService strategySettingsService;
    private final HistoryWarmupService warmupService;
    private final RealMlBacktestRunnerProperties props;

    /** key(chatId,type,ex,net,sym,tf) -> lastWarmupAtMs */
    private final Map<String, Long> lastWarmupAtMs = new ConcurrentHashMap<>();

    @Override
    public BacktestMetrics run(Long chatId,
                               StrategyType type,
                               String exchange,
                               NetworkType network,
                               String symbolOverride,
                               String timeframeOverride,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        if (chatId == null || chatId <= 0) return BacktestMetrics.fail("bad_chatId");
        if (type == null) return BacktestMetrics.fail("type_null");
        if (backtestPort == null) return BacktestMetrics.fail("backtestPort_null");
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return BacktestMetrics.fail("bad_range");

        Map<String, Object> params = (candidateParams != null) ? new HashMap<>(candidateParams) : new HashMap<>();

        StrategySettings ss = null;
        try {
            if (strategySettingsService != null) {
                ss = strategySettingsService.getOrCreate(chatId, type);
            }
        } catch (Exception ignored) {}

        String ex = normUpper(exchange);
        if (ex == null && ss != null) ex = normUpper(ss.getExchangeName());

        NetworkType net = (network != null) ? network : (ss != null ? ss.getNetworkType() : null);

        String sym = normUpper(symbolOverride);
        if (sym == null && ss != null) sym = normUpper(ss.getSymbol());

        String tf = normLower(timeframeOverride);
        if (tf == null && ss != null) tf = normLower(ss.getTimeframe());

        if (sym == null) return BacktestMetrics.fail("symbol_null");
        if (tf == null) return BacktestMetrics.fail("timeframe_null");

        boolean envOk = (ex != null && net != null);

        int candlesLimit = resolveCandlesLimit(params, ss);

        if (ex != null) params.putIfAbsent("exchange", ex);
        if (net != null) params.putIfAbsent("network", net.name());
        params.putIfAbsent("candlesLimit", candlesLimit);
        params.putIfAbsent("cachedCandlesLimit", candlesLimit);
        params.putIfAbsent("limit", candlesLimit);

        try {
            BacktestMetrics first = invokeBacktest(chatId, type, ex, net, sym, tf, params, startAt, endAt);
            if (first == null) return BacktestMetrics.fail("backtest_null_result");

            if (envOk && warmupService != null && shouldWarmup(first)) {
                int warmed = safeWarmup(chatId, type, ex, net, sym, tf, candlesLimit, startAt, endAt);
                if (warmed > 0) {
                    BacktestMetrics second = invokeBacktest(chatId, type, ex, net, sym, tf, params, startAt, endAt);
                    if (second != null) return second;
                }
            }

            return first;

        } catch (Exception e) {
            log.warn("[ML-BT] backtest failed chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, net, sym, tf, e.toString());
            return BacktestMetrics.fail("backtest_error: " + safeMsg(e));
        }
    }

    // =====================================================
    // WARMUP
    // =====================================================

    private int safeWarmup(long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol,
                           String timeframe,
                           int candlesLimit,
                           Instant startAt,
                           Instant endAt) {

        if (props == null) return 0;

        long ttlMs = props.getWarmupTtlMs();
        if (ttlMs > 0) {
            String k = warmupKey(chatId, type, exchange, network, symbol, timeframe);
            long now = System.currentTimeMillis();
            Long last = lastWarmupAtMs.get(k);
            if (last != null && (now - last) < ttlMs) return 0;
            lastWarmupAtMs.put(k, now);
        }

        int warmLimit = computeWarmupLimit(candlesLimit);
        long tfMs = timeframeToMillis(timeframe);

        long endMs = endAt.toEpochMilli();
        long startMs = startAt.toEpochMilli();

        long neededStart = endMs - (tfMs * (long) warmLimit);
        if (neededStart < 0) neededStart = 0;

        long warmStart = Math.min(startMs, neededStart);
        if (warmStart < 0) warmStart = 0;

        int got = warmupService.warmup(
                chatId,
                type,
                exchange,
                network,
                symbol,
                timeframe,
                warmStart,
                endMs,
                warmLimit
        );

        if (props.isLogWarmupInfo()) {
            log.info("[ML-BT] 🔥 warmup chatId={} type={} ex={} net={} {} {} start={} end={} limit={} candles={}",
                    chatId, type, exchange, network, symbol, timeframe,
                    Instant.ofEpochMilli(warmStart), endAt, warmLimit, got);
        }

        return got;
    }

    private int computeWarmupLimit(int candlesLimit) {
        int base = candlesLimit > 0 ? candlesLimit : (props != null ? props.safeDefaultCandlesLimit() : 900);

        double mul = props.safeWarmupMultiplier();
        int raw = (int) Math.round(base * mul);

        int min = props.safeWarmupMin();
        int max = props.safeWarmupMax();

        if (raw < min) raw = min;
        if (raw > max) raw = max;

        return raw;
    }

    private int resolveCandlesLimit(Map<String, Object> p, StrategySettings ss) {
        Integer a = tryInt(p != null ? p.get("cachedCandlesLimit") : null);
        if (a == null) a = tryInt(p != null ? p.get("candlesLimit") : null);
        if (a == null) a = tryInt(p != null ? p.get("limit") : null);

        if (a == null && ss != null && ss.getCachedCandlesLimit() != null) a = ss.getCachedCandlesLimit();
        if (a == null || a <= 0) a = (props != null ? props.safeDefaultCandlesLimit() : 900);

        int min = (props != null ? props.safeCandlesLimitMin() : 50);
        int max = (props != null ? props.safeCandlesLimitMax() : 20_000);

        if (a < min) a = min;
        if (a > max) a = max;

        return a;
    }

    private boolean shouldWarmup(BacktestMetrics m) {
        if (m == null) return false;

        String r = safeLower(m.getFailReason());
        if (r == null) r = safeLower(m.getReason());
        if (r == null) return false;

        return r.contains("not enough candles")
                || r.contains("not_enough_candles")
                || r.contains("no candles")
                || r.contains("empty")
                || (r.contains("candles") && (r.contains("not enough") || r.contains("0")));
    }

    private static String warmupKey(long chatId,
                                    StrategyType type,
                                    String exchange,
                                    NetworkType network,
                                    String symbol,
                                    String timeframe) {
        return chatId + ":" + type + ":" + exchange + ":" + network + ":" + symbol + ":" + timeframe;
    }

    private static long timeframeToMillis(String tf) {
        if (tf == null || tf.isBlank()) return 60_000L;

        String s = tf.trim().toLowerCase(Locale.ROOT);
        try {
            int n = Integer.parseInt(s.substring(0, s.length() - 1));
            char u = s.charAt(s.length() - 1);

            return switch (u) {
                case 'm' -> 60_000L * n;
                case 'h' -> 3_600_000L * n;
                case 'd' -> 86_400_000L * n;
                case 'w' -> 604_800_000L * n;
                default -> 60_000L;
            };
        } catch (Exception ignored) {
            return 60_000L;
        }
    }

    private static Integer tryInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) Math.min(Integer.MAX_VALUE, l);
        if (v instanceof Number n) return n.intValue();

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;

        try { return Integer.parseInt(s); } catch (Exception ignored) { return null; }
    }

    private static String safeLower(String s) {
        if (s == null) return null;
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    // =====================================================
    // reflection: поддержка разных сигнатур BacktestPort
    // =====================================================

    private BacktestMetrics invokeBacktest(Long chatId,
                                           StrategyType type,
                                           String exchange,
                                           NetworkType network,
                                           String symbolOverride,
                                           String timeframeOverride,
                                           Map<String, Object> candidateParams,
                                           Instant startAt,
                                           Instant endAt) throws Exception {

        for (Method m : backtestPort.getClass().getMethods()) {
            if (!"backtest".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();

            if (p.length == 7
                    && isLongType(p[0])
                    && p[1] == StrategyType.class
                    && p[2] == String.class
                    && p[3] == String.class
                    && Map.class.isAssignableFrom(p[4])
                    && p[5] == Instant.class
                    && p[6] == Instant.class) {

                Object[] args = new Object[]{
                        coerceLong(chatId, p[0]),
                        type,
                        symbolOverride,
                        timeframeOverride,
                        candidateParams,
                        startAt,
                        endAt
                };
                return (BacktestMetrics) m.invoke(backtestPort, args);
            }

            if (p.length == 9
                    && isLongType(p[0])
                    && p[1] == StrategyType.class
                    && p[2] == String.class
                    && p[3] == NetworkType.class
                    && p[4] == String.class
                    && p[5] == String.class
                    && Map.class.isAssignableFrom(p[6])
                    && p[7] == Instant.class
                    && p[8] == Instant.class) {

                Object[] args = new Object[]{
                        coerceLong(chatId, p[0]),
                        type,
                        exchange,
                        network,
                        symbolOverride,
                        timeframeOverride,
                        candidateParams,
                        startAt,
                        endAt
                };
                return (BacktestMetrics) m.invoke(backtestPort, args);
            }

            if (p.length == 8
                    && isLongType(p[0])
                    && p[1] == StrategyType.class
                    && p[2] == String.class
                    && p[3] == String.class
                    && p[4] == String.class
                    && Map.class.isAssignableFrom(p[5])
                    && p[6] == Instant.class
                    && p[7] == Instant.class) {

                Object[] args = new Object[]{
                        coerceLong(chatId, p[0]),
                        type,
                        exchange,
                        symbolOverride,
                        timeframeOverride,
                        candidateParams,
                        startAt,
                        endAt
                };
                return (BacktestMetrics) m.invoke(backtestPort, args);
            }
        }

        throw new NoSuchMethodException("No compatible BacktestPort.backtest(...) signature on " +
                backtestPort.getClass().getName());
    }

    private static boolean isLongType(Class<?> c) {
        return c == long.class || c == Long.class;
    }

    private static Object coerceLong(Long v, Class<?> target) {
        if (target == long.class) return v.longValue();
        return v;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String normLower(String s) {
        if (s == null) return null;
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }
}
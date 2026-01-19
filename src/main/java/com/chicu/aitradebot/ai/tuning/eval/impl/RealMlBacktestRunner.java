package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestService;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RealMlBacktestRunnerProperties.class)
public class RealMlBacktestRunner implements MlBacktestRunner {

    private final HistoryWarmupService warmupService;
    private final BacktestService backtestService;
    private final StrategyEnvResolver envResolver;
    private final RealMlBacktestRunnerProperties props;

    /**
     * key -> lastWarmupMs
     * ✅ анти-спам: если недавно прогревали — повторно не делаем
     */
    private final Map<String, Long> warmupTtlCache = new ConcurrentHashMap<>();

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

        // ------------------------------------------------------------
        // validate
        // ------------------------------------------------------------
        if (chatId == null) return BacktestMetrics.fail("chatId is null");
        if (type == null) return BacktestMetrics.fail("strategyType is null");

        String symbol = normalizeSymbol(symbolOverride);
        String tf     = normalizeTimeframe(timeframeOverride);

        if (symbol == null) return BacktestMetrics.fail("symbol is blank");
        if (tf == null)     return BacktestMetrics.fail("timeframe is blank");

        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            return BacktestMetrics.fail("invalid period");
        }

        // ------------------------------------------------------------
        // resolve env (no accidental MAINNET)
        // ------------------------------------------------------------
        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        if (ex == null || net == null) {
            StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
            if (ex == null) ex = normalizeExchangeOrNull(env.exchangeName());
            if (net == null) net = env.networkType();
        }

        if (ex == null)  return BacktestMetrics.fail("exchange is null");
        if (net == null) return BacktestMetrics.fail("network is null");

        // ------------------------------------------------------------
        // warmup limit (from props + candidateParams)
        // ------------------------------------------------------------
        int candlesLimit = resolveCandlesLimit(candidateParams);
        int warmupLimit  = computeWarmupLimit(candlesLimit);

        // ------------------------------------------------------------
        // ✅ WARMUP with TTL (key includes period + candlesLimit)
        // ------------------------------------------------------------
        String warmKey = buildWarmKey(ex, net, symbol, tf, candlesLimit, startAt, endAt);

        long now = System.currentTimeMillis();
        long ttlMs = props.getWarmupTtlMs();
        Long last = warmupTtlCache.get(warmKey);

        if (ttlMs <= 0 || last == null || (now - last) > ttlMs) {

            long startMs = startAt.toEpochMilli();
            long endMs   = endAt.toEpochMilli();

            int warmed = 0;
            try {
                warmed = warmupService.warmup(chatId, type, ex, net, symbol, tf, startMs, endMs, warmupLimit);
                warmupTtlCache.put(warmKey, now);

                if (props.isLogWarmupInfo()) {
                    log.info("🧪 ML WARMUP chatId={} type={} ex={} net={} sym={} tf={} warmed={} warmupLimit={} candlesLimit={} period={}..{}",
                            chatId, type, ex, net, symbol, tf, warmed, warmupLimit, candlesLimit, startAt, endAt);
                }
            } catch (Exception e) {
                // warmup не должен валить тюнинг/бэктест
                warmupTtlCache.put(warmKey, now); // ставим отметку, чтобы не спамить
                log.warn("🧪 ML WARMUP FAIL chatId={} type={} ex={} net={} sym={} tf={} err={}",
                        chatId, type, ex, net, symbol, tf, safeMsg(e));
            }

        } else {
            if (log.isDebugEnabled()) {
                log.debug("🧪 Warmup skipped by TTL={}ms key={}", ttlMs, warmKey);
            }
        }

        // ------------------------------------------------------------
        // ✅ Real backtest
        // ------------------------------------------------------------
        try {
            return backtestService.run(chatId, type, symbol, tf, candidateParams, startAt, endAt);
        } catch (Exception e) {
            log.warn("🧪 ML BACKTEST FAIL chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, net, symbol, tf, safeMsg(e));
            return BacktestMetrics.fail("Backtest error: " + safeMsg(e));
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private int resolveCandlesLimit(Map<String, Object> candidateParams) {
        if (candidateParams == null || candidateParams.isEmpty()) {
            return props.getDefaultCandlesLimit();
        }

        // поддержка нескольких ключей (на случай разных стратегий)
        Integer v = parseIntOrNull(candidateParams.get("cachedCandlesLimit"));
        if (v == null) v = parseIntOrNull(candidateParams.get("candlesLimit"));
        if (v == null) v = parseIntOrNull(candidateParams.get("limit"));

        if (v == null || v <= 0) return props.getDefaultCandlesLimit();

        // clamp (из props)
        int min = props.getCandlesLimitMin();
        int max = props.getCandlesLimitMax();
        int res = v;
        if (res < min) res = min;
        if (res > max) res = max;
        return res;
    }

    private int computeWarmupLimit(int candlesLimit) {
        // warmup = candlesLimit * multiplier, затем clamp
        double mult = props.getWarmupMultiplier();
        int v = (int) Math.ceil(Math.max(1, candlesLimit) * Math.max(0.1, mult));

        int min = props.getWarmupMin();
        int max = props.getWarmupMax();

        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    private static String buildWarmKey(String ex,
                                       NetworkType net,
                                       String symbol,
                                       String tf,
                                       int candlesLimit,
                                       Instant startAt,
                                       Instant endAt) {

        // ✅ ключ учитывает период и candlesLimit (чтобы при изменениях прогрев делался заново)
        return ex + "|" + net + "|" + symbol + "|" + tf + "|L=" + candlesLimit
               + "|S=" + startAt.toEpochMilli() + "|E=" + endAt.toEpochMilli();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeTimeframe(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static Integer parseIntOrNull(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }
}

package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class RealMlBacktestRunner implements MlBacktestRunner {

    private final BacktestPort backtestPort;

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

        String ex = normUpper(exchange);
        String sym = normUpper(symbolOverride);
        String tf = normLower(timeframeOverride);

        Map<String, Object> params = (candidateParams != null) ? new HashMap<>(candidateParams) : new HashMap<>();

        try {
            BacktestMetrics m = invokeBacktest(chatId, type, ex, network, sym, tf, params, startAt, endAt);
            if (m == null) return BacktestMetrics.fail("backtest_null_result");
            return m;
        } catch (Exception e) {
            log.warn("[ML-BT] backtest failed chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, ex, network, sym, tf, e.toString());
            return BacktestMetrics.fail("backtest_error: " + safeMsg(e));
        }
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

        // Ищем метод "backtest" у РЕАЛЬНОГО бина (не интерфейса) и подбираем сигнатуру.
        for (Method m : backtestPort.getClass().getMethods()) {
            if (!"backtest".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();

            // A) backtest(chatId, type, symbol, timeframe, params, startAt, endAt)  -> 7
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

            // B) backtest(chatId, type, exchange, network, symbol, timeframe, params, startAt, endAt) -> 9
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

            // C) backtest(chatId, type, exchange, symbol, timeframe, params, startAt, endAt) -> 8 (без network)
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
package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompositeBacktestCandlePort implements BacktestCandlePort {

    private final ObjectProvider<BacktestCandlePort> ports;

    // =====================================================
    // ✅ OLD SIGNATURE
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
    // ✅ NEW SIGNATURE (exchange/network)
    // =====================================================

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

        List<BacktestCandlePort> list = ports != null ? ports.orderedStream().toList() : List.of();
        if (list.isEmpty()) return List.of();

        List<String> errors = new ArrayList<>();

        for (BacktestCandlePort p : list) {
            if (p == null) continue;

            // ✅ защита от рекурсии
            if (p == this) continue;

            String sn = p.getClass().getSimpleName();
            if (CompositeBacktestCandlePort.class.getSimpleName().equals(sn)) continue;
            if ("HybridBacktestCandlePort".equals(sn)) continue; // ✅ цикл Hybrid <-> Composite

            try {
                List<CandleBar> out = invokePort(p, chatId, type, exchange, network, symbol, timeframe, startAt, endAt, limit);
                if (out != null && !out.isEmpty()) return out;
            } catch (Exception e) {
                errors.add(sn + ":" + e.getClass().getSimpleName());
                if (log.isDebugEnabled()) {
                    log.debug("CompositeBacktestCandlePort: delegate {} failed: {}", sn, e.getMessage());
                }
            }
        }

        if (!errors.isEmpty() && log.isDebugEnabled()) {
            log.debug("CompositeBacktestCandlePort: all delegates returned empty. errors={}", errors);
        }

        return List.of();
    }

    // =====================================================
    // invoke (compat): new signature -> fallback old signature
    // =====================================================

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
}
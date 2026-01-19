package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ai.tuning.eval.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestServiceImpl implements BacktestService {

    private final BacktestCandlePort candlePort;

    @Override
    public BacktestMetrics run(Long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               Map<String, Object> candidateParams,
                               Instant startAt,
                               Instant endAt) {

        if (symbol == null || symbol.isBlank()) return BacktestMetrics.fail("symbol is blank");
        if (timeframe == null || timeframe.isBlank()) return BacktestMetrics.fail("timeframe is blank");
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) return BacktestMetrics.fail("invalid period");

        // Сейчас делаем “прод-каркас”:
        // - свечи берём через порт
        // - считаем метрики
        // - позже ты просто подставишь реализацию candlePort (из БД/биржи)
        List<CandleBar> candles = candlePort.load(chatId, type, symbol, timeframe, startAt, endAt, 20_000);

        if (candles == null || candles.size() < 50) {
            return BacktestMetrics.fail("not enough candles: " + (candles == null ? 0 : candles.size()));
        }

        return switch (type) {
            case SCALPING -> runScalping(chatId, symbol, timeframe, candidateParams, startAt, endAt, candles);
            default -> BacktestMetrics.fail("unsupported strategy: " + type);
        };
    }

    // =====================================================================
    // SCALPING — базовый продовый симулятор (1 позиция, TP/SL, комиссия, DD)
    // =====================================================================
    private BacktestMetrics runScalping(Long chatId,
                                        String symbol,
                                        String timeframe,
                                        Map<String, Object> p,
                                        Instant startAt,
                                        Instant endAt,
                                        List<CandleBar> candles) {

        int window = intParam(p, "windowSize", 20, 2, 500);
        BigDecimal changeTh = bdParam(p, "priceChangeThreshold", new BigDecimal("0.002"), "0.00001", "0.50"); // 0.2%
        BigDecimal tpPct = bdParam(p, "takeProfitPct", new BigDecimal("0.40"), "0.01", "50"); // %
        BigDecimal slPct = bdParam(p, "stopLossPct", new BigDecimal("0.25"), "0.01", "50");   // %
        BigDecimal feePct = bdParam(p, "commissionPct", new BigDecimal("0.10"), "0.0", "1.0"); // %

        boolean inPos = false;
        BigDecimal entry = BigDecimal.ZERO;

        BigDecimal equity = BigDecimal.ONE;      // начинаем с 1.0 (нормированная кривая)
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDd = BigDecimal.ZERO;

        int trades = 0, wins = 0, losses = 0;

        MathContext mc = new MathContext(18, RoundingMode.HALF_UP);

        for (int i = window; i < candles.size(); i++) {
            CandleBar cur = candles.get(i);
            BigDecimal close = nz(cur.close());

            // ---------- вход ----------
            if (!inPos) {
                BigDecimal prev = nz(candles.get(i - window).close());
                if (prev.signum() > 0) {
                    // рост за окно
                    BigDecimal diff = close.subtract(prev, mc);
                    BigDecimal rel = diff.divide(prev, mc); // доля (0.002 = 0.2%)

                    if (rel.compareTo(changeTh) >= 0) {
                        inPos = true;
                        entry = close;
                    }
                }
                continue;
            }

            // ---------- выход по TP/SL ----------
            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = close.compareTo(tp) >= 0;
            boolean hitSl = close.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                BigDecimal gross = close.subtract(entry, mc).divide(entry, mc); // доля
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);      // доля
                BigDecimal net = gross.subtract(fee.multiply(new BigDecimal("2"), mc), mc); // buy+sell fee

                if (net.signum() >= 0) wins++; else losses++;

                equity = equity.multiply(BigDecimal.ONE.add(net, mc), mc);

                if (equity.compareTo(peak) > 0) peak = equity;

                BigDecimal ddNow = peak.subtract(equity, mc).divide(peak, mc); // доля
                if (ddNow.compareTo(maxDd) > 0) maxDd = ddNow;

                inPos = false;
                entry = BigDecimal.ZERO;
            }
        }

        BigDecimal profitPct = equity.subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));     // %
        BigDecimal ddPct = maxDd.multiply(new BigDecimal("100"));                                  // %

        BigDecimal winRate = trades == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf((wins * 100.0) / trades).setScale(6, RoundingMode.HALF_UP);

        return BacktestMetrics.builder()
                .ok(true)
                .reason("OK")
                .chatId(chatId)
                .type(StrategyType.SCALPING)
                .symbol(symbol)
                .timeframe(timeframe)
                .startAt(startAt)
                .endAt(endAt)
                .profitPct(profitPct.setScale(6, RoundingMode.HALF_UP))
                .maxDrawdownPct(ddPct.setScale(6, RoundingMode.HALF_UP))
                .trades(trades)
                .wins(wins)
                .losses(losses)
                .winRatePct(winRate)
                .params(p)
                .build();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static int intParam(Map<String, Object> p, String key, int def, int min, int max) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        try {
            int x = (v instanceof Number n) ? n.intValue() : Integer.parseInt(v.toString().trim());
            if (x < min) return min;
            if (x > max) return max;
            return x;
        } catch (Exception ignored) {
            return def;
        }
    }

    private static BigDecimal bdParam(Map<String, Object> p, String key, BigDecimal def, String min, String max) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        try {
            BigDecimal x = (v instanceof BigDecimal bd) ? bd : new BigDecimal(v.toString().trim());
            BigDecimal mn = new BigDecimal(min);
            BigDecimal mx = new BigDecimal(max);
            if (x.compareTo(mn) < 0) return mn;
            if (x.compareTo(mx) > 0) return mx;
            return x;
        } catch (Exception ignored) {
            return def;
        }
    }
}

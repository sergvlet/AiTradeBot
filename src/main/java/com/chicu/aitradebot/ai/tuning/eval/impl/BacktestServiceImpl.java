package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.*;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

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

        List<CandleBar> candles = candlePort.load(chatId, type, symbol, timeframe, startAt, endAt, 20_000);
        if (candles == null || candles.size() < 50) {
            return BacktestMetrics.fail("not enough candles: " + (candles == null ? 0 : candles.size()));
        }

        return switch (type) {
            case SCALPING -> runScalping(chatId, symbol, timeframe, candidateParams, startAt, endAt, candles);
            case WINDOW_SCALPING -> runWindowScalping(chatId, symbol, timeframe, candidateParams, startAt, endAt, candles);
            default -> BacktestMetrics.fail("unsupported strategy: " + type);
        };
    }

    // =====================================================================
    // COMMON: Risk/Trade controls (из candidateParams, которые приходят из StrategySettings)
    // =====================================================================

    private static class RiskTradeCfg {
        BigDecimal riskPerTradePct;     // %
        BigDecimal minRiskReward;       // RR
        int leverage;                   // >=1
        boolean allowAveraging;         // true/false
        Integer cooldownSeconds;        // seconds
        Integer cooldownAfterLossSeconds;
        Integer maxConsecutiveLosses;
        BigDecimal maxDrawdownPct;      // %
        BigDecimal maxPositionPct;      // %
        Integer maxTradesPerDay;
        Integer maxOpenOrders;

        static RiskTradeCfg from(Map<String, Object> p) {
            RiskTradeCfg c = new RiskTradeCfg();
            c.riskPerTradePct = bdParam(p, "riskPerTradePct", new BigDecimal("1.0"), "0.01", "20.0");
            c.minRiskReward   = bdParam(p, "minRiskReward", new BigDecimal("1.2"), "0.1", "10.0");
            c.leverage        = intParam(p, "leverage", 1, 1, 50);
            c.allowAveraging  = boolParam(p, "allowAveraging", false);

            c.cooldownSeconds          = intNullablePositive(p, "cooldownSeconds", 0, 0, 86_400);
            c.cooldownAfterLossSeconds = intNullablePositive(p, "cooldownAfterLossSeconds", 0, 0, 86_400);
            c.maxConsecutiveLosses     = intNullablePositive(p, "maxConsecutiveLosses", 0, 0, 100);

            c.maxDrawdownPct = bdNullablePct(p, "maxDrawdownPct");
            c.maxPositionPct = bdNullablePct(p, "maxPositionPct");

            c.maxTradesPerDay = intNullablePositive(p, "maxTradesPerDay", 0, 0, 10_000);
            c.maxOpenOrders   = intNullablePositive(p, "maxOpenOrders", 1, 1, 10_000);
            return c;
        }
    }

    private static int candlesPerDay(String timeframe) {
        // минимально достаточно для ограничения maxTradesPerDay
        String tf = (timeframe == null ? "" : timeframe.trim().toLowerCase(Locale.ROOT));
        if (tf.endsWith("m")) {
            int m = safeParseInt(tf.substring(0, tf.length() - 1), 1);
            return Math.max(1, 1440 / Math.max(1, m));
        }
        if (tf.endsWith("h")) {
            int h = safeParseInt(tf.substring(0, tf.length() - 1), 1);
            return Math.max(1, 24 / Math.max(1, h));
        }
        if (tf.endsWith("d")) return 1;
        return 1440; // fallback как 1m
    }

    private static int dayIndex(Instant startAt, Instant ts) {
        long sec = Math.max(0, ts.getEpochSecond() - startAt.getEpochSecond());
        return (int) (sec / 86_400L);
    }

    private static boolean rrOk(BigDecimal tpPct, BigDecimal slPct, BigDecimal minRR, MathContext mc) {
        if (tpPct == null || slPct == null || minRR == null) return true;
        if (slPct.signum() <= 0) return true;
        BigDecimal rr = tpPct.divide(slPct, mc);
        return rr.compareTo(minRR) >= 0;
    }

    // =====================================================================
    // SCALPING (оставил, но добавил risk/trade scaling и лимиты)
    // =====================================================================
    private BacktestMetrics runScalping(Long chatId,
                                        String symbol,
                                        String timeframe,
                                        Map<String, Object> p,
                                        Instant startAt,
                                        Instant endAt,
                                        List<CandleBar> candles) {

        int window = intParam(p, "windowSize", 20, 2, 500);
        BigDecimal changeTh = bdParam(p, "priceChangeThreshold", new BigDecimal("0.002"), "0.00001", "0.50"); // доля
        BigDecimal tpPct = bdParam(p, "takeProfitPct", new BigDecimal("0.40"), "0.01", "50"); // %
        BigDecimal slPct = bdParam(p, "stopLossPct", new BigDecimal("0.25"), "0.01", "50");   // %
        BigDecimal feePct = bdParam(p, "commissionPct", new BigDecimal("0.10"), "0.0", "1.0"); // %

        RiskTradeCfg rt = RiskTradeCfg.from(p);

        MathContext mc = new MathContext(18, RoundingMode.HALF_UP);

        if (!rrOk(tpPct, slPct, rt.minRiskReward, mc)) {
            return BacktestMetrics.builder()
                    .ok(true)
                    .reason("RR filtered by minRiskReward")
                    .chatId(chatId)
                    .type(StrategyType.SCALPING)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .startAt(startAt)
                    .endAt(endAt)
                    .profitPct(BigDecimal.ZERO)
                    .maxDrawdownPct(BigDecimal.ZERO)
                    .trades(0)
                    .wins(0)
                    .losses(0)
                    .winRatePct(BigDecimal.ZERO)
                    .params(p)
                    .build();
        }

        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDd = BigDecimal.ZERO;

        boolean inPos = false;
        BigDecimal entry = BigDecimal.ZERO;

        int trades = 0, wins = 0, losses = 0;
        int consecLoss = 0;
        long nextEntryAllowedSec = startAt.getEpochSecond();

        int perDay = candlesPerDay(timeframe);
        Map<Integer, Integer> tradesPerDay = new HashMap<>();

        for (int i = window; i < candles.size(); i++) {
            CandleBar cur = candles.get(i);
            BigDecimal close = nz(cur.close());
            Instant ts = cur.openTime();

            if (ts == null) continue;

            // лимиты входа
            if (!inPos) {
                if (ts.getEpochSecond() < nextEntryAllowedSec) continue;

                Integer mtd = rt.maxTradesPerDay;
                if (mtd != null && mtd > 0) {
                    int d = dayIndex(startAt, ts);
                    int cnt = tradesPerDay.getOrDefault(d, 0);
                    if (cnt >= mtd) continue;
                }

                if (rt.maxConsecutiveLosses != null && rt.maxConsecutiveLosses > 0) {
                    if (consecLoss >= rt.maxConsecutiveLosses) continue;
                }

                BigDecimal prev = nz(candles.get(i - window).close());
                if (prev.signum() > 0) {
                    BigDecimal rel = close.subtract(prev, mc).divide(prev, mc);
                    if (rel.compareTo(changeTh) >= 0) {
                        inPos = true;
                        entry = close;
                    }
                }
                continue;
            }

            // выход
            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = close.compareTo(tp) >= 0;
            boolean hitSl = close.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                BigDecimal gross = close.subtract(entry, mc).divide(entry, mc); // доля
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);      // доля
                BigDecimal net = gross.subtract(fee.multiply(new BigDecimal("2"), mc), mc); // buy+sell

                // риск/позиция/плечо
                BigDecimal pos = rt.riskPerTradePct.divide(new BigDecimal("100"), mc);
                if (rt.maxPositionPct != null) {
                    BigDecimal cap = rt.maxPositionPct.divide(new BigDecimal("100"), mc);
                    if (cap.compareTo(BigDecimal.ZERO) > 0) pos = pos.min(cap);
                }
                pos = pos.max(BigDecimal.ZERO).min(BigDecimal.ONE);

                BigDecimal lev = BigDecimal.valueOf(Math.max(1, rt.leverage));
                BigDecimal scaled = net.multiply(pos, mc).multiply(lev, mc);

                equity = equity.multiply(BigDecimal.ONE.add(scaled, mc), mc);

                if (scaled.signum() >= 0) {
                    wins++;
                    consecLoss = 0;
                } else {
                    losses++;
                    consecLoss++;
                }

                if (equity.compareTo(peak) > 0) peak = equity;
                BigDecimal ddNow = peak.subtract(equity, mc).divide(peak, mc);
                if (ddNow.compareTo(maxDd) > 0) maxDd = ddNow;

                // cooldown
                long cd = (rt.cooldownSeconds == null ? 0 : rt.cooldownSeconds);
                long afterLoss = (rt.cooldownAfterLossSeconds == null ? 0 : rt.cooldownAfterLossSeconds);
                long add = (scaled.signum() < 0 ? Math.max(cd, afterLoss) : cd);
                nextEntryAllowedSec = ts.getEpochSecond() + add;

                // maxTradesPerDay счетчик
                if (rt.maxTradesPerDay != null && rt.maxTradesPerDay > 0) {
                    int d = dayIndex(startAt, ts);
                    tradesPerDay.put(d, tradesPerDay.getOrDefault(d, 0) + 1);
                }

                inPos = false;
                entry = BigDecimal.ZERO;
            }
        }

        BigDecimal profitPct = equity.subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
        BigDecimal ddPct = maxDd.multiply(new BigDecimal("100"));

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
    // WINDOW_SCALPING — симулятор с окном high/low + зоны входа + TP/SL по range
    // + risk/trade лимиты
    // =====================================================================
    private BacktestMetrics runWindowScalping(Long chatId,
                                              String symbol,
                                              String timeframe,
                                              Map<String, Object> p,
                                              Instant startAt,
                                              Instant endAt,
                                              List<CandleBar> candles) {

        int window = intParam(p, "windowSize", 30, 5, 2000);
        BigDecimal entryLowPct = bdParam(p, "entryFromLowPct", new BigDecimal("20"), "0", "100");
        BigDecimal entryHighPct = bdParam(p, "entryFromHighPct", new BigDecimal("20"), "0", "100");
        BigDecimal minRangePct = bdParam(p, "minRangePct", new BigDecimal("0.25"), "0.01", "50"); // % диапазона
        BigDecimal maxSpreadPct = bdParam(p, "maxSpreadPct", new BigDecimal("0.08"), "0.0", "50"); // %

        BigDecimal tpPct = bdParam(p, "takeProfitPct", new BigDecimal("0.40"), "0.01", "50"); // %
        BigDecimal slPct = bdParam(p, "stopLossPct", new BigDecimal("0.25"), "0.01", "50");   // %
        BigDecimal feePct = bdParam(p, "commissionPct", new BigDecimal("0.10"), "0.0", "1.0"); // %

        RiskTradeCfg rt = RiskTradeCfg.from(p);
        MathContext mc = new MathContext(18, RoundingMode.HALF_UP);

        if (!rrOk(tpPct, slPct, rt.minRiskReward, mc)) {
            return BacktestMetrics.builder()
                    .ok(true)
                    .reason("RR filtered by minRiskReward")
                    .chatId(chatId)
                    .type(StrategyType.WINDOW_SCALPING)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .startAt(startAt)
                    .endAt(endAt)
                    .profitPct(BigDecimal.ZERO)
                    .maxDrawdownPct(BigDecimal.ZERO)
                    .trades(0)
                    .wins(0)
                    .losses(0)
                    .winRatePct(BigDecimal.ZERO)
                    .params(p)
                    .build();
        }

        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDd = BigDecimal.ZERO;

        boolean inPos = false;
        BigDecimal entry = BigDecimal.ZERO;
        int posUnits = 0; // 1 или 2 при averaging
        boolean averaged = false;

        int trades = 0, wins = 0, losses = 0;
        int consecLoss = 0;

        long nextEntryAllowedSec = startAt.getEpochSecond();
        Map<Integer, Integer> tradesPerDay = new HashMap<>();

        for (int i = window; i < candles.size(); i++) {
            CandleBar cur = candles.get(i);
            Instant ts = cur.openTime();

            if (ts == null) continue;

            BigDecimal close = nz(cur.close());
            BigDecimal highW = BigDecimal.ZERO;
            BigDecimal lowW = null;

            // окно
            for (int j = i - window + 1; j <= i; j++) {
                CandleBar b = candles.get(j);
                BigDecimal h = nz(b.high());
                BigDecimal l = nz(b.low());
                if (h.compareTo(highW) > 0) highW = h;
                if (lowW == null || (l.signum() > 0 && l.compareTo(lowW) < 0)) lowW = l;
            }
            if (lowW == null || lowW.signum() <= 0) continue;

            BigDecimal range = highW.subtract(lowW, mc);
            if (range.signum() <= 0) continue;

            BigDecimal rangePct = range.divide(lowW, mc).multiply(new BigDecimal("100"), mc);
            if (rangePct.compareTo(minRangePct) < 0) continue;

            // spread (приближение): (high-low)/close
            BigDecimal spreadPct = range.divide(close.max(BigDecimal.ONE), mc).multiply(new BigDecimal("100"), mc);
            if (spreadPct.compareTo(maxSpreadPct) > 0) continue;

            // лимиты входа
            if (!inPos) {
                if (ts.getEpochSecond() < nextEntryAllowedSec) continue;

                Integer mtd = rt.maxTradesPerDay;
                if (mtd != null && mtd > 0) {
                    int d = dayIndex(startAt, ts);
                    if (tradesPerDay.getOrDefault(d, 0) >= mtd) continue;
                }

                if (rt.maxConsecutiveLosses != null && rt.maxConsecutiveLosses > 0) {
                    if (consecLoss >= rt.maxConsecutiveLosses) continue;
                }

                // зоны входа: нижние entryFromLowPct% диапазона
                BigDecimal lowZoneTop = lowW.add(range.multiply(entryLowPct.divide(new BigDecimal("100"), mc), mc), mc);

                if (close.compareTo(lowZoneTop) <= 0) {
                    inPos = true;
                    entry = close;
                    posUnits = 1;
                    averaged = false;
                }
                continue;
            }

            // averaging (очень аккуратно, 1 раз, только если разрешено и maxOpenOrders >=2)
            if (!averaged && rt.allowAveraging && (rt.maxOpenOrders == null || rt.maxOpenOrders >= 2)) {
                BigDecimal slLine = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);
                BigDecimal trigger = entry.subtract(entry.subtract(slLine, mc).divide(new BigDecimal("2"), mc), mc);
                if (close.compareTo(trigger) <= 0) {
                    // добавляем второй вход по текущей цене
                    BigDecimal newEntry = entry.multiply(new BigDecimal(posUnits), mc)
                            .add(close, mc)
                            .divide(new BigDecimal(posUnits + 1), mc);

                    entry = newEntry;
                    posUnits++;
                    averaged = true;
                }
            }

            // выход по TP/SL
            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = close.compareTo(tp) >= 0;
            boolean hitSl = close.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                BigDecimal gross = close.subtract(entry, mc).divide(entry, mc);
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);
                BigDecimal net = gross.subtract(fee.multiply(new BigDecimal("2"), mc), mc);

                // риск/позиция/плечо
                BigDecimal pos = rt.riskPerTradePct.divide(new BigDecimal("100"), mc);
                if (rt.maxPositionPct != null) {
                    BigDecimal cap = rt.maxPositionPct.divide(new BigDecimal("100"), mc);
                    if (cap.compareTo(BigDecimal.ZERO) > 0) pos = pos.min(cap);
                }
                pos = pos.max(BigDecimal.ZERO).min(BigDecimal.ONE);

                BigDecimal lev = BigDecimal.valueOf(Math.max(1, rt.leverage));
                BigDecimal scaled = net.multiply(pos, mc).multiply(lev, mc).multiply(new BigDecimal(posUnits), mc);

                equity = equity.multiply(BigDecimal.ONE.add(scaled, mc), mc);

                if (scaled.signum() >= 0) {
                    wins++;
                    consecLoss = 0;
                } else {
                    losses++;
                    consecLoss++;
                }

                if (equity.compareTo(peak) > 0) peak = equity;
                BigDecimal ddNow = peak.subtract(equity, mc).divide(peak, mc);
                if (ddNow.compareTo(maxDd) > 0) maxDd = ddNow;

                long cd = (rt.cooldownSeconds == null ? 0 : rt.cooldownSeconds);
                long afterLoss = (rt.cooldownAfterLossSeconds == null ? 0 : rt.cooldownAfterLossSeconds);
                long add = (scaled.signum() < 0 ? Math.max(cd, afterLoss) : cd);
                nextEntryAllowedSec = ts.getEpochSecond() + add;

                if (rt.maxTradesPerDay != null && rt.maxTradesPerDay > 0) {
                    int d = dayIndex(startAt, ts);
                    tradesPerDay.put(d, tradesPerDay.getOrDefault(d, 0) + 1);
                }

                inPos = false;
                entry = BigDecimal.ZERO;
                posUnits = 0;
                averaged = false;
            }

            // мягкий стоп по DD лимиту (если задан)
            if (rt.maxDrawdownPct != null) {
                BigDecimal ddPct = maxDd.multiply(new BigDecimal("100"), mc);
                if (ddPct.compareTo(rt.maxDrawdownPct) > 0) {
                    break;
                }
            }
        }

        BigDecimal profitPct = equity.subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
        BigDecimal ddPct = maxDd.multiply(new BigDecimal("100"));

        BigDecimal winRate = trades == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf((wins * 100.0) / trades).setScale(6, RoundingMode.HALF_UP);

        String reason = "OK";
        if (rt.maxDrawdownPct != null && ddPct.compareTo(rt.maxDrawdownPct) > 0) {
            reason = "DD_LIMIT_REACHED";
        }

        return BacktestMetrics.builder()
                .ok(true)
                .reason(reason)
                .chatId(chatId)
                .type(StrategyType.WINDOW_SCALPING)
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

    private static boolean boolParam(Map<String, Object> p, String key, boolean def) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static int safeParseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static Integer intNullablePositive(Map<String, Object> p, String key, int def, int min, int max) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        try {
            int x = (v instanceof Number n) ? n.intValue() : Integer.parseInt(v.toString().trim());
            if (x < min) x = min;
            if (x > max) x = max;
            if (x <= 0) return null;
            return x;
        } catch (Exception ignored) {
            return def <= 0 ? null : def;
        }
    }

    private static BigDecimal bdNullablePct(Map<String, Object> p, String key) {
        if (p == null) return null;
        Object v = p.get(key);
        if (v == null) return null;
        try {
            BigDecimal x = (v instanceof BigDecimal bd) ? bd : new BigDecimal(v.toString().trim().replace(",", "."));
            if (x.compareTo(BigDecimal.ZERO) < 0) x = BigDecimal.ZERO;
            if (x.compareTo(BigDecimal.valueOf(100)) > 0) x = BigDecimal.valueOf(100);
            return x.setScale(6, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return null;
        }
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
            BigDecimal x = (v instanceof BigDecimal bd) ? bd : new BigDecimal(v.toString().trim().replace(",", "."));
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

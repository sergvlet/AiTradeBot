package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ml.tuning.eval.CandleBar;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public final class ScalpingBacktestSimulator {

    private ScalpingBacktestSimulator() {}

    public static BacktestMetrics run(long chatId,
                                      StrategySettings settings,
                                      String symbol,
                                      String timeframe,
                                      Instant startAt,
                                      Instant endAt,
                                      Map<String, Object> candidateParams,
                                      List<CandleBar> candles) {

        if (candles == null || candles.size() < 5) {
            return BacktestMetrics.fail("Not enough candles for backtest");
        }
        if (settings == null) {
            return BacktestMetrics.fail("StrategySettings is null");
        }

        // =====================================================
        // ✅ ВАЖНО:
        // - capitalUsd/tp/sl/commission больше НЕ хранятся в StrategySettings
        // - initialEquity берём из maxExposureUsd (бюджет стратегии) или из candidateParams.initialEquityUsd
        // - tp/sl/commission должны приходить из candidateParams (их потом подставит вызывающий код из БД/биржи)
        // =====================================================

        BigDecimal initialEquity = resolveInitialEquity(settings, candidateParams);
        if (initialEquity.signum() <= 0) {
            return BacktestMetrics.fail(
                    "Initial equity is missing. Provide settings.maxExposureUsd (budget) or candidateParams.initialEquityUsd"
            );
        }

        BigDecimal tpPct = bigParamRequired(candidateParams, "takeProfitPct");
        BigDecimal slPct = bigParamRequired(candidateParams, "stopLossPct");
        BigDecimal commissionPct = bigParamRequired(candidateParams, "commissionPct");

        double tpFrac = pctToFrac(tpPct);
        double slFrac = pctToFrac(slPct);
        double commissionFrac = pctToFrac(commissionPct);

        int windowSize = intParam(candidateParams, "windowSize", 0);
        if (windowSize <= 0) {
            return BacktestMetrics.fail("candidate param windowSize is missing/invalid");
        }

        double thresholdPct = doubleParam(candidateParams, "priceChangeThreshold", -1d);
        if (thresholdPct <= 0) {
            return BacktestMetrics.fail("candidate param priceChangeThreshold is missing/invalid");
        }

        Integer cooldown = settings.getCooldownSeconds();
        long cooldownSec = cooldown != null ? Math.max(0, cooldown) : 0;

        BigDecimal equity = initialEquity;
        BigDecimal peakEquity = initialEquity;
        BigDecimal maxDdPct = BigDecimal.ZERO;

        int trades = 0;
        int wins = 0;
        int losses = 0;

        Deque<BigDecimal> window = new ArrayDeque<>(windowSize + 2);

        boolean inPos = false;
        boolean isLong = false;
        BigDecimal entryPrice = null;
        BigDecimal qty = null;
        BigDecimal tp = null;
        BigDecimal sl = null;

        Instant lastClosedAt = null;

        for (int i = 0; i < candles.size(); i++) {
            CandleBar bar = candles.get(i);

            Instant barTs = extractTime(bar, startAt, i, timeframe);

            BigDecimal close = nz(bar.close());
            if (close.signum() <= 0) continue;

            // mark-to-market для drawdown
            BigDecimal marked = equity;
            if (inPos && entryPrice != null && qty != null) {
                BigDecimal pnl = isLong
                        ? close.subtract(entryPrice).multiply(qty)
                        : entryPrice.subtract(close).multiply(qty);
                marked = equity.add(pnl);
            }

            if (marked.compareTo(peakEquity) > 0) peakEquity = marked;

            if (peakEquity.signum() > 0) {
                BigDecimal dd = peakEquity.subtract(marked)
                        .divide(peakEquity, 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                if (dd.compareTo(maxDdPct) > 0) maxDdPct = dd;
            }

            // =====================================================
            // EXIT (high/low)
            // =====================================================
            if (inPos && qty != null && tp != null && sl != null && entryPrice != null) {
                BigDecimal high = nz(bar.high());
                BigDecimal low = nz(bar.low());

                boolean hitTp = isLong ? high.compareTo(tp) >= 0 : low.compareTo(tp) <= 0;
                boolean hitSl = isLong ? low.compareTo(sl) <= 0 : high.compareTo(sl) >= 0;

                if (hitTp || hitSl) {
                    BigDecimal exitPrice;

                    if (hitTp && hitSl) {
                        // консервативно: считаем, что сработал SL
                        exitPrice = sl;
                    } else if (hitTp) {
                        exitPrice = tp;
                    } else {
                        exitPrice = sl;
                    }

                    BigDecimal tradeNotionalIn = entryPrice.multiply(qty);
                    BigDecimal tradeNotionalOut = exitPrice.multiply(qty);

                    BigDecimal pnl = isLong
                            ? exitPrice.subtract(entryPrice).multiply(qty)
                            : entryPrice.subtract(exitPrice).multiply(qty);

                    BigDecimal fee = tradeNotionalIn.multiply(BigDecimal.valueOf(commissionFrac))
                            .add(tradeNotionalOut.multiply(BigDecimal.valueOf(commissionFrac)));

                    equity = equity.add(pnl).subtract(fee);

                    trades++;
                    if (pnl.subtract(fee).signum() >= 0) wins++; else losses++;

                    inPos = false;
                    entryPrice = null;
                    qty = null;
                    tp = null;
                    sl = null;
                    lastClosedAt = barTs;

                    window.clear();
                    continue;
                }
            }

            // =====================================================
            // ENTRY (diffPct по окну close)
            // =====================================================
            window.addLast(close);
            while (window.size() > windowSize) window.removeFirst();
            if (window.size() < windowSize) continue;

            if (!inPos) {
                if (cooldownSec > 0 && lastClosedAt != null) {
                    long passed = Math.max(0, barTs.getEpochSecond() - lastClosedAt.getEpochSecond());
                    if (passed < cooldownSec) {
                        continue;
                    }
                }

                BigDecimal first = window.getFirst();
                BigDecimal last = window.getLast();
                if (first.signum() <= 0) continue;

                double diffPct = last.subtract(first)
                                         .divide(first, 10, RoundingMode.HALF_UP)
                                         .doubleValue() * 100.0;

                if (Math.abs(diffPct) >= thresholdPct) {
                    isLong = diffPct > 0;
                    entryPrice = close;

                    BigDecimal tradeAmount = resolveTradeAmount(settings, equity);
                    if (tradeAmount.signum() <= 0) continue;

                    qty = tradeAmount.divide(entryPrice, 10, RoundingMode.DOWN);
                    if (qty.signum() <= 0) continue;

                    tp = isLong
                            ? entryPrice.multiply(BigDecimal.valueOf(1 + tpFrac))
                            : entryPrice.multiply(BigDecimal.valueOf(1 - tpFrac));

                    sl = isLong
                            ? entryPrice.multiply(BigDecimal.valueOf(1 - slFrac))
                            : entryPrice.multiply(BigDecimal.valueOf(1 + slFrac));

                    // комиссия входа
                    BigDecimal feeIn = entryPrice.multiply(qty).multiply(BigDecimal.valueOf(commissionFrac));
                    equity = equity.subtract(feeIn);

                    inPos = true;
                    window.clear();
                }
            }
        }

        BigDecimal profitPct = equity.subtract(initialEquity)
                .divide(initialEquity, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal winRatePct = trades > 0
                ? BigDecimal.valueOf((wins * 100.0) / trades).setScale(6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return BacktestMetrics.builder()
                .ok(true)
                .reason("OK")
                .chatId(chatId)
                .type(settings.getType())
                .symbol(symbol)
                .timeframe(timeframe)
                .startAt(startAt)
                .endAt(endAt)
                .profitPct(profitPct.setScale(6, RoundingMode.HALF_UP))
                .maxDrawdownPct(maxDdPct.setScale(6, RoundingMode.HALF_UP))
                .trades(trades)
                .wins(wins)
                .losses(losses)
                .winRatePct(winRatePct)
                .build();
    }

    // =====================================================
    // ✅ initial equity: budget from settings OR explicit param
    // =====================================================

    private static BigDecimal resolveInitialEquity(StrategySettings s, Map<String, Object> p) {
        // 1) бюджет стратегии в USD — самое логичное для backtest симулятора
        BigDecimal budgetUsd = nz(s.getMaxExposureUsd());
        if (budgetUsd.signum() > 0) return budgetUsd;

        // 2) если бюджет не задан — требуем, чтобы вызывающий код передал equity явно
        BigDecimal equityUsd = bigParam(p, "initialEquityUsd", null);
        if (equityUsd != null && equityUsd.signum() > 0) return equityUsd;

        // 3) поддержка старого поля (если где-то ещё осталось в ветке) через reflection без зависимости компиляции
        BigDecimal legacyCapital = tryBigDecimal(s, "getCapitalUsd");
        if (legacyCapital != null && legacyCapital.signum() > 0) return legacyCapital;

        return BigDecimal.ZERO;
    }

    // ---------------- time extraction (адаптер под твой CandleBar) ----------------

    private static Instant extractTime(CandleBar bar, Instant startAt, int idx, String timeframe) {
        Long ms = tryLong(bar, "timeMs")
                .or(() -> tryLong(bar, "timestamp"))
                .or(() -> tryLong(bar, "ts"))
                .or(() -> tryLong(bar, "openTime"))
                .or(() -> tryLong(bar, "openTs"))
                .orElse(null);

        if (ms != null && ms > 0) return Instant.ofEpochMilli(ms);

        Instant inst = tryInstant(bar, "time")
                .or(() -> tryInstant(bar, "timestamp"))
                .or(() -> tryInstant(bar, "openAt"))
                .or(() -> tryInstant(bar, "openTime"))
                .orElse(null);

        if (inst != null) return inst;

        long stepSec = timeframeToSec(timeframe);
        return startAt != null
                ? startAt.plusSeconds(stepSec * (long) idx)
                : Instant.EPOCH.plusSeconds(stepSec * (long) idx);
    }

    private static java.util.Optional<Long> tryLong(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v == null) return java.util.Optional.empty();
            if (v instanceof Long l) return java.util.Optional.of(l);
            if (v instanceof Integer i) return java.util.Optional.of(i.longValue());
            if (v instanceof BigDecimal bd) return java.util.Optional.of(bd.longValue());
            if (v instanceof String s) return java.util.Optional.of(Long.parseLong(s.trim()));
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Instant> tryInstant(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v instanceof Instant inst) return java.util.Optional.of(inst);
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private static long timeframeToSec(String tf) {
        if (tf == null) return 60;
        String s = tf.trim().toLowerCase();
        try {
            if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1)) * 60L;
            if (s.endsWith("h")) return Long.parseLong(s.substring(0, s.length() - 1)) * 3600L;
            if (s.endsWith("d")) return Long.parseLong(s.substring(0, s.length() - 1)) * 86400L;
        } catch (Exception ignored) {}
        return 60;
    }

    // ---------------- helpers ----------------

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static double pctToFrac(BigDecimal pct) {
        if (pct == null) return 0.0;
        return pct.doubleValue() / 100.0;
    }

    private static int intParam(Map<String, Object> p, String key, int def) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) Math.min(Integer.MAX_VALUE, l);
        if (v instanceof Double d) return (int) Math.round(d);
        if (v instanceof BigDecimal bd) return bd.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private static double doubleParam(Map<String, Object> p, String key, double def) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Double d) return d;
        if (v instanceof Float f) return f.doubleValue();
        if (v instanceof Integer i) return i.doubleValue();
        if (v instanceof Long l) return l.doubleValue();
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private static BigDecimal bigParamRequired(Map<String, Object> p, String key) {
        BigDecimal v = bigParam(p, key, null);
        if (v == null) {
            throw new IllegalArgumentException("Missing required param: " + key);
        }
        return v;
    }

    private static BigDecimal bigParam(Map<String, Object> p, String key, BigDecimal def) {
        if (p == null) return def;
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return BigDecimal.valueOf(f.doubleValue());
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static BigDecimal resolveTradeAmount(StrategySettings s, BigDecimal equity) {
        BigDecimal maxAllowed = resolveMaxExposureAmount(s, equity);

        // riskPerTradePct — трактуем как верхнюю границу аллокации от equity (если задано)
        BigDecimal riskPct = s.getRiskPerTradePct();
        if (riskPct != null && riskPct.signum() > 0) {
            BigDecimal byPct = equity.multiply(riskPct)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.DOWN);
            return byPct.min(maxAllowed);
        }

        return maxAllowed;
    }

    private static BigDecimal resolveMaxExposureAmount(StrategySettings s, BigDecimal equity) {
        BigDecimal maxUsd = s.getMaxExposureUsd();
        if (maxUsd != null && maxUsd.signum() > 0) {
            return maxUsd.min(equity);
        }

        BigDecimal pct = asBigDecimal(s.getMaxExposurePct());
        if (pct != null && pct.signum() > 0) {
            BigDecimal byPct = equity.multiply(pct)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.DOWN);
            return byPct.min(equity);
        }

        return equity;
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return BigDecimal.valueOf(f.doubleValue());
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static BigDecimal tryBigDecimal(Object target, String getterName) {
        try {
            Method m = target.getClass().getMethod(getterName);
            Object v = m.invoke(target);
            if (v instanceof BigDecimal bd) return bd;
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

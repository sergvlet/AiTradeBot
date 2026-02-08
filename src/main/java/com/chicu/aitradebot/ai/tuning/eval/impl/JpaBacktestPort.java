package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class JpaBacktestPort implements BacktestPort {

    private final StrategySettingsService strategySettingsService;
    private final BacktestCandlePort candlePort;

    @Override
    public BacktestMetrics backtest(Long chatId,
                                    StrategyType type,
                                    String exchange,
                                    NetworkType network,
                                    String symbolOverride,
                                    String timeframeOverride,
                                    Map<String, Object> candidateParams,
                                    Instant startAt,
                                    Instant endAt) {

        try {
            if (chatId == null || chatId <= 0) return BacktestMetrics.fail("chatId is null/bad");
            if (type == null) return BacktestMetrics.fail("strategyType is null");

            if (startAt == null || endAt == null) return BacktestMetrics.fail("startAt/endAt is null");
            if (!endAt.isAfter(startAt)) return BacktestMetrics.fail("endAt must be after startAt");

            String ex = normalizeExchangeOrNull(exchange);
            NetworkType net = network;

            // ✅ выбираем настройки: если ex/net заданы — пытаемся найти именно под них
            StrategySettings settings = pickBestSettings(chatId, type, ex, net);

            // ✅ symbol/timeframe: override > settings
            String symbol = (symbolOverride != null && !symbolOverride.isBlank())
                    ? symbolOverride.trim()
                    : settings.getSymbol();

            String timeframe = (timeframeOverride != null && !timeframeOverride.isBlank())
                    ? timeframeOverride.trim()
                    : settings.getTimeframe();

            if (symbol == null || symbol.isBlank()) return BacktestMetrics.fail("symbol is null/blank");
            if (timeframe == null || timeframe.isBlank()) return BacktestMetrics.fail("timeframe is null/blank");

            // ✅ env: аргументы имеют приоритет, иначе из settings
            if (ex == null) ex = normalizeExchangeOrNull(settings.getExchangeName());
            if (net == null) net = settings.getNetworkType();

            if (ex == null) return BacktestMetrics.fail("exchange is null/blank (arg + StrategySettings)");
            if (net == null) return BacktestMetrics.fail("network is null (arg + StrategySettings)");

            int limit = resolveLimit(settings, candidateParams);

            List<CandleBar> candles = candlePort.load(
                    chatId,
                    type,
                    ex,
                    net,
                    symbol,
                    timeframe,
                    startAt,
                    endAt,
                    limit
            );

            if (candles == null || candles.size() < 50) {
                return BacktestMetrics.fail("not enough candles: " + (candles == null ? 0 : candles.size()));
            }

            Map<String, Object> p = (candidateParams != null) ? candidateParams : Map.of();

            // ✅ реальный прогон
            return switch (type) {
                case SCALPING -> runScalping(chatId, symbol, timeframe, p, startAt, endAt, candles);
                case WINDOW_SCALPING -> runWindowScalping(chatId, symbol, timeframe, p, startAt, endAt, candles);
                default -> BacktestMetrics.fail("Unsupported strategy for backtest: " + type);
            };

        } catch (Exception e) {
            log.warn("Backtest failed: {}", e.getMessage());
            return BacktestMetrics.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================
    // StrategySettings pick (active first + freshest)
    // + если передали ex/net — предпочтём совпадение
    // =====================================================

    private StrategySettings pickBestSettings(Long chatId,
                                              StrategyType type,
                                              String exchangeOrNull,
                                              NetworkType networkOrNull) {

        Function<StrategySettings, Boolean> isInactive =
                s -> !Boolean.TRUE.equals(s.isActive());

        Comparator<StrategySettings> byFreshDesc =
                Comparator.comparing(
                                StrategySettings::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                StrategySettings::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .reversed();

        Comparator<StrategySettings> pickBest =
                Comparator.comparing(isInactive) // active(false) раньше inactive(true)
                        .thenComparing(byFreshDesc);

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == type)
                .toList();

        if (all.isEmpty()) {
            throw new IllegalStateException("StrategySettings not found: chatId=" + chatId + ", type=" + type);
        }

        // ✅ если env задан — сначала ищем совпадение, иначе fallback на “лучшие”
        if (exchangeOrNull != null && networkOrNull != null) {
            Optional<StrategySettings> exact = all.stream()
                    .filter(s -> exchangeOrNull.equalsIgnoreCase(safeTrimUpper(s.getExchangeName())))
                    .filter(s -> networkOrNull == s.getNetworkType())
                    .sorted(pickBest)
                    .findFirst();

            if (exact.isPresent()) return exact.get();
        }

        return all.stream()
                .sorted(pickBest)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings not found after sort"));
    }

    private static String safeTrimUpper(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x.toUpperCase(Locale.ROOT);
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? null : ex;
    }

    private static int resolveLimit(StrategySettings settings, Map<String, Object> candidateParams) {
        // 1) тюнер может передать candlesLimit
        if (candidateParams != null) {
            Integer a = tryInt(candidateParams.get("cachedCandlesLimit"));
            if (a != null && a > 0) return a;

            Integer b = tryInt(candidateParams.get("candlesLimit"));
            if (b != null && b > 0) return b;

            Integer c = tryInt(candidateParams.get("limit"));
            if (c != null && c > 0) return c;
        }

        // 2) StrategySettings.cachedCandlesLimit
        Integer cached = settings.getCachedCandlesLimit();
        if (cached != null && cached > 0) return cached;

        // 3) default
        return 1000;
    }

    private static Integer tryInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) Math.min(Integer.MAX_VALUE, l);
        if (v instanceof Double d) return (int) Math.round(d);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    // =====================================================
    // COMMON: Risk/Trade cfg (из candidateParams)
    // =====================================================

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

    // =====================================================
    // SCALPING
    // =====================================================

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

        Map<Integer, Integer> tradesPerDay = new HashMap<>();

        for (int i = window; i < candles.size(); i++) {
            CandleBar cur = candles.get(i);
            BigDecimal close = nz(cur.close());
            Instant ts = cur.openTime();
            if (ts == null) continue;

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

            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = close.compareTo(tp) >= 0;
            boolean hitSl = close.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                BigDecimal gross = close.subtract(entry, mc).divide(entry, mc); // доля
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);      // доля
                BigDecimal net = gross.subtract(fee.multiply(new BigDecimal("2"), mc), mc);

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

    // =====================================================
    // WINDOW_SCALPING
    // =====================================================

    private BacktestMetrics runWindowScalping(Long chatId,
                                              String symbol,
                                              String timeframe,
                                              Map<String, Object> p,
                                              Instant startAt,
                                              Instant endAt,
                                              List<CandleBar> candles) {

        int window = intParam(p, "windowSize", 30, 5, 2000);
        BigDecimal entryLowPct = bdParam(p, "entryFromLowPct", new BigDecimal("20"), "0", "100");
        BigDecimal minRangePct = bdParam(p, "minRangePct", new BigDecimal("0.25"), "0.01", "50");
        BigDecimal maxSpreadPct = bdParam(p, "maxSpreadPct", new BigDecimal("0.08"), "0.0", "50");

        BigDecimal tpPct = bdParam(p, "takeProfitPct", new BigDecimal("0.40"), "0.01", "50");
        BigDecimal slPct = bdParam(p, "stopLossPct", new BigDecimal("0.25"), "0.01", "50");
        BigDecimal feePct = bdParam(p, "commissionPct", new BigDecimal("0.10"), "0.0", "1.0");

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
        int posUnits = 0;
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

            BigDecimal spreadPct = range.divide(close.max(BigDecimal.ONE), mc).multiply(new BigDecimal("100"), mc);
            if (spreadPct.compareTo(maxSpreadPct) > 0) continue;

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

                BigDecimal lowZoneTop = lowW.add(range.multiply(entryLowPct.divide(new BigDecimal("100"), mc), mc), mc);
                if (close.compareTo(lowZoneTop) <= 0) {
                    inPos = true;
                    entry = close;
                    posUnits = 1;
                    averaged = false;
                }
                continue;
            }

            if (!averaged && rt.allowAveraging && (rt.maxOpenOrders == null || rt.maxOpenOrders >= 2)) {
                BigDecimal slLine = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);
                BigDecimal trigger = entry.subtract(entry.subtract(slLine, mc).divide(new BigDecimal("2"), mc), mc);
                if (close.compareTo(trigger) <= 0) {
                    BigDecimal newEntry = entry.multiply(new BigDecimal(posUnits), mc)
                            .add(close, mc)
                            .divide(new BigDecimal(posUnits + 1), mc);

                    entry = newEntry;
                    posUnits++;
                    averaged = true;
                }
            }

            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = close.compareTo(tp) >= 0;
            boolean hitSl = close.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                BigDecimal gross = close.subtract(entry, mc).divide(entry, mc);
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);
                BigDecimal net = gross.subtract(fee.multiply(new BigDecimal("2"), mc), mc);

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

            if (rt.maxDrawdownPct != null) {
                BigDecimal ddPct = maxDd.multiply(new BigDecimal("100"), mc);
                if (ddPct.compareTo(rt.maxDrawdownPct) > 0) break;
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

    // =====================================================
    // Helpers (parsing)
    // =====================================================

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

    private static Integer intNullablePositive(Map<String, Object> p, String key, int def, int min, int max) {
        if (p == null) return def <= 0 ? null : def;
        Object v = p.get(key);
        if (v == null) return def <= 0 ? null : def;
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

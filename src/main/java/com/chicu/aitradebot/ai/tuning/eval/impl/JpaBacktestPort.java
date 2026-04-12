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

    /**
     * ✅ Сигнатура без exchange/network (как у BacktestPort / RealMlBacktestRunner).
     * env берём из StrategySettings (но при выборе настроек учитываем symbol/timeframe override).
     */
    @Override
    public BacktestMetrics backtest(Long chatId,
                                    StrategyType type,
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

            StrategySettings settings = pickBestSettings(chatId, type, symbolOverride, timeframeOverride, candidateParams);

            String symbol = (symbolOverride != null && !symbolOverride.isBlank())
                    ? symbolOverride.trim()
                    : settings.getSymbol();

            String timeframe = (timeframeOverride != null && !timeframeOverride.isBlank())
                    ? timeframeOverride.trim()
                    : settings.getTimeframe();

            if (symbol == null || symbol.isBlank()) return BacktestMetrics.fail("symbol is null/blank");
            if (timeframe == null || timeframe.isBlank()) return BacktestMetrics.fail("timeframe is null/blank");

            String ex = normalizeExchangeOrNull(settings.getExchangeName());
            NetworkType net = settings.getNetworkType();

            if (ex == null) return BacktestMetrics.fail("exchange is null/blank (StrategySettings)");
            if (net == null) return BacktestMetrics.fail("network is null (StrategySettings)");

            int limit = resolveLimit(settings, candidateParams);

            List<CandleBar> candles = candlePort.load(
                    chatId,
                    type,
                    ex,
                    net,
                    symbol.trim(),
                    timeframe.trim(),
                    startAt,
                    endAt,
                    limit
            );

            if (candles == null || candles.size() < 50) {
                return BacktestMetrics.fail("not enough candles: " + (candles == null ? 0 : candles.size()));
            }

            Map<String, Object> p = (candidateParams != null) ? candidateParams : Map.of();

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

    /**
     * ✅ Env-aware override: если тюнер/раннер передал exchange+network — используем их.
     * Это критично, чтобы тестировать именно то окружение, где стратегия реально торгует.
     */
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

        // если env не задан — используем базовый метод
        if ((exchange == null || exchange.isBlank()) && network == null) {
            return backtest(chatId, type, symbolOverride, timeframeOverride, candidateParams, startAt, endAt);
        }

        try {
            if (chatId == null || chatId <= 0) return BacktestMetrics.fail("chatId is null/bad");
            if (type == null) return BacktestMetrics.fail("strategyType is null");

            if (startAt == null || endAt == null) return BacktestMetrics.fail("startAt/endAt is null");
            if (!endAt.isAfter(startAt)) return BacktestMetrics.fail("endAt must be after startAt");

            StrategySettings settings = pickBestSettings(chatId, type, symbolOverride, timeframeOverride, candidateParams);

            String symbol = (symbolOverride != null && !symbolOverride.isBlank())
                    ? symbolOverride.trim()
                    : settings.getSymbol();

            String timeframe = (timeframeOverride != null && !timeframeOverride.isBlank())
                    ? timeframeOverride.trim()
                    : settings.getTimeframe();

            if (symbol == null || symbol.isBlank()) return BacktestMetrics.fail("symbol is null/blank");
            if (timeframe == null || timeframe.isBlank()) return BacktestMetrics.fail("timeframe is null/blank");

            String ex = normalizeExchangeOrNull(exchange);
            if (ex == null) ex = normalizeExchangeOrNull(settings.getExchangeName());

            NetworkType net = (network != null ? network : settings.getNetworkType());

            if (ex == null) return BacktestMetrics.fail("exchange is null/blank");
            if (net == null) return BacktestMetrics.fail("network is null");

            int limit = resolveLimit(settings, candidateParams);

            List<CandleBar> candles = candlePort.load(
                    chatId,
                    type,
                    ex,
                    net,
                    symbol.trim(),
                    timeframe.trim(),
                    startAt,
                    endAt,
                    limit
            );

            if (candles == null || candles.size() < 50) {
                return BacktestMetrics.fail("not enough candles: " + (candles == null ? 0 : candles.size()));
            }

            Map<String, Object> p = (candidateParams != null) ? candidateParams : Map.of();

            return switch (type) {
                case SCALPING -> runScalping(chatId, symbol, timeframe, p, startAt, endAt, candles);
                case WINDOW_SCALPING -> runWindowScalping(chatId, symbol, timeframe, p, startAt, endAt, candles);
                default -> BacktestMetrics.fail("Unsupported strategy for backtest: " + type);
            };

        } catch (Exception e) {
            log.warn("Backtest(env) failed: {}", e.getMessage());
            return BacktestMetrics.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================
    // StrategySettings pick: active first + freshest
    // + prefer match by symbol/timeframe overrides (и при желании по env из candidateParams)
    // =====================================================

    private StrategySettings pickBestSettings(Long chatId,
                                              StrategyType type,
                                              String symbolOverride,
                                              String timeframeOverride,
                                              Map<String, Object> candidateParams) {

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId)
                .stream()
                .filter(s -> s != null && s.getType() == type)
                .toList();

        if (all.isEmpty()) {
            throw new IllegalStateException("StrategySettings not found: chatId=" + chatId + ", type=" + type);
        }

        String symHint = normSymbolOrNull(symbolOverride);
        String tfHint = normTfOrNull(timeframeOverride);

        String exHint = normalizeExchangeOrNull(firstString(candidateParams, "exchange", "exchangeName"));
        NetworkType netHint = firstNetwork(candidateParams, "network", "networkType");

        Function<StrategySettings, Boolean> isInactive = s -> !s.isActive(); // ✅ boolean

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
                        .thenComparingInt(s -> matchPenalty(s, symHint, tfHint, exHint, netHint))
                        .thenComparing(byFreshDesc);

        return all.stream()
                .sorted(pickBest)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings not found after sort"));
    }

    private static int matchPenalty(StrategySettings s,
                                    String symHint,
                                    String tfHint,
                                    String exHint,
                                    NetworkType netHint) {
        int p = 0;

        if (symHint != null) {
            String sym = normSymbolOrNull(s.getSymbol());
            if (sym == null || !symHint.equalsIgnoreCase(sym)) p += 10;
        }

        if (tfHint != null) {
            String tf = normTfOrNull(s.getTimeframe());
            if (tf == null || !tfHint.equalsIgnoreCase(tf)) p += 5;
        }

        if (exHint != null) {
            String ex = normalizeExchangeOrNull(s.getExchangeName());
            if (ex == null || !exHint.equalsIgnoreCase(ex)) p += 2;
        }

        if (netHint != null) {
            NetworkType n = s.getNetworkType();
            if (n == null || n != netHint) p += 2;
        }

        return p;
    }

    private static String firstString(Map<String, Object> p, String... keys) {
        if (p == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = p.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    private static NetworkType firstNetwork(Map<String, Object> p, String... keys) {
        String s = firstString(p, keys);
        if (s == null) return null;
        try {
            return NetworkType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String normSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTfOrNull(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? null : ex;
    }

    private static int resolveLimit(StrategySettings settings, Map<String, Object> candidateParams) {
        if (candidateParams != null) {
            Integer a = tryInt(candidateParams.get("cachedCandlesLimit"));
            if (a != null && a > 0) return a;

            Integer b = tryInt(candidateParams.get("candlesLimit"));
            if (b != null && b > 0) return b;

            Integer c = tryInt(candidateParams.get("limit"));
            if (c != null && c > 0) return c;
        }

        Integer cached = settings.getCachedCandlesLimit();
        if (cached != null && cached > 0) return cached;

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
            Instant ts = cur.openTime();
            if (ts == null) continue;

            BigDecimal close = nz(cur.close());

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
    // WINDOW_SCALPING (FIX NO_TRADES)
    // =====================================================

    private BacktestMetrics runWindowScalping(Long chatId,
                                              String symbol,
                                              String timeframe,
                                              Map<String, Object> p,
                                              Instant startAt,
                                              Instant endAt,
                                              List<CandleBar> candles) {

        int window = intParam(p, "windowSize", 30, 5, 2000);

        BigDecimal entryLowPct  = bdParam(p, "entryFromLowPct",  new BigDecimal("20"), "0", "100");
        BigDecimal entryHighPct = bdParam(p, "entryFromHighPct", new BigDecimal("20"), "0", "100");
        BigDecimal minRangePct  = bdParam(p, "minRangePct",      new BigDecimal("0.25"), "0.01", "50");

        // ✅ ВАЖНО: maxSpreadPct = спред СВЕЧИ (high-low), а не диапазон окна
        BigDecimal maxSpreadPct = bdParam(p, "maxSpreadPct",     new BigDecimal("0.08"), "0.0", "50");

        BigDecimal tpPct  = bdParam(p, "takeProfitPct", new BigDecimal("0.40"), "0.01", "50");
        BigDecimal slPct  = bdParam(p, "stopLossPct",   new BigDecimal("0.25"), "0.01", "50");
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

        // ✅ диагностика причин NO_TRADES
        Map<String, Integer> skips = new HashMap<>();

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
            if (ts == null) {
                skips.merge("ts_null", 1, Integer::sum);
                continue;
            }

            BigDecimal close = nz(cur.close());
            if (close.signum() <= 0) {
                skips.merge("close_invalid", 1, Integer::sum);
                continue;
            }

            BigDecimal candleHigh = nz(cur.high());
            BigDecimal candleLow  = nz(cur.low());
            if (candleHigh.signum() <= 0 || candleLow.signum() <= 0) {
                skips.merge("candle_hl_invalid", 1, Integer::sum);
                continue;
            }

            // окно
            BigDecimal highW = BigDecimal.ZERO;
            BigDecimal lowW = null;

            for (int j = i - window + 1; j <= i; j++) {
                CandleBar b = candles.get(j);
                BigDecimal h = nz(b.high());
                BigDecimal l = nz(b.low());
                if (h.compareTo(highW) > 0) highW = h;
                if (lowW == null || (l.signum() > 0 && l.compareTo(lowW) < 0)) lowW = l;
            }
            if (lowW == null || lowW.signum() <= 0) {
                skips.merge("window_low_invalid", 1, Integer::sum);
                continue;
            }

            BigDecimal range = highW.subtract(lowW, mc);
            if (range.signum() <= 0) {
                skips.merge("window_range_zero", 1, Integer::sum);
                continue;
            }

            BigDecimal rangePct = range.divide(lowW, mc).multiply(new BigDecimal("100"), mc);
            if (rangePct.compareTo(minRangePct) < 0) {
                skips.merge("minRangePct", 1, Integer::sum);
                continue;
            }

            // ✅ свечной спред
            BigDecimal candleSpread = candleHigh.subtract(candleLow, mc).abs(mc);
            BigDecimal candleSpreadPct = candleSpread
                    .divide(close.max(BigDecimal.ONE), mc)
                    .multiply(new BigDecimal("100"), mc);

            if (candleSpreadPct.compareTo(maxSpreadPct) > 0) {
                skips.merge("maxSpreadPct", 1, Integer::sum);
                continue;
            }

            if (!inPos) {
                if (ts.getEpochSecond() < nextEntryAllowedSec) {
                    skips.merge("cooldown", 1, Integer::sum);
                    continue;
                }

                Integer mtd = rt.maxTradesPerDay;
                if (mtd != null && mtd > 0) {
                    int d = dayIndex(startAt, ts);
                    if (tradesPerDay.getOrDefault(d, 0) >= mtd) {
                        skips.merge("maxTradesPerDay", 1, Integer::sum);
                        continue;
                    }
                }

                if (rt.maxConsecutiveLosses != null && rt.maxConsecutiveLosses > 0) {
                    if (consecLoss >= rt.maxConsecutiveLosses) {
                        skips.merge("maxConsecutiveLosses", 1, Integer::sum);
                        continue;
                    }
                }

                // ✅ зона входа: near low + (не near high)
                BigDecimal lowZoneTop = lowW.add(range.multiply(entryLowPct.divide(new BigDecimal("100"), mc), mc), mc);
                BigDecimal highGuard  = highW.subtract(range.multiply(entryHighPct.divide(new BigDecimal("100"), mc), mc), mc);
                BigDecimal entryTop = lowZoneTop.min(highGuard);

                // ✅ триггер: low свечи коснулся зоны
                boolean touched = candleLow.compareTo(entryTop) <= 0;
                boolean closedIn = close.compareTo(entryTop) <= 0;

                if (touched || closedIn) {
                    // консервативно: вход по верхней границе зоны
                    BigDecimal entryPx = entryTop;
                    if (entryPx.signum() <= 0) {
                        skips.merge("entry_invalid", 1, Integer::sum);
                        continue;
                    }

                    inPos = true;
                    entry = entryPx;
                    posUnits = 1;
                    averaged = false;
                } else {
                    skips.merge("noEntrySignal", 1, Integer::sum);
                }

                continue;
            }

            // averaging: один раз, по касанию low свечи
            if (!averaged && rt.allowAveraging && (rt.maxOpenOrders == null || rt.maxOpenOrders >= 2)) {
                BigDecimal slLine = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);
                BigDecimal trigger = entry.subtract(entry.subtract(slLine, mc).divide(new BigDecimal("2"), mc), mc);

                if (candleLow.compareTo(trigger) <= 0) {
                    BigDecimal addPx = trigger.max(BigDecimal.ZERO);
                    if (addPx.signum() > 0) {
                        BigDecimal newEntry = entry.multiply(new BigDecimal(posUnits), mc)
                                .add(addPx, mc)
                                .divide(new BigDecimal(posUnits + 1), mc);

                        entry = newEntry;
                        posUnits++;
                        averaged = true;
                    }
                }
            }

            BigDecimal tp = entry.multiply(BigDecimal.ONE.add(tpPct.divide(new BigDecimal("100"), mc)), mc);
            BigDecimal sl = entry.multiply(BigDecimal.ONE.subtract(slPct.divide(new BigDecimal("100"), mc)), mc);

            boolean hitTp = candleHigh.compareTo(tp) >= 0;
            boolean hitSl = candleLow.compareTo(sl) <= 0;

            if (hitTp || hitSl) {
                trades++;

                // если оба в одной свече — берём консервативно SL
                BigDecimal exitPx = hitSl ? sl : tp;

                BigDecimal gross = exitPx.subtract(entry, mc).divide(entry, mc); // доля
                BigDecimal fee = feePct.divide(new BigDecimal("100"), mc);       // доля
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

        if (trades == 0) {
            String top = skips.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("unknown");
            reason = "NO_TRADES:" + top;
        }

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
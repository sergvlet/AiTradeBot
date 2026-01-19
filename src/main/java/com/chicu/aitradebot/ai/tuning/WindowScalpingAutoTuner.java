package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.MlBacktestRunner;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettings;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingAutoTuner implements StrategyAutoTuner {

    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final MlBacktestRunner backtestRunner;
    private final WindowScalpingTunerProperties props;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.WINDOW_SCALPING;
    }

    @Override
    public TuningResult tune(TuningRequest request) {

        if (request == null || request.chatId() == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("WINDOW_SCALPING tuner: request/chatId is null")
                    .modelVersion(props.getModelVersion())
                    .build();
        }

        if (request.strategyType() != null && request.strategyType() != StrategyType.WINDOW_SCALPING) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("WINDOW_SCALPING tuner: wrong strategyType=" + request.strategyType())
                    .modelVersion(props.getModelVersion())
                    .build();
        }

        final Long chatId = request.chatId();
        final StrategyType type = StrategyType.WINDOW_SCALPING;

        // =========================================================
        // ✅ окружение: request -> db (без хардкода дефолтов)
        // =========================================================
        final Env env;
        try {
            env = resolveEnv(chatId, type, request.exchange(), request.network());
        } catch (Exception e) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Не могу определить exchange/network: " + e.getMessage())
                    .modelVersion(props.getModelVersion())
                    .build();
        }

        final String ex = env.exchange;
        final NetworkType net = env.network;

        WindowScalpingStrategySettings curWs = windowSettingsService.getOrCreate(chatId);
        StrategySettings curSs = strategySettingsService.getOrCreate(chatId, type, ex, net);

        // ---------------------------------------------------------------------
        // strategy params
        // ---------------------------------------------------------------------
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("windowSize", nz(curWs.getWindowSize(), 30));
        base.put("entryFromLowPct", nz(curWs.getEntryFromLowPct(), 20.0));
        base.put("entryFromHighPct", nz(curWs.getEntryFromHighPct(), 20.0));
        base.put("minRangePct", nz(curWs.getMinRangePct(), 0.25));
        base.put("maxSpreadPct", nz(curWs.getMaxSpreadPct(), 0.08));

        // ---------------------------------------------------------------------
        // risk/trade params
        // ---------------------------------------------------------------------
        Map<String, Object> riskTrade = new LinkedHashMap<>();
        riskTrade.put("riskPerTradePct", bdOr(curSs.getRiskPerTradePct(), "1.0"));
        riskTrade.put("minRiskReward", bdOr(curSs.getMinRiskReward(), "1.2"));

        // ✅ leverage у тебя int → никаких null-check
        riskTrade.put("leverage", normalizeLeverage(curSs.getLeverage()));

        riskTrade.put("allowAveraging", parseBool(curSs.getAllowAveraging()));

        riskTrade.put("cooldownSeconds", curSs.getCooldownSeconds());
        riskTrade.put("cooldownAfterLossSeconds", curSs.getCooldownAfterLossSeconds());
        riskTrade.put("maxConsecutiveLosses", curSs.getMaxConsecutiveLosses());
        riskTrade.put("maxDrawdownPct", curSs.getMaxDrawdownPct());
        riskTrade.put("maxPositionPct", curSs.getMaxPositionPct());
        riskTrade.put("maxTradesPerDay", curSs.getMaxTradesPerDay());
        riskTrade.put("maxOpenOrders", curSs.getMaxOpenOrders());

        // ---------------------------------------------------------------------
        // ✅ candlesLimit (без сравнений int != null)
        // ---------------------------------------------------------------------
        final int minCandles = props.getMinCandlesLimit();
        final int defaultCandles = props.getDefaultCandlesLimit();

        Integer candlesLimit = request.candlesLimit();
        if (candlesLimit == null || candlesLimit < minCandles) {
            candlesLimit = curSs.getCachedCandlesLimit();
        }
        if (candlesLimit == null || candlesLimit < minCandles) {
            candlesLimit = defaultCandles;
        }
        riskTrade.put("cachedCandlesLimit", candlesLimit);

        Map<String, Object> oldParams = new LinkedHashMap<>();
        oldParams.putAll(base);
        oldParams.putAll(riskTrade);

        // ---------------------------------------------------------------------
        // period
        // ---------------------------------------------------------------------
        Instant startAt = (request.startAt() != null)
                ? request.startAt()
                : Instant.now().minusSeconds((long) props.getDefaultPeriodDays() * 86_400L);

        Instant endAt = (request.endAt() != null) ? request.endAt() : Instant.now();

        // symbol/tf
        String symbol = normalizeSymbol(firstNonBlank(request.symbol(), curSs.getSymbol()));
        String timeframe = normalizeTimeframe(firstNonBlank(request.timeframe(), curSs.getTimeframe()));

        if (symbol == null || timeframe == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("symbol/timeframe is blank (request+db)")
                    .modelVersion(props.getModelVersion())
                    .oldParams(oldParams)
                    .newParams(oldParams)
                    .build();
        }

        // ---------------------------------------------------------------------
        // baseline
        // ---------------------------------------------------------------------
        BacktestMetrics bm0 = backtestRunner.run(
                chatId,
                type,
                ex, net,
                symbol,
                timeframe,
                oldParams,
                startAt,
                endAt
        );

        if (bm0 == null || !bm0.ok()) {
            String why = (bm0 == null) ? "BacktestMetrics is null" : ("Backtest failed: " + bm0.reason());
            log.warn("🧠 WINDOW_SCALPING baseline FAIL chatId={} ex={} net={} sym={} tf={} reason={}",
                    chatId, ex, net, symbol, timeframe, why);

            return TuningResult.builder()
                    .applied(false)
                    .reason(why)
                    .modelVersion(props.getModelVersion())
                    .oldParams(oldParams)
                    .newParams(oldParams)
                    .build();
        }

        BigDecimal score0 = score(bm0, oldParams);

        // ---------------------------------------------------------------------
        // candidates (int)
        // ---------------------------------------------------------------------
        int candidatesN = Math.max(1, props.getCandidates());

        BigDecimal bestScore = score0;
        Map<String, Object> bestParams = oldParams;
        BacktestMetrics bestBm = bm0;

        for (int i = 0; i < candidatesN; i++) {
            Map<String, Object> cand = mutateCandidate(oldParams);

            BacktestMetrics bm = backtestRunner.run(
                    chatId,
                    type,
                    ex, net,
                    symbol,
                    timeframe,
                    cand,
                    startAt,
                    endAt
            );

            BigDecimal sc = score(bm, cand);
            if (sc.compareTo(bestScore) > 0) {
                bestScore = sc;
                bestParams = cand;
                bestBm = bm;
            }
        }

        BigDecimal delta = bestScore.subtract(score0);

        // ---------------------------------------------------------------------
        // thresholds
        // ---------------------------------------------------------------------
        BigDecimal minAbsImprove = nzBd(props.getMinAbsImprove(), new BigDecimal("0.02"));
        BigDecimal minRelImprove = nzBd(props.getMinRelImprove(), new BigDecimal("0.03"));
        BigDecimal relThreshold = score0.abs().multiply(minRelImprove).max(minAbsImprove);

        boolean passAbs = delta.compareTo(minAbsImprove) >= 0;
        boolean passRel = delta.compareTo(relThreshold) >= 0;

        BigDecimal baselineTooBadScore = nzBd(props.getBaselineTooBadScore(), new BigDecimal("-1.00"));
        BigDecimal baselineTooBadMinDelta = nzBd(props.getBaselineTooBadMinDelta(), new BigDecimal("0.01"));

        boolean baselineTooBad = score0.compareTo(baselineTooBadScore) <= 0;
        boolean passTooBad = baselineTooBad && delta.compareTo(baselineTooBadMinDelta) >= 0;

        if (!(passAbs || passRel || passTooBad)) {
            if (props.isLogSkipAsInfo()) {
                log.info("🧠 WINDOW_SCALPING tune SKIP chatId={} ex={} net={} score0={} best={} delta={} thrAbs={} thrRel={}",
                        chatId, ex, net, score0, bestScore, delta, minAbsImprove, relThreshold);
            } else if (log.isDebugEnabled()) {
                log.debug("🧠 WINDOW_SCALPING tune SKIP chatId={} ex={} net={} score0={} best={} delta={} thrAbs={} thrRel={}",
                        chatId, ex, net, score0, bestScore, delta, minAbsImprove, relThreshold);
            }

            return TuningResult.builder()
                    .applied(false)
                    .reason("Нет достаточного улучшения")
                    .modelVersion(props.getModelVersion())
                    .scoreBefore(score0)
                    .scoreAfter(bestScore)
                    .oldParams(oldParams)
                    .newParams(oldParams)
                    .build();
        }

        // =====================================================================
        // APPLY
        // =====================================================================

        WindowScalpingStrategySettings patchedWs = WindowScalpingStrategySettings.builder()
                .chatId(chatId)
                .windowSize(clampInt(bestParams.get("windowSize"), 5, 2000, nz(curWs.getWindowSize(), 30)))
                .entryFromLowPct(clampDouble(bestParams.get("entryFromLowPct"), 0.0, 100.0, nz(curWs.getEntryFromLowPct(), 20.0)))
                .entryFromHighPct(clampDouble(bestParams.get("entryFromHighPct"), 0.0, 100.0, nz(curWs.getEntryFromHighPct(), 20.0)))
                .minRangePct(clampDouble(bestParams.get("minRangePct"), 0.01, 50.0, nz(curWs.getMinRangePct(), 0.25)))
                .maxSpreadPct(clampDouble(bestParams.get("maxSpreadPct"), 0.0, 50.0, nz(curWs.getMaxSpreadPct(), 0.08)))
                .build();

        windowSettingsService.update(chatId, patchedWs);

        curSs.setRiskPerTradePct(parseBd(bestParams.get("riskPerTradePct")));
        curSs.setMinRiskReward(parseBd(bestParams.get("minRiskReward")));

        // ✅ leverage у тебя int → никаких null-check, дефолт через normalizeLeverage()
        curSs.setLeverage(clampInt(bestParams.get("leverage"), 1, 50, normalizeLeverage(curSs.getLeverage())));

        curSs.setAllowAveraging(parseBool(bestParams.get("allowAveraging")));

        curSs.setCooldownSeconds(toNullablePositiveInt(bestParams.get("cooldownSeconds")));
        curSs.setCooldownAfterLossSeconds(toNullablePositiveInt(bestParams.get("cooldownAfterLossSeconds")));
        curSs.setMaxConsecutiveLosses(toNullablePositiveInt(bestParams.get("maxConsecutiveLosses")));

        curSs.setMaxDrawdownPct(parseBd(bestParams.get("maxDrawdownPct")));
        curSs.setMaxPositionPct(parseBd(bestParams.get("maxPositionPct")));

        curSs.setMaxTradesPerDay(toNullablePositiveInt(bestParams.get("maxTradesPerDay")));
        curSs.setMaxOpenOrders(toNullablePositiveInt(bestParams.get("maxOpenOrders")));

        // витрина (mlConfidence/totalProfit)
        if (bestBm != null && bestBm.ok()) {
            BigDecimal eps = nzBd(props.getEpsilon(), new BigDecimal("0.0001"));
            curSs.setTotalProfitPct(normEps(bestBm.profitPct(), eps));
            curSs.setMlConfidence(norm01(deriveMlConfidence(bestBm)));
        }

        strategySettingsService.save(curSs);

        log.info("🧠 WINDOW_SCALPING tuned APPLY chatId={} ex={} net={} score {} -> {} delta={} profit={} dd={} trades={}",
                chatId, ex, net, score0, bestScore, delta,
                safeBd(bestBm != null ? bestBm.profitPct() : null),
                safeBd(bestBm != null ? bestBm.maxDrawdownPct() : null),
                bestBm != null ? bestBm.trades() : 0
        );

        return TuningResult.builder()
                .applied(true)
                .reason("WINDOW_SCALPING tuned + applied")
                .modelVersion(props.getModelVersion())
                .scoreBefore(score0)
                .scoreAfter(bestScore)
                .oldParams(oldParams)
                .newParams(new LinkedHashMap<>(bestParams))
                .build();
    }

    // =====================================================================
    // ENV resolve (без хардкода дефолтов)
    // =====================================================================

    private Env resolveEnv(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        if (ex != null && net != null) {
            return new Env(ex, net);
        }

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId, null, null);

        Comparator<StrategySettings> byFreshDesc =
                Comparator.comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed();

        Comparator<StrategySettings> byActiveFirst =
                Comparator.comparing((StrategySettings s) -> !activeValue(s)); // false(активна) -> раньше

        // 1) пытаемся найти запись именно этой стратегии
        StrategySettings best = all.stream()
                .filter(s -> s != null)
                .filter(s -> s.getType() == type)
                .filter(s -> s.getExchangeName() != null && s.getNetworkType() != null)
                .sorted(byActiveFirst.thenComparing(byFreshDesc))
                .findFirst()
                .orElse(null);

        // 2) если нет — любая запись с env, чтобы не падать без причины
        if (best == null) {
            best = all.stream()
                    .filter(s -> s != null)
                    .filter(s -> s.getExchangeName() != null && s.getNetworkType() != null)
                    .sorted(byFreshDesc)
                    .findFirst()
                    .orElse(null);
        }

        if (ex == null && best != null) ex = normalizeExchangeOrNull(best.getExchangeName());
        if (net == null && best != null) net = best.getNetworkType();

        if (ex == null || net == null) {
            throw new IllegalStateException("Нет данных env в request и в базе (сначала выбери сеть/биржу в UI)");
        }

        return new Env(ex, net);
    }

    private record Env(String exchange, NetworkType network) {}

    private static boolean activeValue(StrategySettings s) {
        if (s == null) return false;

        // 1) Boolean getActive()
        try {
            Method m = s.getClass().getMethod("getActive");
            Object v = m.invoke(s);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 2) boolean isActive()
        try {
            Method m = s.getClass().getMethod("isActive");
            Object v = m.invoke(s);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        return false;
    }

    private static int normalizeLeverage(int lev) {
        return lev > 0 ? lev : 1;
    }

    // =====================================================================
    // Candidate mutation / score
    // =====================================================================

    private static Map<String, Object> mutateCandidate(Map<String, Object> base) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Map<String, Object> m = new LinkedHashMap<>(base);

        m.put("windowSize", clampInt(jitterInt(m.get("windowSize"), r, 0.25), 5, 2000, 30));
        m.put("entryFromLowPct", clampDouble(jitterDouble(m.get("entryFromLowPct"), r, 6.0), 0, 100, 20));
        m.put("entryFromHighPct", clampDouble(jitterDouble(m.get("entryFromHighPct"), r, 6.0), 0, 100, 20));
        m.put("minRangePct", clampDouble(jitterDouble(m.get("minRangePct"), r, 0.15), 0.01, 50.0, 0.25));
        m.put("maxSpreadPct", clampDouble(jitterDouble(m.get("maxSpreadPct"), r, 0.08), 0.0, 50.0, 0.08));

        m.put("riskPerTradePct", clampBd(jitterBd(m.get("riskPerTradePct"), r, "0.40"), "0.01", "20.0", "1.0"));
        m.put("minRiskReward", clampBd(jitterBd(m.get("minRiskReward"), r, "0.35"), "0.1", "10.0", "1.2"));

        int lev = clampInt(m.get("leverage"), 1, 50, 1);
        if (r.nextDouble() < 0.35) {
            lev = Math.max(1, Math.min(50, lev + (r.nextBoolean() ? 1 : -1)));
        } else if (r.nextDouble() < 0.10) {
            lev = r.nextInt(1, 11);
        }
        m.put("leverage", lev);

        if (r.nextDouble() < 0.10) {
            m.put("allowAveraging", !parseBool(m.get("allowAveraging")));
        }

        m.put("cooldownSeconds", jitterNullableInt(m.get("cooldownSeconds"), r, 0, 3600));
        m.put("cooldownAfterLossSeconds", jitterNullableInt(m.get("cooldownAfterLossSeconds"), r, 0, 7200));
        m.put("maxConsecutiveLosses", jitterNullableInt(m.get("maxConsecutiveLosses"), r, 0, 20));

        m.put("maxDrawdownPct", jitterNullableBdPct(m.get("maxDrawdownPct"), r, "0.8"));
        m.put("maxPositionPct", jitterNullableBdPct(m.get("maxPositionPct"), r, "0.8"));

        m.put("maxTradesPerDay", jitterNullableInt(m.get("maxTradesPerDay"), r, 0, 300));
        m.put("maxOpenOrders", jitterNullableInt(m.get("maxOpenOrders"), r, 1, 50));

        return m;
    }

    private static BigDecimal score(BacktestMetrics m, Map<String, Object> params) {
        if (m == null || !m.ok()) return new BigDecimal("-999999");

        BigDecimal profit = safeBd(m.profitPct());
        BigDecimal dd = safeBd(m.maxDrawdownPct());

        int trades = m.trades();
        int lev = clampInt(params.get("leverage"), 1, 50, 1);

        BigDecimal risk = parseBd(params.get("riskPerTradePct"));
        if (risk == null) risk = new BigDecimal("1.0");

        boolean avg = parseBool(params.get("allowAveraging"));

        BigDecimal s = profit
                .subtract(dd.multiply(new BigDecimal("0.70")))
                .subtract(BigDecimal.valueOf(Math.max(0, lev - 1)).multiply(new BigDecimal("0.25")))
                .subtract(risk.multiply(new BigDecimal("0.10")));

        if (trades < 5) s = s.subtract(new BigDecimal("1.0"));
        if (avg) s = s.subtract(new BigDecimal("0.15"));

        BigDecimal maxDdLimit = parseBd(params.get("maxDrawdownPct"));
        if (maxDdLimit != null && dd.compareTo(maxDdLimit) > 0) {
            s = s.subtract(new BigDecimal("50"));
        }

        return s.setScale(6, RoundingMode.HALF_UP);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static BigDecimal nzBd(BigDecimal v, BigDecimal def) { return v != null ? v : def; }
    private static Integer nz(Integer v, int def) { return v != null ? v : def; }
    private static Double nz(Double v, double def) { return v != null ? v : def; }
    private static BigDecimal bdOr(BigDecimal v, String def) { return v != null ? v : new BigDecimal(def); }
    private static BigDecimal safeBd(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static String normalizeExchangeOrNull(String ex) {
        if (ex == null) return null;
        String s = ex.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return (b != null && !b.trim().isEmpty()) ? b.trim() : null;
    }

    private static BigDecimal normEps(BigDecimal v, BigDecimal eps) {
        if (v == null) return BigDecimal.ZERO;
        if (eps == null) return v;
        return v.abs().compareTo(eps) < 0 ? BigDecimal.ZERO : v;
    }

    private static BigDecimal norm01(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return v;
    }

    private static int clampInt(Object v, int min, int max, int def) {
        try {
            int x = (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (x < min) return min;
            if (x > max) return max;
            return x;
        } catch (Exception e) {
            return def;
        }
    }

    private static double clampDouble(Object v, double min, double max, double def) {
        try {
            double x = (v instanceof Number n) ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(v).trim().replace(",", "."));
            if (x < min) return min;
            if (x > max) return max;
            return x;
        } catch (Exception e) {
            return def;
        }
    }

    private static BigDecimal parseBd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(String.valueOf(v).trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean parseBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static Integer toNullablePositiveInt(Object v) {
        try {
            if (v == null) return null;
            int x = (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
            return x > 0 ? x : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int jitterInt(Object v, ThreadLocalRandom r, double frac) {
        int x = clampInt(v, 1, Integer.MAX_VALUE, 30);
        int delta = Math.max(1, (int) Math.round(x * frac));
        return x + r.nextInt(-delta, delta + 1);
    }

    private static double jitterDouble(Object v, ThreadLocalRandom r, double deltaAbs) {
        double x = clampDouble(v, -1e9, 1e9, 0.0);
        return x + r.nextDouble(-deltaAbs, deltaAbs);
    }

    private static BigDecimal jitterBd(Object v, ThreadLocalRandom r, String deltaAbs) {
        BigDecimal x = parseBd(v);
        if (x == null) x = BigDecimal.ZERO;
        BigDecimal d = new BigDecimal(deltaAbs);
        BigDecimal j = BigDecimal.valueOf(r.nextDouble(-1.0, 1.0)).multiply(d);
        return x.add(j);
    }

    private static BigDecimal clampBd(BigDecimal v, String min, String max, String def) {
        BigDecimal x = (v != null ? v : new BigDecimal(def));
        BigDecimal mn = new BigDecimal(min);
        BigDecimal mx = new BigDecimal(max);
        if (x.compareTo(mn) < 0) x = mn;
        if (x.compareTo(mx) > 0) x = mx;
        return x.setScale(6, RoundingMode.HALF_UP);
    }

    private static Object jitterNullableInt(Object v, ThreadLocalRandom r, int min, int max) {
        if (min == 0 && r.nextDouble() < 0.20) return null;
        int x = clampInt(v, min, max, min);
        if (r.nextDouble() < 0.30) {
            int d = r.nextInt(-30, 31);
            x = Math.max(min, Math.min(max, x + d));
        }
        if (x <= 0 && min == 0) return null;
        return x;
    }

    private static Object jitterNullableBdPct(Object v, ThreadLocalRandom r, String deltaAbs) {
        if (r.nextDouble() < 0.20) return null;
        BigDecimal x = parseBd(v);
        if (x == null) x = new BigDecimal("10.0");
        BigDecimal d = new BigDecimal(deltaAbs);
        BigDecimal j = BigDecimal.valueOf(r.nextDouble(-1.0, 1.0)).multiply(d);
        x = x.add(j);
        if (x.compareTo(BigDecimal.ZERO) < 0) x = BigDecimal.ZERO;
        if (x.compareTo(BigDecimal.valueOf(100)) > 0) x = BigDecimal.valueOf(100);
        return x.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal deriveMlConfidence(BacktestMetrics bm) {
        if (bm == null || !bm.ok()) return BigDecimal.ZERO;

        int trades = bm.trades();
        BigDecimal wr = bm.winRatePct() != null ? bm.winRatePct() : BigDecimal.ZERO;

        BigDecimal t = BigDecimal.valueOf(Math.min(trades, 50))
                .divide(BigDecimal.valueOf(50), 6, RoundingMode.HALF_UP);

        BigDecimal w = wr.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO).min(BigDecimal.ONE);

        return w.multiply(new BigDecimal("0.60"))
                .add(t.multiply(new BigDecimal("0.40")))
                .setScale(6, RoundingMode.HALF_UP);
    }
}

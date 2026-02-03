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
import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingAutoTuner implements StrategyAutoTuner {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final WindowScalpingTunerProperties props;
    private final MlBacktestRunner backtestRunner;

    private final StrategySettingsService strategySettingsService;
    private final WindowScalpingStrategySettingsService windowSettingsService;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.WINDOW_SCALPING;
    }

    @Override
    public TuningResult tune(TuningRequest request) {

        // ✅ FAIL-SAFE: тюнер должен работать только с реальным раннером
        String runnerName = (backtestRunner != null)
                ? backtestRunner.getClass().getSimpleName()
                : "null";
        if (runnerName.toLowerCase(Locale.ROOT).contains("stub")) {
            log.warn("[WS-TUNER] ❌ MlBacktestRunner выглядит как STUB: {}. Тюнинг отключён, нужен реальный бэктест-раннер.",
                    runnerName);
            return TuningResult.builder()
                    .applied(false)
                    .reason("stub_backtest_runner")
                    .modelVersion(safe(props.getModelVersion()))
                    .build();
        }

        if (request == null) {
            return TuningResult.builder().applied(false).reason("request=null").build();
        }

        Long chatId = request.chatId();
        if (chatId == null || chatId <= 0) {
            return TuningResult.builder().applied(false).reason("bad chatId").build();
        }

        String ex = normalizeExchange(request.exchange());
        NetworkType net = request.network() != null ? request.network() : NetworkType.MAINNET;

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING, ex, net);
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

        if (ss == null || cfg == null) {
            return TuningResult.builder().applied(false).reason("no_settings").build();
        }

        String symbol = safeSym(firstNonBlank(request.symbol(), ss.getSymbol()));
        String tf = safeTf(firstNonBlank(request.timeframe(), ss.getTimeframe()));

        Integer candlesLimit = request.candlesLimit() != null ? request.candlesLimit() : ss.getCachedCandlesLimit();
        int minCL = Math.max(50, props.getMinCandlesLimit());
        int defCL = Math.max(minCL, props.getDefaultCandlesLimit());
        int cl = (candlesLimit == null || candlesLimit < minCL) ? defCL : candlesLimit;

        int days = Math.max(3, props.getDefaultPeriodDays());
        LocalDate endDate = LocalDate.now(ZONE);
        LocalDate startDate = endDate.minusDays(days);

        Instant start = startDate.atStartOfDay(ZONE).toInstant();
        Instant end = endDate.plusDays(1).atStartOfDay(ZONE).toInstant();

        // baseline
        Map<String, Object> baselineParams = buildParamsFromCurrent(ss, cfg);
        baselineParams.put("candlesLimit", cl);
        baselineParams.put("cachedCandlesLimit", cl);

        BacktestMetrics baseMetrics = safeRunBacktest(chatId, ex, net, symbol, tf, baselineParams, start, end);

        // ✅ FIX: score() может быть BigDecimal → всегда приводим к double безопасно
        double baseScore = (baseMetrics != null && baseMetrics.score() != null)
                ? baseMetrics.score().doubleValue()
                : -1.0;

        Integer baseTrades = (baseMetrics != null ? baseMetrics.trades() : null);

        // ✅ если базово сделок нет — разжимаем грубые фильтры 1 раз
        if (baseTrades != null && baseTrades <= 0) {
            log.warn("[WS-TUNER] baseline NO_TRADES chatId={} ex={} net={} sym={} tf={} cl={} -> coarse adjust",
                    chatId, ex, net, symbol, tf, cl);

            boolean changed = adjustCoarseFiltersInternal(chatId, ex, net, symbol, tf, ss, cfg, "baseline_no_trades");
            if (changed) {
                persistSafe(strategySettingsService, ss);
                persistSafe(windowSettingsService, cfg);
            }

            baselineParams = buildParamsFromCurrent(ss, cfg);
            baselineParams.put("candlesLimit", cl);
            baselineParams.put("cachedCandlesLimit", cl);

            baseMetrics = safeRunBacktest(chatId, ex, net, symbol, tf, baselineParams, start, end);

            baseScore = (baseMetrics != null && baseMetrics.score() != null)
                    ? baseMetrics.score().doubleValue()
                    : -1.0;

            baseTrades = (baseMetrics != null ? baseMetrics.trades() : null);
        }

        int candidates = Math.max(5, props.getCandidates());
        long seed = Optional.ofNullable(request.seed()).orElse(System.nanoTime());
        Random rnd = new Random(seed);

        Candidate best = new Candidate(baselineParams, baseScore, baseTrades, "baseline");

        for (int i = 0; i < candidates; i++) {
            Map<String, Object> cand = new HashMap<>(baselineParams);
            mutateCandidate(cand, rnd);

            cand.put("candlesLimit", cl);
            cand.put("cachedCandlesLimit", cl);

            BacktestMetrics m = safeRunBacktest(chatId, ex, net, symbol, tf, cand, start, end);

            // ✅ FIX: score() BigDecimal → double
            double sc = (m != null && m.score() != null)
                    ? m.score().doubleValue()
                    : -1.0;

            Integer tr = (m != null ? m.trades() : null);

            if (isBetter(sc, tr, best.score, best.trades)) {
                best = new Candidate(cand, sc, tr, "cand#" + i);
            }
        }

        BigDecimal base = bd(baseScore);
        BigDecimal bestSc = bd(best.score);

        if (base == null) base = BigDecimal.valueOf(-1.0);
        if (bestSc == null) bestSc = BigDecimal.valueOf(-1.0);

        BigDecimal delta = bestSc.subtract(base);
        boolean apply = shouldApply(base, delta);

        String modelVersion = safe(props.getModelVersion());

        if (!apply) {
            String reason = (best.trades != null && best.trades <= 0) ? "no_trades" : "no_improvement";
            logSkip(chatId, ex, net, symbol, tf, base, bestSc, delta, reason);

            return TuningResult.builder()
                    .applied(false)
                    .scoreBefore(base)
                    .scoreAfter(bestSc)
                    .modelVersion(modelVersion)
                    .reason(reason)
                    .build();
        }

        // ✅ применяем лучший
        applyToSettings(ss, cfg, best.params);

        persistSafe(strategySettingsService, ss);
        persistSafe(windowSettingsService, cfg);

        log.info("[WS-TUNER] ✅ APPLIED chatId={} ex={} net={} sym={} tf={} base={} best={} delta={} trades={} model={}",
                chatId, ex, net, symbol, tf,
                strip(base), strip(bestSc), strip(delta),
                best.trades, modelVersion
        );

        return TuningResult.builder()
                .applied(true)
                .scoreBefore(base)
                .scoreAfter(bestSc)
                .modelVersion(modelVersion)
                .reason("applied")
                .build();
    }

    // =====================================================
    // ✅ NO_TRADES handlers (для AutoTunerOrchestrator reflection)
    // =====================================================

    public boolean onNoTrades(TuningRequest request) {
        return adjustCoarseFilters(request);
    }

    public boolean adjustCoarseFilters(TuningRequest request) {
        return adjustCoarseFilters(request, "no_trades");
    }

    public boolean onNoTrades(TuningRequest request, String reason) {
        return adjustCoarseFilters(request, reason);
    }

    public boolean adjustCoarseFilters(TuningRequest request, String reason) {
        if (request == null || request.chatId() == null || request.chatId() <= 0) return false;

        Long chatId = request.chatId();
        String ex = normalizeExchange(request.exchange());
        NetworkType net = request.network() != null ? request.network() : NetworkType.MAINNET;

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING, ex, net);
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

        if (ss == null || cfg == null) return false;

        String symbol = safeSym(request.symbol());
        String tf = safeTf(request.timeframe());

        boolean applied = adjustCoarseFiltersInternal(chatId, ex, net, symbol, tf, ss, cfg, reason);
        if (applied) {
            persistSafe(strategySettingsService, ss);
            persistSafe(windowSettingsService, cfg);
        }
        return applied;
    }

    // =====================================================
    // coarse adjust
    // =====================================================

    private boolean adjustCoarseFiltersInternal(Long chatId,
                                                String ex,
                                                NetworkType net,
                                                String symbol,
                                                String tf,
                                                StrategySettings ss,
                                                WindowScalpingStrategySettings cfg,
                                                String reason) {

        boolean changed = false;

        double oldMinRange = toDouble(readGetter(cfg, "getMinRangePct"));
        double newMinRange;

        if (reason != null && reason.toLowerCase(Locale.ROOT).contains("range")) {
            newMinRange = Math.max(0.0, oldMinRange * 0.35);
        } else {
            newMinRange = Math.max(0.0, oldMinRange * 0.60);
        }

        newMinRange = clampD(newMinRange, 0.0, 10.0);

        if (Math.abs(newMinRange - oldMinRange) > 1e-9) {
            if (setNumeric(cfg, "setMinRangePct", newMinRange)) changed = true;
        }

        double oldLow = toDouble(readGetter(cfg, "getEntryFromLowPct"));
        double oldHigh = toDouble(readGetter(cfg, "getEntryFromHighPct"));

        double newLow = clampD(oldLow * 0.70, 0.1, 30.0);
        double newHigh = clampD(oldHigh * 0.70, 0.1, 30.0);

        if (newLow + newHigh > 60.0) {
            double k = 60.0 / (newLow + newHigh);
            newLow *= k;
            newHigh *= k;
        }

        if (Math.abs(newLow - oldLow) > 1e-9) {
            if (setNumeric(cfg, "setEntryFromLowPct", newLow)) changed = true;
        }
        if (Math.abs(newHigh - oldHigh) > 1e-9) {
            if (setNumeric(cfg, "setEntryFromHighPct", newHigh)) changed = true;
        }

        double oldSpread = toDouble(readGetter(cfg, "getMaxSpreadPct"));
        double newSpread = clampD(oldSpread + 0.05, 0.0, 5.0);
        if (Math.abs(newSpread - oldSpread) > 1e-9) {
            if (setNumeric(cfg, "setMaxSpreadPct", newSpread)) changed = true;
        }

        Integer w = cfg.getWindowSize();
        Integer newWObj = w;
        if (w != null && w > 20) {
            int newW = clampInt((int) Math.round(w * 0.85), 5, 250);
            if (newW != w) {
                cfg.setWindowSize(newW);
                newWObj = newW;
                changed = true;
            }
        }

        if (changed) {
            log.info("[WS-TUNER] 🧱 COARSE_ADJUST chatId={} ex={} net={} sym={} tf={} reason={} | minRangePct {} -> {} | entryLow {} -> {} | entryHigh {} -> {} | maxSpread {} -> {} | window {} -> {}",
                    chatId, ex, net, symbol, tf, String.valueOf(reason),
                    fmt(oldMinRange), fmt(toDouble(readGetter(cfg, "getMinRangePct"))),
                    fmt(oldLow), fmt(toDouble(readGetter(cfg, "getEntryFromLowPct"))),
                    fmt(oldHigh), fmt(toDouble(readGetter(cfg, "getEntryFromHighPct"))),
                    fmt(oldSpread), fmt(toDouble(readGetter(cfg, "getMaxSpreadPct"))),
                    w, newWObj
            );
        }

        return changed;
    }

    // =====================================================
    // candidate / compare
    // =====================================================

    private record Candidate(Map<String, Object> params, double score, Integer trades, String tag) {}

    private boolean isBetter(double scoreA, Integer tradesA, double scoreB, Integer tradesB) {
        int ta = tradesA != null ? tradesA : -1;
        int tb = tradesB != null ? tradesB : -1;

        if (tb <= 0 && ta > 0) return true;
        if (tb > 0 && ta <= 0) return false;

        return scoreA > scoreB;
    }

    private boolean shouldApply(BigDecimal baseScore, BigDecimal delta) {
        if (delta == null) return false;

        BigDecimal baselineTooBadScore = nz(props.getBaselineTooBadScore(), "-999999");
        BigDecimal baselineTooBadMinDelta = nz(props.getBaselineTooBadMinDelta(), "0.02");

        if (baseScore != null && baseScore.compareTo(baselineTooBadScore) <= 0) {
            return delta.compareTo(baselineTooBadMinDelta) >= 0;
        }

        BigDecimal minAbs = nz(props.getMinAbsImprove(), "0.02");
        if (delta.compareTo(minAbs) < 0) return false;

        BigDecimal absBase = baseScore != null ? baseScore.abs() : BigDecimal.ONE;
        if (absBase.compareTo(new BigDecimal("0.000001")) < 0) absBase = BigDecimal.ONE;

        BigDecimal rel = delta.divide(absBase, 8, RoundingMode.HALF_UP);
        BigDecimal minRel = nz(props.getMinRelImprove(), "0.03");

        return rel.compareTo(minRel) >= 0;
    }

    private void mutateCandidate(Map<String, Object> p, Random rnd) {

        int windowJitter = 10;
        double entryJitter = 2.0;
        double minRangeJitter = 0.25;
        double spreadJitter = 0.25;
        double riskJitter = 0.15;
        double rrJitter = 0.20;
        int leverageJitter = 1;
        int cooldownJitter = 60;

        int w = intOf(p.get("windowSize"), 40);
        w = clampInt(w + rndInt(rnd, -windowJitter, windowJitter), 5, 250);
        p.put("windowSize", w);

        double low = dblOf(p.get("entryFromLowPct"), 1.2);
        double high = dblOf(p.get("entryFromHighPct"), 1.2);

        low = clampD(low + rndD(rnd, -entryJitter, entryJitter), 0.1, 30.0);
        high = clampD(high + rndD(rnd, -entryJitter, entryJitter), 0.1, 30.0);

        if (low + high > 60.0) {
            double k = 60.0 / (low + high);
            low *= k;
            high *= k;
        }

        p.put("entryFromLowPct", low);
        p.put("entryFromHighPct", high);

        double minR = dblOf(p.get("minRangePct"), 0.35);
        minR = clampD(minR + rndD(rnd, -minRangeJitter, minRangeJitter), 0.0, 10.0);
        p.put("minRangePct", minR);

        double maxSp = dblOf(p.get("maxSpreadPct"), 0.30);
        maxSp = clampD(maxSp + rndD(rnd, -spreadJitter, spreadJitter), 0.0, 5.0);
        p.put("maxSpreadPct", maxSp);

        BigDecimal risk = bd(p.get("riskPerTradePct"));
        if (risk == null) risk = new BigDecimal("1.0");
        risk = clampBD(risk.add(bd(rndD(rnd, -riskJitter, riskJitter))),
                new BigDecimal("0.1"), new BigDecimal("10.0"));
        p.put("riskPerTradePct", risk);

        BigDecimal rr = bd(p.get("minRiskReward"));
        if (rr == null) rr = new BigDecimal("1.5");
        rr = clampBD(rr.add(bd(rndD(rnd, -rrJitter, rrJitter))),
                new BigDecimal("0.5"), new BigDecimal("6.0"));
        p.put("minRiskReward", rr);

        int lev = intOf(p.get("leverage"), 1);
        lev = clampInt(lev + rndInt(rnd, -leverageJitter, leverageJitter), 1, 25);
        p.put("leverage", lev);

        Boolean avg = boolOf(p.get("allowAveraging"));
        if (avg == null) avg = Boolean.FALSE;
        if (rnd.nextDouble() < 0.10) avg = !avg;
        p.put("allowAveraging", avg);

        Integer cd = intObjOf(p.get("cooldownAfterLossSeconds"));
        if (cd == null) cd = 0;
        cd = clampInt(cd + rndInt(rnd, -cooldownJitter, cooldownJitter), 0, 3600);
        p.put("cooldownAfterLossSeconds", cd);

        Integer mcl = intObjOf(p.get("maxConsecutiveLosses"));
        if (mcl == null) mcl = 3;
        if (rnd.nextDouble() < 0.20) {
            mcl = clampInt(mcl + (rnd.nextBoolean() ? 1 : -1), 1, 15);
        }
        p.put("maxConsecutiveLosses", mcl);
    }

    // =====================================================
    // params <-> settings
    // =====================================================

    private Map<String, Object> buildParamsFromCurrent(StrategySettings ss, WindowScalpingStrategySettings cfg) {
        Map<String, Object> p = new HashMap<>();

        // cfg (может быть double/Double/BigDecimal — нам всё равно, кладём как есть)
        p.put("windowSize", cfg.getWindowSize());
        p.put("entryFromLowPct", readGetter(cfg, "getEntryFromLowPct"));
        p.put("entryFromHighPct", readGetter(cfg, "getEntryFromHighPct"));
        p.put("minRangePct", readGetter(cfg, "getMinRangePct"));
        p.put("maxSpreadPct", readGetter(cfg, "getMaxSpreadPct"));
        p.put("takeProfitPct", cfg.getTakeProfitPct());
        p.put("stopLossPct", cfg.getStopLossPct());

        // ss
        p.put("riskPerTradePct", ss.getRiskPerTradePct());
        p.put("minRiskReward", ss.getMinRiskReward());
        p.put("leverage", ss.getLeverage());
        p.put("allowAveraging", ss.getAllowAveraging());
        p.put("cooldownAfterLossSeconds", ss.getCooldownAfterLossSeconds());
        p.put("maxConsecutiveLosses", ss.getMaxConsecutiveLosses());
        p.put("maxDrawdownPct", ss.getMaxDrawdownPct());

        return p;
    }

    private void applyToSettings(StrategySettings ss, WindowScalpingStrategySettings cfg, Map<String, Object> p) {

        Integer w = intObjOf(p.get("windowSize"));
        if (w != null && w > 0) cfg.setWindowSize(w);

        setNumeric(cfg, "setEntryFromLowPct", dblOf(p.get("entryFromLowPct"), toDouble(readGetter(cfg, "getEntryFromLowPct"))));
        setNumeric(cfg, "setEntryFromHighPct", dblOf(p.get("entryFromHighPct"), toDouble(readGetter(cfg, "getEntryFromHighPct"))));
        setNumeric(cfg, "setMinRangePct", dblOf(p.get("minRangePct"), toDouble(readGetter(cfg, "getMinRangePct"))));
        setNumeric(cfg, "setMaxSpreadPct", dblOf(p.get("maxSpreadPct"), toDouble(readGetter(cfg, "getMaxSpreadPct"))));

        BigDecimal tp = bd(p.get("takeProfitPct"));
        BigDecimal sl = bd(p.get("stopLossPct"));
        if (tp != null && tp.signum() > 0) cfg.setTakeProfitPct(tp);
        if (sl != null && sl.signum() > 0) cfg.setStopLossPct(sl);

        BigDecimal risk = bd(p.get("riskPerTradePct"));
        if (risk != null && risk.signum() > 0) ss.setRiskPerTradePct(risk);

        BigDecimal rr = bd(p.get("minRiskReward"));
        if (rr != null && rr.signum() > 0) ss.setMinRiskReward(rr);

        Integer lev = intObjOf(p.get("leverage"));
        if (lev != null && lev > 0) ss.setLeverage(lev);

        Boolean avg = boolOf(p.get("allowAveraging"));
        if (avg != null) ss.setAllowAveraging(avg);

        Integer cd = intObjOf(p.get("cooldownAfterLossSeconds"));
        if (cd != null && cd >= 0) ss.setCooldownAfterLossSeconds(cd);

        Integer mcl = intObjOf(p.get("maxConsecutiveLosses"));
        if (mcl != null && mcl > 0) ss.setMaxConsecutiveLosses(mcl);

        BigDecimal dd = bd(p.get("maxDrawdownPct"));
        if (dd != null && dd.signum() > 0) ss.setMaxDrawdownPct(dd);
    }

    // =====================================================
    // backtest
    // =====================================================

    private BacktestMetrics safeRunBacktest(Long chatId,
                                            String exchange,
                                            NetworkType network,
                                            String symbol,
                                            String timeframe,
                                            Map<String, Object> params,
                                            Instant start,
                                            Instant end) {

        try {
            return backtestRunner.run(
                    chatId,
                    StrategyType.WINDOW_SCALPING,
                    exchange,
                    network,
                    symbol,
                    timeframe,
                    params,
                    start,
                    end
            );
        } catch (Exception e) {
            log.warn("[WS-TUNER] backtest fail: {}", e.getMessage());
            return BacktestMetrics.fail("runner_error: " + e.getMessage());
        }
    }

    // =====================================================
    // persist (reflection-safe)
    // =====================================================

    private void persistSafe(Object service, Object entity) {
        if (service == null || entity == null) return;

        String[] methods = {"save", "saveOrUpdate", "update", "persist"};
        for (String m : methods) {
            if (tryInvoke1(service, m, entity)) return;
        }

        try {
            Method getRepo = service.getClass().getMethod("getRepository");
            Object repo = getRepo.invoke(service);
            if (repo != null) {
                if (tryInvoke1(repo, "save", entity)) return;
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private boolean tryInvoke1(Object target, String method, Object arg) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(method)) continue;
                if (m.getParameterCount() != 1) continue;

                Class<?> pt = m.getParameterTypes()[0];
                if (!pt.isAssignableFrom(arg.getClass())) continue;

                m.invoke(target, arg);
                return true;
            }
        } catch (Exception ignore) {
            // ignore
        }
        return false;
    }

    // =====================================================
    // logs
    // =====================================================

    private void logSkip(Long chatId, String ex, NetworkType net, String sym, String tf,
                         BigDecimal base, BigDecimal best, BigDecimal delta, String reason) {

        String msg = String.format(Locale.ROOT,
                "[WS-TUNER] SKIP chatId=%d ex=%s net=%s sym=%s tf=%s base=%s best=%s delta=%s reason=%s",
                chatId, ex, net, sym, tf, strip(base), strip(best), strip(delta), reason
        );

        if (props.isLogSkipAsInfo()) log.info(msg);
        else log.debug(msg);
    }

    // =====================================================
    // utils
    // =====================================================

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    private static String safeSym(String s) {
        if (s == null) return "BTCUSDT";
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? "BTCUSDT" : x;
    }

    private static String safeTf(String s) {
        if (s == null) return "1m";
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() ? "1m" : x;
    }

    private static String firstNonBlank(String a, String b) {
        String x = safe(a);
        if (x != null) return x;
        return safe(b);
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return null;
    }

    private static BigDecimal nz(BigDecimal v, String def) {
        if (v != null) return v;
        return new BigDecimal(def);
    }

    private static String strip(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }

    private static int intOf(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return def;
    }

    private static Integer intObjOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return null;
    }

    private static double dblOf(Object v, double def) {
        if (v == null) return def;
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return def;
    }

    private static Boolean boolOf(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
        return null;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BigDecimal clampBD(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v == null) return null;
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }

    private static int rndInt(Random r, int lo, int hi) {
        if (hi < lo) { int t = lo; lo = hi; hi = t; }
        return lo + r.nextInt((hi - lo) + 1);
    }

    private static double rndD(Random r, double lo, double hi) {
        if (hi < lo) { double t = lo; lo = hi; hi = t; }
        return lo + (hi - lo) * r.nextDouble();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    // =====================================================
    // ✅ reflection numeric setters (поддержка double/Double/BigDecimal)
    // =====================================================

    private static Object readGetter(Object target, String getterName) {
        if (target == null || getterName == null) return null;
        try {
            Method m = target.getClass().getMethod(getterName);
            return m.invoke(target);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return 0.0;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception ignore) {}
        return null;
    }

    private static boolean setNumeric(Object target, String setterName, double value) {
        if (target == null || setterName == null) return false;

        // пробуем double/Double/BigDecimal
        if (trySet(target, setterName, value, double.class)) return true;
        if (trySet(target, setterName, value, Double.class)) return true;

        BigDecimal bd = BigDecimal.valueOf(value);
        if (trySet(target, setterName, bd, BigDecimal.class)) return true;

        // fallback: Number
        if (trySetAssignable(target, setterName, Double.valueOf(value))) return true;

        return false;
    }

    private static boolean trySet(Object target, String setterName, Object arg, Class<?> paramType) {
        try {
            Method m = target.getClass().getMethod(setterName, paramType);
            m.invoke(target, arg);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static boolean trySetAssignable(Object target, String setterName, Object arg) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(setterName)) continue;
                if (m.getParameterCount() != 1) continue;

                Class<?> pt = m.getParameterTypes()[0];

                Object coerced = coerceNumber(arg, pt);
                if (coerced == null) continue;

                m.invoke(target, coerced);
                return true;
            }
        } catch (Exception ignore) {
            // ignore
        }
        return false;
    }

    private static Object coerceNumber(Object value, Class<?> targetType) {
        if (value == null || targetType == null) return null;

        if (targetType.isInstance(value)) return value;

        if (targetType == double.class || targetType == Double.class) {
            return toDouble(value);
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) toDouble(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return (int) Math.round(toDouble(value));
        }
        if (targetType == long.class || targetType == Long.class) {
            return (long) Math.round(toDouble(value));
        }
        if (targetType == BigDecimal.class) {
            return toBigDecimal(value);
        }

        if (Number.class.isAssignableFrom(targetType)) {
            double d = toDouble(value);
            if (targetType == Short.class) return (short) Math.round(d);
            if (targetType == Byte.class) return (byte) Math.round(d);
            return Double.valueOf(d);
        }

        return null;
    }
}

package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.ai.tuning.StrategyAutoTuner;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingAutoTuner implements StrategyAutoTuner {

    private static final int MIN_WINDOW = 6;
    private static final int MAX_WINDOW = 120;
    private static final int MIN_REQUIRED_CANDLES = 60;

    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final MarketDataStreamService marketDataStreamService;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.SCALPING;
    }

    @Override
    public TuningResult tune(TuningRequest request) {
        if (request == null) {
            return result(false, "request_null");
        }

        long chatId = readLong(request, "getChatId", "chatId");
        if (chatId <= 0) {
            return result(false, "chatId_invalid");
        }

        StrategySettings strategySettings = loadStrategySettings(chatId);
        if (strategySettings == null) {
            return result(false, "strategy_settings_missing");
        }

        String exchange = firstNonBlank(
                readString(request, "getExchange", "exchange"),
                strategySettings.getExchangeName(),
                "BINANCE"
        );
        NetworkType network = readNetwork(request, strategySettings.getNetworkType());
        String symbol = firstNonBlank(
                readString(request, "getSymbol", "symbol"),
                strategySettings.getSymbol()
        );
        String timeframe = normalizeTf(firstNonBlank(
                readString(request, "getTimeframe", "timeframe"),
                strategySettings.getTimeframe(),
                "1m"
        ));

        if (symbol == null || exchange == null || network == null || timeframe == null) {
            return result(false, "context_incomplete");
        }

        int candlesLimit = readInt(request, "getCandlesLimit", "candlesLimit");
        if (candlesLimit <= 0) {
            candlesLimit = strategySettings.getCachedCandlesLimit() != null
                    ? strategySettings.getCachedCandlesLimit()
                    : 500;
        }
        candlesLimit = Math.max(candlesLimit, 180);
        candlesLimit = Math.min(candlesLimit, 1000);

        List<Candle> candles = marketDataStreamService.getCachedCandles(
                chatId,
                StrategyType.SCALPING,
                exchange,
                network,
                symbol,
                timeframe,
                candlesLimit
        );

        if (candles == null || candles.isEmpty()) {
            return result(false, "no_cached_candles");
        }

        ScalpingStrategySettings base = scalpingSettingsService.getEffective(chatId);
        if (base == null) {
            return result(false, "scalping_settings_missing");
        }

        List<BigDecimal> closes = extractClosedCloses(candles);
        if (closes.size() < MIN_REQUIRED_CANDLES) {
            return result(false, "not_enough_closed_candles:" + closes.size());
        }

        String requestReason = normalizeReason(readString(request, "getReason", "reason"));

        Eval baseline = evaluate(closes, base);
        ScalpingStrategySettings candidate = copy(base);
        boolean changed = mutate(candidate, baseline, timeframe, requestReason);

        if (!changed) {
            return result(false, "no_change_needed|ready=" + baseline.readySignals + " total=" + baseline.totalSignals + " block=" + baseline.dominantBlockReason);
        }

        normalize(candidate, timeframe);
        Eval tuned = evaluate(closes, candidate);

        boolean improved =
                tuned.readySignals > baseline.readySignals
                        || tuned.readyRatio() > baseline.readyRatio() + 0.015d
                        || (baseline.readySignals == 0 && tuned.readySignals > 0);

        boolean adaptiveAcceptable = isAdaptiveReason(requestReason) && isAdaptiveCandidateAcceptable(baseline, tuned);

        if (!improved && !adaptiveAcceptable) {
            return result(false,
                    "no_improvement|before=" + baseline.readySignals + "/" + baseline.totalSignals
                            + " after=" + tuned.readySignals + "/" + tuned.totalSignals
                            + " blockBefore=" + baseline.dominantBlockReason
                            + " blockAfter=" + tuned.dominantBlockReason);
        }

        candidate.setId(base.getId());
        candidate.setChatId(base.getChatId());
        candidate.setVersion(base.getVersion());
        candidate.setActive(base.getActive());
        candidate.setSymbol(firstNonBlank(base.getSymbol(), symbol));
        candidate.setTimeframe(firstNonBlank(base.getTimeframe(), timeframe));
        candidate.setCachedCandlesLimit(base.getCachedCandlesLimit());
        candidate.setCreatedAt(base.getCreatedAt());

        scalpingSettingsService.save(candidate);

        String reason = (improved ? "applied" : "adaptive_applied") + "|before=" + baseline.readySignals + "/" + baseline.totalSignals
                + " after=" + tuned.readySignals + "/" + tuned.totalSignals
                + " blockBefore=" + baseline.dominantBlockReason
                + " blockAfter=" + tuned.dominantBlockReason
                + " trigger=" + requestReason
                + " score=" + tuned.avgScore.stripTrailingZeros().toPlainString();

        log.info("🧠 [SCALPING-TUNER] chatId={} ex={} net={} sym={} tf={} {}", chatId, exchange, network, symbol, timeframe, reason);
        return result(true, reason);
    }

    private Eval evaluate(List<BigDecimal> closes, ScalpingStrategySettings cfg) {
        int window = effectiveWindow(cfg != null ? cfg.getWindowSize() : null);
        Deque<BigDecimal> deque = new ArrayDeque<>();

        int totalSignals = 0;
        int readySignals = 0;
        BigDecimal scoreSum = BigDecimal.ZERO;
        Map<String, Integer> blockers = new LinkedHashMap<>();

        for (BigDecimal close : closes) {
            if (close == null || close.signum() <= 0) {
                continue;
            }
            deque.addLast(close);
            while (deque.size() > window) {
                deque.removeFirst();
            }
            if (deque.size() < window) {
                continue;
            }

            ScalpingFeatureSnapshot f = ScalpingFeatureCalculator.calculate(deque, cfg, Instant.now());
            if (f == null) {
                continue;
            }

            totalSignals++;
            scoreSum = scoreSum.add(f.score() != null ? f.score() : BigDecimal.ZERO);

            String reason = blockReason(cfg, f);
            if (reason == null) {
                readySignals++;
            } else {
                blockers.merge(reason, 1, Integer::sum);
            }
        }

        BigDecimal avgScore = totalSignals > 0
                ? scoreSum.divide(BigDecimal.valueOf(totalSignals), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        String dominant = blockers.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("READY");

        return new Eval(totalSignals, readySignals, avgScore, dominant);
    }

    private boolean mutate(ScalpingStrategySettings s, Eval baseline, String timeframe, String requestReason) {
        if (s == null) {
            return false;
        }

        boolean changed = false;
        double readyRatio = baseline.readyRatio();
        String block = baseline.dominantBlockReason == null ? "" : baseline.dominantBlockReason;

        if (readyRatio < 0.08d) {
            changed |= shrinkWindow(s, 0.80d, 24);
            changed |= lowerMinImpulse(s, 0.70d, 0.01d);
            changed |= lowerVolumeRatio(s, 0.92d, 0.82d);
            changed |= lowerEmaDiff(s, 0.82d, 0.01d);
            changed |= raiseSpreadLimit(s, 1.10d, 0.45d);
            changed |= raiseAtrLimit(s, 1.12d, 1.20d);
            changed |= lowerRsiFilter(s, 4.0d, 34.0d);
            changed |= lowerRiskRewardMin(s, 0.95d, 1.00d);
        } else if (readyRatio > 0.28d) {
            changed |= raiseMinImpulse(s, 1.08d, 0.25d);
            changed |= raiseVolumeRatio(s, 1.04d, 1.20d);
            changed |= raiseEmaDiff(s, 1.06d, 0.12d);
        }

        if ("impulse_below_min".equals(block)) {
            changed |= lowerMinImpulse(s, 0.60d, 0.008d);
            changed |= shrinkWindow(s, 0.85d, 18);
        } else if ("rsi_filter_block".equals(block) || "rsi_mid_weak".equals(block)) {
            changed |= lowerRsiFilter(s, 6.0d, 30.0d);
        } else if ("volume_ratio_low".equals(block)) {
            changed |= lowerVolumeRatio(s, 0.88d, 0.80d);
        } else if ("ema_trend_weak".equals(block)) {
            changed |= lowerEmaDiff(s, 0.75d, 0.008d);
        } else if ("outside_entry_zone".equals(block) || "not_close_to_window_high".equals(block)) {
            changed |= shrinkWindow(s, 0.85d, 18);
        }

        if (isAdaptiveReason(requestReason)) {
            changed |= adaptByRuntimeReason(s, block, requestReason);
        }

        if ("1m".equals(timeframe) || "3m".equals(timeframe)) {
            changed |= clampTakeProfitDown(s, 0.30d);
            changed |= clampStopLossDown(s, 0.20d);
            changed |= clampWindowDown(s, 72);
            changed |= clampRiskRewardDown(s, 1.15d);
        }

        return changed;
    }


    public void adjustCoarseFilters(TuningRequest request) {
        applyRelaxedProfile(request, "coarse_filters");
    }

    public void onNoTrades(TuningRequest request) {
        applyRelaxedProfile(request, "no_trades");
    }

    private void applyRelaxedProfile(TuningRequest request, String source) {
        if (request == null) {
            return;
        }

        long chatId = readLong(request, "getChatId", "chatId");
        if (chatId <= 0) {
            return;
        }

        ScalpingStrategySettings base = scalpingSettingsService.getEffective(chatId);
        if (base == null) {
            return;
        }

        String requestReason = normalizeReason(readString(request, "getReason", "reason"));
        String timeframe = normalizeTf(firstNonBlank(
                readString(request, "getTimeframe", "timeframe"),
                base.getTimeframe(),
                "1m"
        ));

        ScalpingStrategySettings candidate = copy(base);
        boolean changed = adaptByRuntimeReason(candidate, normalizeReason(requestReason), requestReason);
        if (!changed) {
            changed |= lowerMinImpulse(candidate, 0.80d, 0.006d);
            changed |= lowerEmaDiff(candidate, 0.80d, 0.006d);
            changed |= lowerVolumeRatio(candidate, 0.92d, 0.78d);
            changed |= shrinkWindow(candidate, 0.85d, 18);
        }

        normalize(candidate, timeframe);
        if (!changed || settingsEqualForRuntime(base, candidate)) {
            return;
        }

        candidate.setId(base.getId());
        candidate.setChatId(base.getChatId());
        candidate.setVersion(base.getVersion());
        candidate.setActive(base.getActive());
        candidate.setSymbol(base.getSymbol());
        candidate.setTimeframe(base.getTimeframe());
        candidate.setCachedCandlesLimit(base.getCachedCandlesLimit());
        candidate.setCreatedAt(base.getCreatedAt());

        scalpingSettingsService.save(candidate);

        log.warn("🧠 [SCALPING-TUNER] runtime relax applied chatId={} source={} reason={} window={} impulse={} emaDiff={} volumeRatio={} spread={} atr={} rsi={} rr={} tp={} sl={}",
                chatId,
                source,
                requestReason,
                candidate.getWindowSize(),
                candidate.getMinImpulsePct(),
                candidate.getEmaDiffThreshold(),
                candidate.getVolumeRatio(),
                candidate.getSpreadLimitPct(),
                candidate.getAtrPctRange(),
                candidate.getRsiFilter(),
                candidate.getRiskRewardMin(),
                candidate.getTakeProfitPct(),
                candidate.getStopLossPct());
    }

    private String blockReason(ScalpingStrategySettings cfg, ScalpingFeatureSnapshot f) {
        if (cfg == null || f == null) {
            return "no_features";
        }

        double minImpulsePct = Math.max(0.005d, normalizePercentLike(cfg.getMinImpulsePct()));
        double emaDiffThreshold = Math.max(0.0005d, normalizeEmaThreshold(cfg.getEmaDiffThreshold()));
        double spreadLimitPct = Math.max(0.02d, normalizePercentLike(cfg.getSpreadLimitPct()));
        double atrLimitPct = Math.max(0.10d, normalizePercentLike(cfg.getAtrPctRange()));
        double volumeRatio = nz(cfg.getVolumeRatio(), 1.0d);
        double rsiFilter = nz(cfg.getRsiFilter(), 38.0d);
        double rrMin = Math.max(1.0d, nz(cfg.getRiskRewardMin(), 1.1d));

        if (f.spreadPct().doubleValue() > spreadLimitPct) return "spread_too_wide";
        if (f.atrPct().doubleValue() > atrLimitPct) return "atr_too_high";
        if (f.riskRewardRatio().doubleValue() < rrMin) return "risk_reward_low";
        if (f.rsi().doubleValue() >= 82.0d) return "rsi_too_hot";

        double commonVolumeNeed = Math.max(0.82d, Math.min(volumeRatio, 1.10d));
        double breakoutDistance = Math.max(0.06d, f.windowRange().doubleValue() * 0.35d);
        double reboundDistance = Math.max(0.10d, f.windowRange().doubleValue() * 0.30d);

        double momentumRsiMin = Math.max(40.0d, rsiFilter - 2.0d);
        double reboundRsiMin = Math.max(18.0d, rsiFilter - 22.0d);
        double reboundRsiMax = Math.min(48.0d, Math.max(rsiFilter + 6.0d, 44.0d));

        double momentumImpulseNeed = minImpulsePct;
        double reboundImpulseNeed = Math.max(0.005d, minImpulsePct * 0.55d);
        double reboundEmaNeed = Math.max(0.0005d, emaDiffThreshold * 0.65d);

        boolean liquidEnough = f.volumeToAverage().doubleValue() >= commonVolumeNeed;

        boolean momentumSetup =
                liquidEnough
                        && f.priceChangePct().doubleValue() >= momentumImpulseNeed
                        && f.emaDiff().doubleValue() >= emaDiffThreshold
                        && f.rsi().doubleValue() >= momentumRsiMin
                        && f.priceFromWindowHigh().doubleValue() <= breakoutDistance;

        boolean reboundSetup =
                liquidEnough
                        && f.priceFromWindowLow().doubleValue() <= reboundDistance
                        && f.rsi().doubleValue() >= reboundRsiMin
                        && f.rsi().doubleValue() <= reboundRsiMax
                        && (f.priceChangePct().doubleValue() >= reboundImpulseNeed
                        || f.emaDiff().doubleValue() >= reboundEmaNeed);

        if (momentumSetup || reboundSetup) {
            return null;
        }

        if (!liquidEnough) return "volume_ratio_low";
        if (f.priceChangePct().doubleValue() < reboundImpulseNeed) return "impulse_below_min";
        if (f.emaDiff().doubleValue() < reboundEmaNeed) return "ema_trend_weak";

        boolean farFromBreakout = f.priceFromWindowHigh().doubleValue() > breakoutDistance;
        boolean farFromRebound = f.priceFromWindowLow().doubleValue() > reboundDistance;
        if (farFromBreakout && farFromRebound) return "outside_entry_zone";

        if (f.rsi().doubleValue() < reboundRsiMin) return "rsi_too_cold";
        if (f.rsi().doubleValue() > reboundRsiMax && f.rsi().doubleValue() < momentumRsiMin) return "rsi_filter_block";

        return "setup_not_ready";
    }


    private static boolean adaptByRuntimeReason(ScalpingStrategySettings s, String dominantBlockReason, String requestReason) {
        if (s == null) {
            return false;
        }

        boolean changed = false;
        String block = normalizeReason(dominantBlockReason);
        String reason = normalizeReason(requestReason);

        if (block.contains("impulse_below_min") || reason.contains("impulse_below_min")) {
            changed |= lowerMinImpulse(s, 0.55d, 0.006d);
            changed |= lowerEmaDiff(s, 0.85d, 0.006d);
            changed |= shrinkWindow(s, 0.82d, 16);
        }
        if (block.contains("volume_ratio_low") || reason.contains("volume_ratio_low")) {
            changed |= lowerVolumeRatio(s, 0.84d, 0.75d);
        }
        if (block.contains("ema_trend_weak") || reason.contains("ema_trend_weak")) {
            changed |= lowerEmaDiff(s, 0.72d, 0.005d);
        }
        if (block.contains("outside_entry_zone") || reason.contains("outside_entry_zone")) {
            changed |= shrinkWindow(s, 0.78d, 14);
            changed |= lowerMinImpulse(s, 0.78d, 0.006d);
        }
        if (block.contains("rsi_filter_block") || block.contains("rsi_too_cold") || reason.contains("rsi_")) {
            changed |= lowerRsiFilter(s, 5.0d, 24.0d);
        }

        if (reason.startsWith("starvation:") || reason.startsWith("regime_shift:")) {
            changed |= lowerMinImpulse(s, 0.75d, 0.006d);
            changed |= lowerEmaDiff(s, 0.80d, 0.005d);
            changed |= lowerVolumeRatio(s, 0.92d, 0.74d);
            changed |= lowerRiskRewardMin(s, 0.96d, 1.00d);
            changed |= shrinkWindow(s, 0.88d, 14);
        }

        if (reason.startsWith("profit_expand:")) {
            changed |= lowerMinImpulse(s, 0.92d, 0.006d);
            changed |= lowerVolumeRatio(s, 0.96d, 0.78d);
        }

        return changed;
    }

    private static boolean isAdaptiveReason(String requestReason) {
        String reason = normalizeReason(requestReason);
        return reason.startsWith("starvation:")
                || reason.startsWith("regime_shift:")
                || reason.startsWith("loss_recovery:")
                || reason.startsWith("profit_expand:")
                || reason.startsWith("coarse_filters")
                || reason.startsWith("no_trades");
    }

    private static boolean isAdaptiveCandidateAcceptable(Eval baseline, Eval tuned) {
        if (baseline == null || tuned == null) {
            return false;
        }
        if (tuned.readySignals > baseline.readySignals) {
            return true;
        }
        if (baseline.readySignals <= 0) {
            return tuned.readySignals > 0 || tuned.totalSignals > baseline.totalSignals;
        }
        int allowedDrop = Math.max(6, (int) Math.ceil(baseline.readySignals * 0.08d));
        return tuned.readySignals >= (baseline.readySignals - allowedDrop);
    }

    private static boolean settingsEqualForRuntime(ScalpingStrategySettings a, ScalpingStrategySettings b) {
        if (a == null || b == null) {
            return false;
        }
        return java.util.Objects.equals(a.getWindowSize(), b.getWindowSize())
                && java.util.Objects.equals(a.getMinImpulsePct(), b.getMinImpulsePct())
                && java.util.Objects.equals(a.getEmaDiffThreshold(), b.getEmaDiffThreshold())
                && java.util.Objects.equals(a.getVolumeRatio(), b.getVolumeRatio())
                && java.util.Objects.equals(a.getSpreadLimitPct(), b.getSpreadLimitPct())
                && java.util.Objects.equals(a.getAtrPctRange(), b.getAtrPctRange())
                && java.util.Objects.equals(a.getRsiFilter(), b.getRsiFilter())
                && java.util.Objects.equals(a.getRiskRewardMin(), b.getRiskRewardMin())
                && java.util.Objects.equals(a.getTakeProfitPct(), b.getTakeProfitPct())
                && java.util.Objects.equals(a.getStopLossPct(), b.getStopLossPct());
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<BigDecimal> extractClosedCloses(List<Candle> candles) {
        List<BigDecimal> out = new ArrayList<>();
        if (candles == null) {
            return out;
        }
        for (Candle candle : candles) {
            if (candle == null || !candle.isClosed()) {
                continue;
            }
            BigDecimal close = BigDecimal.valueOf(candle.getClose());
            if (close.signum() > 0) {
                out.add(close);
            }
        }
        return out;
    }

    private static ScalpingStrategySettings copy(ScalpingStrategySettings s) {
        return ScalpingStrategySettings.builder()
                .id(s.getId())
                .chatId(s.getChatId())
                .version(s.getVersion())
                .active(s.getActive())
                .windowSize(s.getWindowSize())
                .minImpulsePct(s.getMinImpulsePct())
                .emaDiffThreshold(s.getEmaDiffThreshold())
                .volumeRatio(s.getVolumeRatio())
                .spreadLimitPct(s.getSpreadLimitPct())
                .atrPctRange(s.getAtrPctRange())
                .rsiFilter(s.getRsiFilter())
                .riskRewardMin(s.getRiskRewardMin())
                .orderVolume(s.getOrderVolume())
                .takeProfitPct(s.getTakeProfitPct())
                .stopLossPct(s.getStopLossPct())
                .symbol(s.getSymbol())
                .timeframe(s.getTimeframe())
                .cachedCandlesLimit(s.getCachedCandlesLimit())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private static void normalize(ScalpingStrategySettings s, String timeframe) {
        if (s == null) return;
        if (s.getWindowSize() == null || s.getWindowSize() < MIN_WINDOW) s.setWindowSize(60);
        if (s.getWindowSize() > MAX_WINDOW) s.setWindowSize(MAX_WINDOW);
        if (s.getMinImpulsePct() == null || s.getMinImpulsePct() <= 0) s.setMinImpulsePct(0.08d);
        if (s.getEmaDiffThreshold() == null || s.getEmaDiffThreshold() <= 0) s.setEmaDiffThreshold(0.05d);
        if (s.getVolumeRatio() == null || s.getVolumeRatio() <= 0) s.setVolumeRatio(1.00d);
        if (s.getSpreadLimitPct() == null || s.getSpreadLimitPct() <= 0) s.setSpreadLimitPct(0.35d);
        if (s.getAtrPctRange() == null || s.getAtrPctRange() <= 0) s.setAtrPctRange(0.90d);
        if (s.getRsiFilter() == null || s.getRsiFilter() <= 0) s.setRsiFilter(38.0d);
        if (s.getRiskRewardMin() == null || s.getRiskRewardMin() <= 0) s.setRiskRewardMin(1.10d);
        if (s.getTakeProfitPct() == null || s.getTakeProfitPct() <= 0) s.setTakeProfitPct(0.28d);
        if (s.getStopLossPct() == null || s.getStopLossPct() <= 0) s.setStopLossPct(0.18d);

        if ("1m".equals(timeframe) || "3m".equals(timeframe)) {
            clampWindowDown(s, 72);
            clampTakeProfitDown(s, 0.30d);
            clampStopLossDown(s, 0.20d);
            clampRiskRewardDown(s, 1.15d);
        }
    }

    private static int effectiveWindow(Integer raw) {
        if (raw == null) return 60;
        if (raw < MIN_WINDOW) return MIN_WINDOW;
        return Math.min(raw, MAX_WINDOW);
    }

    private static double normalizePercentLike(Double raw) {
        if (raw == null) return 0.0d;
        if (raw > 0.0d && raw <= 0.01d) return raw * 100.0d;
        return raw;
    }

    private static double normalizeEmaThreshold(Double raw) {
        if (raw == null) return 0.0d;
        if (raw > 0.0d && raw <= 0.01d) return raw * 100.0d;
        if (raw >= 0.02d && raw <= 1.0d) return raw / 100.0d;
        return raw;
    }

    private static double nz(Double value, double def) {
        return value == null ? def : value;
    }

    private static boolean lowerMinImpulse(ScalpingStrategySettings s, double factor, double floor) {
        double before = nz(s.getMinImpulsePct(), 0.08d);
        double after = Math.max(floor, round(before * factor));
        if (after >= before) return false;
        s.setMinImpulsePct(after);
        return true;
    }

    private static boolean raiseMinImpulse(ScalpingStrategySettings s, double factor, double cap) {
        double before = nz(s.getMinImpulsePct(), 0.08d);
        double after = Math.min(cap, round(before * factor));
        if (after <= before) return false;
        s.setMinImpulsePct(after);
        return true;
    }

    private static boolean lowerEmaDiff(ScalpingStrategySettings s, double factor, double floor) {
        double before = nz(s.getEmaDiffThreshold(), 0.05d);
        double after = Math.max(floor, round(before * factor));
        if (after >= before) return false;
        s.setEmaDiffThreshold(after);
        return true;
    }

    private static boolean raiseEmaDiff(ScalpingStrategySettings s, double factor, double cap) {
        double before = nz(s.getEmaDiffThreshold(), 0.05d);
        double after = Math.min(cap, round(before * factor));
        if (after <= before) return false;
        s.setEmaDiffThreshold(after);
        return true;
    }

    private static boolean lowerVolumeRatio(ScalpingStrategySettings s, double factor, double floor) {
        double before = nz(s.getVolumeRatio(), 1.00d);
        double after = Math.max(floor, round(before * factor));
        if (after >= before) return false;
        s.setVolumeRatio(after);
        return true;
    }

    private static boolean raiseVolumeRatio(ScalpingStrategySettings s, double factor, double cap) {
        double before = nz(s.getVolumeRatio(), 1.00d);
        double after = Math.min(cap, round(before * factor));
        if (after <= before) return false;
        s.setVolumeRatio(after);
        return true;
    }

    private static boolean raiseSpreadLimit(ScalpingStrategySettings s, double factor, double cap) {
        double before = nz(s.getSpreadLimitPct(), 0.35d);
        double after = Math.min(cap, round(before * factor));
        if (after <= before) return false;
        s.setSpreadLimitPct(after);
        return true;
    }

    private static boolean raiseAtrLimit(ScalpingStrategySettings s, double factor, double cap) {
        double before = nz(s.getAtrPctRange(), 0.90d);
        double after = Math.min(cap, round(before * factor));
        if (after <= before) return false;
        s.setAtrPctRange(after);
        return true;
    }

    private static boolean lowerRsiFilter(ScalpingStrategySettings s, double delta, double floor) {
        double before = nz(s.getRsiFilter(), 38.0d);
        double after = Math.max(floor, round(before - delta));
        if (after >= before) return false;
        s.setRsiFilter(after);
        return true;
    }

    private static boolean lowerRiskRewardMin(ScalpingStrategySettings s, double factor, double floor) {
        double before = nz(s.getRiskRewardMin(), 1.10d);
        double after = Math.max(floor, round(before * factor));
        if (after >= before) return false;
        s.setRiskRewardMin(after);
        return true;
    }

    private static boolean clampTakeProfitDown(ScalpingStrategySettings s, double cap) {
        double before = nz(s.getTakeProfitPct(), 0.28d);
        if (before <= cap) return false;
        s.setTakeProfitPct(round(cap));
        return true;
    }

    private static boolean clampStopLossDown(ScalpingStrategySettings s, double cap) {
        double before = nz(s.getStopLossPct(), 0.18d);
        if (before <= cap) return false;
        s.setStopLossPct(round(cap));
        return true;
    }

    private static boolean clampWindowDown(ScalpingStrategySettings s, int cap) {
        int before = s.getWindowSize() == null ? 60 : s.getWindowSize();
        if (before <= cap) return false;
        s.setWindowSize(cap);
        return true;
    }

    private static boolean clampRiskRewardDown(ScalpingStrategySettings s, double cap) {
        double before = nz(s.getRiskRewardMin(), 1.10d);
        if (before <= cap) return false;
        s.setRiskRewardMin(round(cap));
        return true;
    }

    private static boolean shrinkWindow(ScalpingStrategySettings s, double factor, int floor) {
        int before = s.getWindowSize() == null ? 60 : s.getWindowSize();
        int after = Math.max(floor, (int) Math.round(before * factor));
        if (after >= before) return false;
        s.setWindowSize(after);
        return true;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private StrategySettings loadStrategySettings(long chatId) {
        try {
            return strategySettingsService.findAllByChatId(chatId)
                    .stream()
                    .filter(s -> s.getType() == StrategyType.SCALPING)
                    .sorted((a, b) -> {
                        if (a.getUpdatedAt() == null && b.getUpdatedAt() == null) return 0;
                        if (a.getUpdatedAt() == null) return 1;
                        if (b.getUpdatedAt() == null) return -1;
                        return b.getUpdatedAt().compareTo(a.getUpdatedAt());
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("🧠 [SCALPING-TUNER] loadStrategySettings failed chatId={} err={}", chatId, e.toString());
            return null;
        }
    }

    private static String normalizeTf(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value == null) continue;
            String v = value.trim();
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    private static long readLong(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static int readInt(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String readString(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        return value == null ? null : String.valueOf(value);
    }

    private static NetworkType readNetwork(Object target, NetworkType def) {
        Object value = invokeFirst(target, "getNetwork", "network", "getNetworkType", "networkType");
        if (value instanceof NetworkType n) return n;
        if (value != null) {
            try { return NetworkType.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT)); }
            catch (Exception ignored) {}
        }
        return def != null ? def : NetworkType.TESTNET;
    }

    private static Object invokeFirst(Object target, String... methods) {
        if (target == null || methods == null) return null;
        for (String methodName : methods) {
            if (methodName == null || methodName.isBlank()) continue;
            try {
                Method m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static TuningResult result(boolean applied, String reason) {
        try {
            Method builderMethod = TuningResult.class.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            setBuilder(builder, "applied", applied);
            setBuilder(builder, "reason", reason);
            setBuilder(builder, "updatedAt", Instant.now());
            Method build = builder.getClass().getMethod("build");
            return (TuningResult) build.invoke(builder);
        } catch (Exception e) {
            return null;
        }
    }

    private static void setBuilder(Object builder, String methodName, Object value) {
        if (builder == null || methodName == null) return;
        for (Method method : builder.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) continue;
            try {
                method.invoke(builder, value);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private record Eval(int totalSignals, int readySignals, BigDecimal avgScore, String dominantBlockReason) {
        double readyRatio() {
            if (totalSignals <= 0) {
                return 0.0d;
            }
            return readySignals / (double) totalSignals;
        }
    }
}


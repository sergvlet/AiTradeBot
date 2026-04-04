package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.ema.EmaCrossoverStrategySettings;
import com.chicu.aitradebot.strategy.ema.EmaCrossoverStrategySettingsService;
import com.chicu.aitradebot.strategy.ema.EmaMlPreparationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmaCrossoverAutoTuner implements StrategyAutoTuner {

    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("0.80");

    private final StrategySettingsService strategySettingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final EmaMlPreparationService preparationService;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.EMA_CROSSOVER;
    }

    @Override
    public TuningResult tune(TuningRequest request) {
        if (request == null) {
            return TuningResult.builder().applied(false).reason("request=null").build();
        }

        Long chatId = request.chatId();
        if (chatId == null || chatId <= 0) {
            return TuningResult.builder().applied(false).reason("bad_chatId").build();
        }

        String ex = normalizeExchangeOrNull(request.exchange());
        NetworkType net = request.network();
        if (ex == null || net == null) {
            return TuningResult.builder().applied(false).reason("env_missing(exchange/network)").build();
        }

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
        EmaCrossoverStrategySettings cfg = emaSettingsService.getOrCreate(chatId);
        if (ss == null || cfg == null) {
            return TuningResult.builder().applied(false).reason("no_settings").build();
        }

        List<EmaMlPreparationService.CandlePoint> candles = preparationService.loadCandlePoints(
                chatId,
                ss,
                ss.getCachedCandlesLimit() != null ? ss.getCachedCandlesLimit() : 1200
        );
        if (candles.size() < 120) {
            return TuningResult.builder().applied(false).reason("not_enough_candles").build();
        }

        Candidate baseline = Candidate.of(
                clampInt(cfg.getEmaFast() != null ? cfg.getEmaFast() : 9, 1, 300),
                clampInt(cfg.getEmaSlow() != null ? cfg.getEmaSlow() : 21, 2, 600),
                clampInt(cfg.getConfirmBars() != null ? cfg.getConfirmBars() : 1, 1, 10),
                clampDouble(cfg.getMaxSpreadPct() != null ? cfg.getMaxSpreadPct() : 0.08d, 0.0d, 100.0d)
        );

        if (baseline.emaSlow <= baseline.emaFast) {
            baseline = Candidate.of(baseline.emaFast, baseline.emaFast + 1, baseline.confirmBars, baseline.maxSpreadPct);
        }

        BacktestScore base = simulate(candles, baseline);
        Candidate bestCandidate = baseline;
        BacktestScore bestScore = base;

        for (Candidate candidate : buildCandidates(baseline)) {
            BacktestScore score = simulate(candles, candidate);
            if (score.score.compareTo(bestScore.score) > 0) {
                bestCandidate = candidate;
                bestScore = score;
            }
        }

        boolean prepareMode = safeLower(request.reason()).contains("prepare");
        int minTrades = prepareMode ? 3 : 5;
        if (bestScore.trades < minTrades) {
            String reason = "too_few_trades:" + bestScore.trades + "/need:" + minTrades;
            return TuningResult.builder()
                    .applied(false)
                    .scoreBefore(base.score)
                    .scoreAfter(bestScore.score)
                    .modelVersion(safe(ss.getMlModelVersion()))
                    .reason(reason)
                    .build();
        }

        BigDecimal delta = bestScore.score.subtract(base.score).setScale(6, RoundingMode.HALF_UP);
        if (delta.compareTo(new BigDecimal("0.001000")) <= 0) {
            return TuningResult.builder()
                    .applied(false)
                    .scoreBefore(base.score)
                    .scoreAfter(bestScore.score)
                    .modelVersion(safe(ss.getMlModelVersion()))
                    .reason("no_improvement")
                    .build();
        }

        EmaCrossoverStrategySettings patch = EmaCrossoverStrategySettings.builder()
                .chatId(chatId)
                .emaFast(bestCandidate.emaFast)
                .emaSlow(bestCandidate.emaSlow)
                .confirmBars(bestCandidate.confirmBars)
                .maxSpreadPct(bestCandidate.maxSpreadPct)
                .build();
        emaSettingsService.save(chatId, patch);

        log.info("[EMA-TUNER] ✅ APPLIED chatId={} ex={} net={} sym={} tf={} baseScore={} bestScore={} delta={} fast={} slow={} confirm={} spread={} trades={}",
                chatId,
                ex,
                net,
                safeUpper(ss.getSymbol()),
                safeLower(ss.getTimeframe()),
                strip(base.score),
                strip(bestScore.score),
                strip(delta),
                bestCandidate.emaFast,
                bestCandidate.emaSlow,
                bestCandidate.confirmBars,
                bestCandidate.maxSpreadPct,
                bestScore.trades);

        return TuningResult.builder()
                .applied(true)
                .scoreBefore(base.score)
                .scoreAfter(bestScore.score)
                .modelVersion(safe(ss.getMlModelVersion()))
                .reason("applied")
                .build();
    }

    public boolean onNoTrades(TuningRequest request) {
        return adjustCoarseFilters(request);
    }

    public boolean adjustCoarseFilters(TuningRequest request) {
        if (request == null || request.chatId() == null || request.chatId() <= 0) {
            return false;
        }
        Long chatId = request.chatId();
        EmaCrossoverStrategySettings cur = emaSettingsService.getOrCreate(chatId);
        EmaCrossoverStrategySettings patch = EmaCrossoverStrategySettings.builder()
                .chatId(chatId)
                .confirmBars(1)
                .maxSpreadPct(Math.max(0.12d, cur.getMaxSpreadPct() != null ? cur.getMaxSpreadPct() : 0.12d))
                .build();
        emaSettingsService.save(chatId, patch);
        return true;
    }

    private List<Candidate> buildCandidates(Candidate base) {
        List<Candidate> out = new ArrayList<>();
        int[] fasts = unique(
                base.emaFast - 4,
                base.emaFast - 2,
                base.emaFast,
                base.emaFast + 2,
                base.emaFast + 4,
                5,
                9,
                12,
                15
        );
        int[] slows = unique(
                base.emaSlow - 8,
                base.emaSlow - 4,
                base.emaSlow,
                base.emaSlow + 4,
                base.emaSlow + 8,
                21,
                34,
                55
        );
        int[] confirms = unique(base.confirmBars, 1, 2, 3);
        double[] spreads = unique(base.maxSpreadPct, 0.03d, 0.05d, 0.08d, 0.12d, 0.18d);

        for (int fast : fasts) {
            fast = clampInt(fast, 1, 300);
            for (int slow : slows) {
                slow = clampInt(slow, 2, 600);
                if (slow <= fast) continue;
                for (int confirm : confirms) {
                    confirm = clampInt(confirm, 1, 10);
                    for (double spread : spreads) {
                        spread = clampDouble(spread, 0.0d, 100.0d);
                        out.add(Candidate.of(fast, slow, confirm, spread));
                    }
                }
            }
        }
        return out;
    }

    private BacktestScore simulate(List<EmaMlPreparationService.CandlePoint> candles, Candidate candidate) {
        Double fast = null;
        Double slow = null;
        Double prevFast = null;
        Double prevSlow = null;
        int bullishConfirmBars = 0;
        int bearishConfirmBars = 0;

        boolean inPosition = false;
        double entry = 0.0d;
        double tp = 0.0d;
        double sl = 0.0d;
        double equity = 100.0d;
        double peak = equity;
        double maxDd = 0.0d;
        int trades = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal profitPct = BigDecimal.ZERO;

        for (int i = 0; i < candles.size(); i++) {
            EmaMlPreparationService.CandlePoint candle = candles.get(i);
            double close = candle.close();

            prevFast = fast;
            prevSlow = slow;
            fast = nextEma(fast, close, candidate.emaFast);
            slow = nextEma(slow, close, candidate.emaSlow);

            if (i + 1 < candidate.emaSlow || prevFast == null || prevSlow == null || fast == null || slow == null) {
                continue;
            }

            boolean bullRegime = fast > slow;
            boolean bearRegime = fast < slow;
            if (bullRegime) {
                bullishConfirmBars++;
                bearishConfirmBars = 0;
            } else if (bearRegime) {
                bearishConfirmBars++;
                bullishConfirmBars = 0;
            }

            double spreadPct = safePct(Math.abs(fast - slow), slow);
            if (!inPosition) {
                if (bullRegime && bullishConfirmBars >= candidate.confirmBars && spreadPct <= Math.max(0.000001d, candidate.maxSpreadPct)) {
                    inPosition = true;
                    entry = close;
                    tp = entry * (1.0d + DEFAULT_TP_PCT.doubleValue() / 100.0d);
                    sl = entry * (1.0d - DEFAULT_SL_PCT.doubleValue() / 100.0d);
                }
                continue;
            }

            String exitReason = null;
            double exitPrice = close;
            if (candle.low() <= sl) {
                exitPrice = sl;
                exitReason = "SL";
            } else if (candle.high() >= tp) {
                exitPrice = tp;
                exitReason = "TP";
            } else if (bearishConfirmBars >= candidate.confirmBars) {
                exitPrice = close;
                exitReason = "BEAR";
            }

            if (exitReason != null) {
                trades++;
                double pnlPctValue = safePct(exitPrice - entry, entry);
                profitPct = profitPct.add(BigDecimal.valueOf(pnlPctValue));
                equity = equity * (1.0d + pnlPctValue / 100.0d);
                if (pnlPctValue >= 0.0d) wins++; else losses++;
                peak = Math.max(peak, equity);
                if (peak > 0.0d) {
                    double dd = ((peak - equity) / peak) * 100.0d;
                    maxDd = Math.max(maxDd, dd);
                }
                inPosition = false;
                entry = 0.0d;
                tp = 0.0d;
                sl = 0.0d;
            }
        }

        BigDecimal score = profitPct.subtract(BigDecimal.valueOf(maxDd).multiply(new BigDecimal("0.50")));
        if (trades <= 0) {
            score = score.subtract(new BigDecimal("10.000"));
        }
        score = score.setScale(6, RoundingMode.HALF_UP);

        return new BacktestScore(
                score,
                profitPct.setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(maxDd).setScale(6, RoundingMode.HALF_UP),
                trades,
                wins,
                losses
        );
    }

    private static double nextEma(Double prev, double price, int period) {
        if (period <= 1 || prev == null) return price;
        double alpha = 2.0d / (period + 1.0d);
        return price * alpha + prev * (1.0d - alpha);
    }

    private static double safePct(double numerator, double base) {
        if (!Double.isFinite(numerator) || !Double.isFinite(base) || Math.abs(base) < 1e-12d) return 0.0d;
        double v = (numerator / base) * 100.0d;
        return Double.isFinite(v) ? v : 0.0d;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static int[] unique(int... values) {
        return java.util.Arrays.stream(values).distinct().toArray();
    }

    private static double[] unique(double... values) {
        return java.util.Arrays.stream(values).distinct().toArray();
    }

    private static String strip(BigDecimal v) {
        if (v == null) return "null";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String safeUpper(String v) {
        if (v == null) return "";
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "" : s;
    }

    private static String safeLower(String v) {
        if (v == null) return "";
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "" : s;
    }

    private static final class Candidate {
        final int emaFast;
        final int emaSlow;
        final int confirmBars;
        final double maxSpreadPct;

        private Candidate(int emaFast, int emaSlow, int confirmBars, double maxSpreadPct) {
            this.emaFast = emaFast;
            this.emaSlow = emaSlow;
            this.confirmBars = confirmBars;
            this.maxSpreadPct = maxSpreadPct;
        }

        static Candidate of(int emaFast, int emaSlow, int confirmBars, double maxSpreadPct) {
            return new Candidate(emaFast, emaSlow, confirmBars, maxSpreadPct);
        }
    }

    private static final class BacktestScore {
        final BigDecimal score;
        final BigDecimal profitPct;
        final BigDecimal maxDrawdownPct;
        final int trades;
        final int wins;
        final int losses;

        private BacktestScore(BigDecimal score, BigDecimal profitPct, BigDecimal maxDrawdownPct, int trades, int wins, int losses) {
            this.score = score;
            this.profitPct = profitPct;
            this.maxDrawdownPct = maxDrawdownPct;
            this.trades = trades;
            this.wins = wins;
            this.losses = losses;
        }
    }
}

package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScalpingFeatureCalculator {

    private static final int SCALE = 8;

    private ScalpingFeatureCalculator() {
    }

    public static ScalpingFeatureSnapshot calculate(Deque<BigDecimal> priceWindow,
                                                    ScalpingStrategySettings cfg,
                                                    Instant ts) {
        if (priceWindow == null || cfg == null || priceWindow.size() < 3) {
            return null;
        }

        List<BigDecimal> prices = new ArrayList<>(priceWindow);
        BigDecimal first = prices.get(0);
        BigDecimal last = prices.get(prices.size() - 1);
        if (!positive(first) || !positive(last)) {
            return null;
        }

        BigDecimal low = prices.stream()
                .filter(ScalpingFeatureCalculator::positive)
                .min(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal high = prices.stream()
                .filter(ScalpingFeatureCalculator::positive)
                .max(BigDecimal::compareTo)
                .orElse(null);
        if (!positive(low) || !positive(high)) {
            return null;
        }

        BigDecimal priceChangePct = pct(first, last);
        BigDecimal windowRange = pct(low, high);
        BigDecimal priceFromWindowLow = pct(low, last);
        BigDecimal priceFromWindowHigh = pct(last, high);

        int fastPeriod = Math.max(3, cfg.getWindowSize() / 4);
        int slowPeriod = Math.max(fastPeriod + 2, cfg.getWindowSize() / 2);
        double emaFast = ema(prices, fastPeriod);
        double emaSlow = ema(prices, slowPeriod);
        BigDecimal emaDiff = pct(BigDecimal.valueOf(emaSlow), BigDecimal.valueOf(emaFast));

        BigDecimal atrPct = calculateAtrPct(prices);
        BigDecimal spreadPct = calculateMicroSpreadPct(prices);
        BigDecimal volumeToAverage = calculateActivityRatio(prices);
        BigDecimal rsi = BigDecimal.valueOf(calculateRsi(prices, Math.min(14, Math.max(6, prices.size() / 2))));

        BigDecimal riskRewardRatio = BigDecimal.ZERO;
        if (cfg.getTakeProfitPct() != null && cfg.getStopLossPct() != null && cfg.getStopLossPct() > 0) {
            riskRewardRatio = BigDecimal.valueOf(cfg.getTakeProfitPct())
                    .divide(BigDecimal.valueOf(cfg.getStopLossPct()), SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal score = buildScore(priceChangePct, emaDiff, volumeToAverage, spreadPct, atrPct, rsi, riskRewardRatio);

        return new ScalpingFeatureSnapshot(
                ts,
                last.setScale(SCALE, RoundingMode.HALF_UP),
                low.setScale(SCALE, RoundingMode.HALF_UP),
                high.setScale(SCALE, RoundingMode.HALF_UP),
                priceChangePct,
                emaDiff,
                volumeToAverage,
                spreadPct,
                atrPct,
                windowRange,
                priceFromWindowLow,
                priceFromWindowHigh,
                rsi.setScale(SCALE, RoundingMode.HALF_UP),
                riskRewardRatio.setScale(SCALE, RoundingMode.HALF_UP),
                score.setScale(SCALE, RoundingMode.HALF_UP)
        );
    }

    private static BigDecimal buildScore(BigDecimal priceChangePct,
                                         BigDecimal emaDiff,
                                         BigDecimal volumeToAverage,
                                         BigDecimal spreadPct,
                                         BigDecimal atrPct,
                                         BigDecimal rsi,
                                         BigDecimal riskRewardRatio) {
        double score = 0.0d;
        score += safe(priceChangePct) * 2.2d;
        score += Math.max(0.0d, safe(emaDiff)) * 2.0d;
        score += Math.max(0.0d, safe(volumeToAverage) - 1.0d) * 1.7d;
        score += Math.max(0.0d, 1.0d - safe(spreadPct)) * 1.1d;
        score += Math.max(0.0d, 1.0d - safe(atrPct)) * 0.8d;
        score += Math.max(0.0d, (safe(rsi) - 50.0d) / 10.0d) * 0.7d;
        score += Math.max(0.0d, safe(riskRewardRatio) - 1.0d) * 1.3d;
        return BigDecimal.valueOf(score);
    }

    private static BigDecimal calculateAtrPct(List<BigDecimal> prices) {
        if (prices.size() < 2) {
            return BigDecimal.ZERO;
        }
        double sum = 0.0d;
        int count = 0;
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (!positive(prev) || !positive(curr)) {
                continue;
            }
            sum += Math.abs(curr.subtract(prev)
                    .divide(prev, SCALE, RoundingMode.HALF_UP)
                    .doubleValue()) * 100.0d;
            count++;
        }
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(sum / count).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateMicroSpreadPct(List<BigDecimal> prices) {
        if (prices.size() < 3) {
            return BigDecimal.ZERO;
        }
        int start = Math.max(1, prices.size() - 3);
        double sum = 0.0d;
        int count = 0;
        for (int i = start; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (!positive(prev) || !positive(curr)) {
                continue;
            }
            sum += Math.abs(curr.subtract(prev)
                    .divide(prev, SCALE, RoundingMode.HALF_UP)
                    .doubleValue()) * 100.0d;
            count++;
        }
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(sum / count).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * proxy для volumeToAverage, пока в onPriceUpdate нет реального объёма свечи.
     * Сравниваем "энергию" последних движений с базовым средним окном.
     */
    private static BigDecimal calculateActivityRatio(List<BigDecimal> prices) {
        if (prices.size() < 4) {
            return BigDecimal.ONE;
        }

        List<Double> absMoves = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (!positive(prev) || !positive(curr)) {
                continue;
            }
            absMoves.add(Math.abs(curr.subtract(prev)
                    .divide(prev, SCALE, RoundingMode.HALF_UP)
                    .doubleValue()) * 100.0d);
        }

        if (absMoves.isEmpty()) {
            return BigDecimal.ONE;
        }

        double baseAvg = absMoves.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        int recentCount = Math.max(2, absMoves.size() / 4);
        double recentAvg = absMoves.subList(absMoves.size() - recentCount, absMoves.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0d);

        if (baseAvg <= 0.0d) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(recentAvg / baseAvg).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static double calculateRsi(List<BigDecimal> prices, int period) {
        if (prices.size() < period + 1) {
            return 50.0d;
        }

        double gains = 0.0d;
        double losses = 0.0d;
        int start = Math.max(1, prices.size() - period);
        for (int i = start; i < prices.size(); i++) {
            double delta = prices.get(i).subtract(prices.get(i - 1)).doubleValue();
            if (delta >= 0) {
                gains += delta;
            } else {
                losses += Math.abs(delta);
            }
        }

        if (losses == 0.0d && gains == 0.0d) {
            return 50.0d;
        }
        if (losses == 0.0d) {
            return 100.0d;
        }

        double rs = gains / losses;
        return 100.0d - (100.0d / (1.0d + rs));
    }

    private static double ema(List<BigDecimal> values, int period) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        double alpha = 2.0d / (period + 1.0d);
        double ema = values.get(0).doubleValue();
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).doubleValue() * alpha + ema * (1.0d - alpha);
        }
        return ema;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal value) {
        if (!positive(base) || value == null) {
            return BigDecimal.ZERO;
        }
        return value.subtract(base)
                .divide(base, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100.0d))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static double safe(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }
}

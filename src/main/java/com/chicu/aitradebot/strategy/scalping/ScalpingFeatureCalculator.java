package com.chicu.aitradebot.strategy.scalping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScalpingFeatureCalculator {

    private static final int SCALE = 8;

    public record CandleInput(
            Instant timestamp,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume
    ) {}

    private ScalpingFeatureCalculator() {
    }

    /**
     * Фолбэк для intrabar/tick-режима, когда у нас есть только closes.
     * Здесь volumeToAverage считается как proxy и не должен блокировать вход сам по себе.
     */
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

        BigDecimal atrPct = calculateAtrPctFromCloses(prices);
        BigDecimal spreadPct = calculateMicroSpreadPctFromCloses(prices);
        BigDecimal volumeToAverage = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(calculateRsi(prices, Math.min(14, Math.max(6, prices.size() / 2))));

        BigDecimal riskRewardRatio = riskRewardRatio(cfg);
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
                score.setScale(SCALE, RoundingMode.HALF_UP),
                true
        );
    }

    /**
     * Основной расчёт по реальным свечам OHLCV.
     */
    public static ScalpingFeatureSnapshot calculateFromCandles(Deque<CandleInput> candleWindow,
                                                               ScalpingStrategySettings cfg,
                                                               Instant ts) {
        if (candleWindow == null || cfg == null || candleWindow.size() < 3) {
            return null;
        }

        List<CandleInput> candles = candleWindow.stream()
                .filter(c -> c != null && positive(c.close()))
                .toList();

        if (candles.size() < 3) {
            return null;
        }

        List<BigDecimal> closes = candles.stream().map(CandleInput::close).toList();

        BigDecimal first = closes.get(0);
        BigDecimal last = closes.get(closes.size() - 1);

        BigDecimal low = candles.stream()
                .map(CandleInput::low)
                .filter(ScalpingFeatureCalculator::positive)
                .min(BigDecimal::compareTo)
                .orElse(null);

        BigDecimal high = candles.stream()
                .map(CandleInput::high)
                .filter(ScalpingFeatureCalculator::positive)
                .max(BigDecimal::compareTo)
                .orElse(null);

        if (!positive(first) || !positive(last) || !positive(low) || !positive(high)) {
            return null;
        }

        BigDecimal priceChangePct = pct(first, last);
        BigDecimal windowRange = pct(low, high);
        BigDecimal priceFromWindowLow = pct(low, last);
        BigDecimal priceFromWindowHigh = pct(last, high);

        int fastPeriod = Math.max(3, cfg.getWindowSize() / 4);
        int slowPeriod = Math.max(fastPeriod + 2, cfg.getWindowSize() / 2);
        double emaFast = ema(closes, fastPeriod);
        double emaSlow = ema(closes, slowPeriod);
        BigDecimal emaDiff = pct(BigDecimal.valueOf(emaSlow), BigDecimal.valueOf(emaFast));

        BigDecimal atrPct = calculateAtrPctFromCandles(candles);
        BigDecimal spreadPct = calculateMicroSpreadPctFromCloses(closes);
        BigDecimal volumeToAverage = calculateVolumeRatio(candles);
        BigDecimal rsi = BigDecimal.valueOf(calculateRsi(closes, Math.min(14, Math.max(6, closes.size() / 2))));

        BigDecimal riskRewardRatio = riskRewardRatio(cfg);
        BigDecimal score = buildScore(priceChangePct, emaDiff, volumeToAverage, spreadPct, atrPct, rsi, riskRewardRatio);

        return new ScalpingFeatureSnapshot(
                ts != null ? ts : candles.get(candles.size() - 1).timestamp(),
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
                score.setScale(SCALE, RoundingMode.HALF_UP),
                false
        );
    }

    private static BigDecimal riskRewardRatio(ScalpingStrategySettings cfg) {
        BigDecimal riskRewardRatio = BigDecimal.ZERO;
        if (cfg != null && cfg.getTakeProfitPct() != null && cfg.getStopLossPct() != null && cfg.getStopLossPct() > 0) {
            riskRewardRatio = BigDecimal.valueOf(cfg.getTakeProfitPct())
                    .divide(BigDecimal.valueOf(cfg.getStopLossPct()), SCALE, RoundingMode.HALF_UP);
        }
        return riskRewardRatio;
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

    private static BigDecimal calculateAtrPctFromCandles(List<CandleInput> candles) {
        if (candles.size() < 2) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }

        double sum = 0.0d;
        int count = 0;
        BigDecimal prevClose = candles.get(0).close();

        for (int i = 1; i < candles.size(); i++) {
            CandleInput c = candles.get(i);
            if (!positive(c.high()) || !positive(c.low()) || !positive(prevClose) || !positive(c.close())) {
                prevClose = c.close();
                continue;
            }

            double highLow = c.high().subtract(c.low()).doubleValue();
            double highPrev = c.high().subtract(prevClose).abs().doubleValue();
            double lowPrev = c.low().subtract(prevClose).abs().doubleValue();
            double tr = Math.max(highLow, Math.max(highPrev, lowPrev));

            if (c.close().signum() > 0) {
                sum += (tr / c.close().doubleValue()) * 100.0d;
                count++;
            }
            prevClose = c.close();
        }

        if (count == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }

        return BigDecimal.valueOf(sum / count).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateAtrPctFromCloses(List<BigDecimal> prices) {
        if (prices.size() < 2) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
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
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(sum / count).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateMicroSpreadPctFromCloses(List<BigDecimal> prices) {
        if (prices.size() < 3) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
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
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(sum / count).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateVolumeRatio(List<CandleInput> candles) {
        if (candles.size() < 4) {
            return BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        }

        List<BigDecimal> volumes = candles.stream()
                .map(CandleInput::volume)
                .filter(ScalpingFeatureCalculator::positive)
                .toList();

        if (volumes.size() < 4) {
            return BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        }

        double baseAvg = volumes.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0d);

        int recentCount = Math.max(2, volumes.size() / 4);
        double recentAvg = volumes.subList(volumes.size() - recentCount, volumes.size())
                .stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0d);

        if (baseAvg <= 0.0d) {
            return BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
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
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
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

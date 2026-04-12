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

    public static ScalpingFeatureSnapshot calculate(Deque<BigDecimal> priceWindow,
                                                    ScalpingStrategySettings cfg,
                                                    Instant ts) {
        if (priceWindow == null || cfg == null || priceWindow.size() < 4) {
            return null;
        }

        List<BigDecimal> prices = new ArrayList<>(priceWindow);
        BigDecimal first = prices.get(0);
        BigDecimal last = prices.get(prices.size() - 1);
        BigDecimal low = prices.stream().filter(ScalpingFeatureCalculator::positive).min(BigDecimal::compareTo).orElse(null);
        BigDecimal high = prices.stream().filter(ScalpingFeatureCalculator::positive).max(BigDecimal::compareTo).orElse(null);
        if (!positive(first) || !positive(last) || !positive(low) || !positive(high)) {
            return null;
        }

        int window = effectiveWindow(cfg);
        int fastPeriod = Math.max(3, Math.min(window / 4, prices.size() - 1));
        int slowPeriod = Math.max(fastPeriod + 2, Math.min(window / 2, prices.size()));
        double emaFast = ema(prices, fastPeriod);
        double emaSlow = ema(prices, slowPeriod);
        double prevEmaFast = ema(prices.subList(0, prices.size() - 1), fastPeriod);
        double prevEmaSlow = ema(prices.subList(0, prices.size() - 1), slowPeriod);

        BigDecimal priceChangePct = pct(first, last);
        BigDecimal windowRange = pct(low, high);
        BigDecimal priceFromWindowLow = pct(low, last);
        BigDecimal priceFromWindowHigh = pct(last, high);
        BigDecimal emaDiff = pct(BigDecimal.valueOf(emaSlow), BigDecimal.valueOf(emaFast));
        BigDecimal atrPct = calculateAtrPctFromCloses(prices);
        BigDecimal spreadPct = calculateMicroSpreadPctFromCloses(prices);
        BigDecimal volumeToAverage = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal volumeRatio = volumeToAverage;
        BigDecimal rsi = scale(calculateRsi(prices, Math.min(14, Math.max(6, prices.size() / 2))));
        BigDecimal riskRewardRatio = riskRewardRatio(cfg);
        BigDecimal emaSlopeFast = pct(BigDecimal.valueOf(prevEmaFast), BigDecimal.valueOf(emaFast));
        BigDecimal emaSlopeSlow = pct(BigDecimal.valueOf(prevEmaSlow), BigDecimal.valueOf(emaSlow));
        BigDecimal adxLike = scale(estimateAdxLike(prices));
        BigDecimal vwapDistancePct = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal wickBodyRatio = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal candleEfficiency = scale(0.50d);
        BigDecimal distanceFromLowPct = priceFromWindowLow;
        BigDecimal distanceFromHighPct = priceFromWindowHigh;
        BigDecimal microPullbackDepthPct = scale(Math.max(0.0d, priceFromWindowHigh.doubleValue() * 0.85d));
        BigDecimal squeezeScore = estimateSqueezeScore(atrPct, windowRange, spreadPct);
        BigDecimal breakoutPressure = estimateBreakoutPressure(priceChangePct, priceFromWindowHigh, volumeRatio);
        BigDecimal bullishStructureScore = estimateBullishStructureScore(prices);
        BigDecimal score = buildScore(priceChangePct, emaDiff, volumeToAverage, spreadPct, atrPct, rsi, riskRewardRatio,
                breakoutPressure, bullishStructureScore);

        return new ScalpingFeatureSnapshot(
                ts,
                scale(last),
                scale(low),
                scale(high),
                priceChangePct,
                emaDiff,
                volumeToAverage,
                spreadPct,
                atrPct,
                windowRange,
                priceFromWindowLow,
                priceFromWindowHigh,
                rsi,
                riskRewardRatio,
                score,
                true,
                scale(emaFast),
                scale(emaSlow),
                emaSlopeFast,
                emaSlopeSlow,
                adxLike,
                volumeRatio,
                vwapDistancePct,
                wickBodyRatio,
                candleEfficiency,
                microPullbackDepthPct,
                squeezeScore,
                distanceFromLowPct,
                distanceFromHighPct,
                breakoutPressure,
                bullishStructureScore
        );
    }

    public static ScalpingFeatureSnapshot calculateFromCandles(Deque<CandleInput> candleWindow,
                                                               ScalpingStrategySettings cfg,
                                                               Instant ts) {
        if (candleWindow == null || cfg == null || candleWindow.size() < 4) {
            return null;
        }

        List<CandleInput> candles = candleWindow.stream()
                .filter(c -> c != null && positive(c.close()))
                .toList();
        if (candles.size() < 4) {
            return null;
        }

        List<BigDecimal> closes = candles.stream().map(CandleInput::close).toList();
        BigDecimal first = closes.get(0);
        BigDecimal last = closes.get(closes.size() - 1);
        BigDecimal low = candles.stream().map(CandleInput::low).filter(ScalpingFeatureCalculator::positive).min(BigDecimal::compareTo).orElse(null);
        BigDecimal high = candles.stream().map(CandleInput::high).filter(ScalpingFeatureCalculator::positive).max(BigDecimal::compareTo).orElse(null);
        if (!positive(first) || !positive(last) || !positive(low) || !positive(high)) {
            return null;
        }

        int window = effectiveWindow(cfg);
        int fastPeriod = Math.max(3, Math.min(window / 4, closes.size() - 1));
        int slowPeriod = Math.max(fastPeriod + 2, Math.min(window / 2, closes.size()));
        double emaFast = ema(closes, fastPeriod);
        double emaSlow = ema(closes, slowPeriod);
        double prevEmaFast = ema(closes.subList(0, closes.size() - 1), fastPeriod);
        double prevEmaSlow = ema(closes.subList(0, closes.size() - 1), slowPeriod);

        BigDecimal priceChangePct = pct(first, last);
        BigDecimal windowRange = pct(low, high);
        BigDecimal priceFromWindowLow = pct(low, last);
        BigDecimal priceFromWindowHigh = pct(last, high);
        BigDecimal emaDiff = pct(BigDecimal.valueOf(emaSlow), BigDecimal.valueOf(emaFast));
        BigDecimal atrPct = calculateAtrPctFromCandles(candles);
        BigDecimal spreadPct = calculateMicroSpreadPctFromCloses(closes);
        BigDecimal volumeToAverage = calculateVolumeRatio(candles);
        BigDecimal volumeRatio = volumeToAverage;
        BigDecimal rsi = scale(calculateRsi(closes, Math.min(14, Math.max(6, closes.size() / 2))));
        BigDecimal riskRewardRatio = riskRewardRatio(cfg);
        BigDecimal emaSlopeFast = pct(BigDecimal.valueOf(prevEmaFast), BigDecimal.valueOf(emaFast));
        BigDecimal emaSlopeSlow = pct(BigDecimal.valueOf(prevEmaSlow), BigDecimal.valueOf(emaSlow));
        BigDecimal adxLike = scale(estimateAdxLike(closes));
        BigDecimal vwapDistancePct = calculateVwapDistancePct(candles, last);
        BigDecimal wickBodyRatio = calculateWickBodyRatio(candles.get(candles.size() - 1));
        BigDecimal candleEfficiency = calculateCandleEfficiency(candles.get(candles.size() - 1));
        BigDecimal distanceFromLowPct = priceFromWindowLow;
        BigDecimal distanceFromHighPct = priceFromWindowHigh;
        BigDecimal microPullbackDepthPct = calculateMicroPullbackDepthPct(candles, last);
        BigDecimal squeezeScore = estimateSqueezeScore(atrPct, windowRange, spreadPct);
        BigDecimal breakoutPressure = estimateBreakoutPressure(priceChangePct, priceFromWindowHigh, volumeRatio);
        BigDecimal bullishStructureScore = estimateBullishStructureScore(closes);
        BigDecimal score = buildScore(priceChangePct, emaDiff, volumeToAverage, spreadPct, atrPct, rsi, riskRewardRatio,
                breakoutPressure, bullishStructureScore);

        return new ScalpingFeatureSnapshot(
                ts != null ? ts : candles.get(candles.size() - 1).timestamp(),
                scale(last),
                scale(low),
                scale(high),
                priceChangePct,
                emaDiff,
                volumeToAverage,
                spreadPct,
                atrPct,
                windowRange,
                priceFromWindowLow,
                priceFromWindowHigh,
                rsi,
                riskRewardRatio,
                score,
                false,
                scale(emaFast),
                scale(emaSlow),
                emaSlopeFast,
                emaSlopeSlow,
                adxLike,
                volumeRatio,
                vwapDistancePct,
                wickBodyRatio,
                candleEfficiency,
                microPullbackDepthPct,
                squeezeScore,
                distanceFromLowPct,
                distanceFromHighPct,
                breakoutPressure,
                bullishStructureScore
        );
    }

    private static int effectiveWindow(ScalpingStrategySettings cfg) {
        if (cfg.getWindowSize() == null || cfg.getWindowSize() < 8) return 36;
        return Math.min(cfg.getWindowSize(), 240);
    }

    private static BigDecimal riskRewardRatio(ScalpingStrategySettings cfg) {
        if (cfg == null || cfg.getTakeProfitPct() == null || cfg.getStopLossPct() == null || cfg.getStopLossPct() <= 0) {
            return BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(cfg.getTakeProfitPct())
                .divide(BigDecimal.valueOf(cfg.getStopLossPct()), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal buildScore(BigDecimal priceChangePct,
                                         BigDecimal emaDiff,
                                         BigDecimal volumeToAverage,
                                         BigDecimal spreadPct,
                                         BigDecimal atrPct,
                                         BigDecimal rsi,
                                         BigDecimal riskRewardRatio,
                                         BigDecimal breakoutPressure,
                                         BigDecimal bullishStructureScore) {
        double score = 0.0d;
        score += Math.max(0.0d, safe(priceChangePct)) * 2.4d;
        score += Math.max(0.0d, safe(emaDiff)) * 1.9d;
        score += Math.max(0.0d, safe(volumeToAverage) - 0.90d) * 1.5d;
        score += Math.max(0.0d, 0.35d - safe(spreadPct)) * 2.5d;
        score += Math.max(0.0d, 1.10d - safe(atrPct)) * 0.9d;
        score += Math.max(0.0d, (safe(rsi) - 45.0d) / 10.0d) * 0.8d;
        score += Math.max(0.0d, safe(riskRewardRatio) - 1.0d) * 1.1d;
        score += Math.max(0.0d, safe(breakoutPressure)) * 0.9d;
        score += Math.max(0.0d, safe(bullishStructureScore)) * 1.1d;
        return scale(score);
    }

    private static BigDecimal calculateAtrPctFromCandles(List<CandleInput> candles) {
        if (candles.size() < 2) return scale(0);
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
            sum += (tr / c.close().doubleValue()) * 100.0d;
            count++;
            prevClose = c.close();
        }
        return count == 0 ? scale(0) : scale(sum / count);
    }

    private static BigDecimal calculateAtrPctFromCloses(List<BigDecimal> prices) {
        if (prices.size() < 2) return scale(0);
        double sum = 0.0d;
        int count = 0;
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (!positive(prev) || !positive(curr)) continue;
            sum += Math.abs(curr.subtract(prev).divide(prev, SCALE, RoundingMode.HALF_UP).doubleValue()) * 100.0d;
            count++;
        }
        return count == 0 ? scale(0) : scale(sum / count);
    }

    private static BigDecimal calculateMicroSpreadPctFromCloses(List<BigDecimal> prices) {
        if (prices.size() < 3) return scale(0);
        int start = Math.max(1, prices.size() - 4);
        double sum = 0.0d;
        int count = 0;
        for (int i = start; i < prices.size(); i++) {
            BigDecimal prev = prices.get(i - 1);
            BigDecimal curr = prices.get(i);
            if (!positive(prev) || !positive(curr)) continue;
            sum += Math.abs(curr.subtract(prev).divide(prev, SCALE, RoundingMode.HALF_UP).doubleValue()) * 100.0d;
            count++;
        }
        return count == 0 ? scale(0) : scale(sum / count);
    }

    private static BigDecimal calculateVolumeRatio(List<CandleInput> candles) {
        if (candles.size() < 4) return scale(1);
        List<BigDecimal> volumes = candles.stream().map(CandleInput::volume).filter(ScalpingFeatureCalculator::positive).toList();
        if (volumes.size() < 4) return scale(1);
        double baseAvg = volumes.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0d);
        int recentCount = Math.max(2, volumes.size() / 4);
        double recentAvg = volumes.subList(volumes.size() - recentCount, volumes.size()).stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0d);
        if (baseAvg <= 0.0d) return scale(1);
        return scale(recentAvg / baseAvg);
    }

    private static BigDecimal calculateVwapDistancePct(List<CandleInput> candles, BigDecimal last) {
        if (!positive(last) || candles.isEmpty()) return scale(0);
        double pv = 0.0d;
        double vv = 0.0d;
        int start = Math.max(0, candles.size() - 16);
        for (int i = start; i < candles.size(); i++) {
            CandleInput c = candles.get(i);
            if (!positive(c.close()) || !positive(c.volume())) continue;
            pv += c.close().doubleValue() * c.volume().doubleValue();
            vv += c.volume().doubleValue();
        }
        if (vv <= 0.0d) return scale(0);
        double vwap = pv / vv;
        return pct(scale(vwap), last);
    }

    private static BigDecimal calculateWickBodyRatio(CandleInput candle) {
        if (candle == null || !positive(candle.high()) || !positive(candle.low()) || candle.open() == null || candle.close() == null) {
            return scale(1);
        }
        BigDecimal range = candle.high().subtract(candle.low()).abs();
        BigDecimal body = candle.close().subtract(candle.open()).abs();
        if (range.signum() <= 0) return scale(1);
        if (body.signum() <= 0) return scale(10);
        BigDecimal wick = range.subtract(body).max(BigDecimal.ZERO);
        return wick.divide(body, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateCandleEfficiency(CandleInput candle) {
        if (candle == null || !positive(candle.high()) || !positive(candle.low()) || candle.open() == null || candle.close() == null) {
            return scale(0.5d);
        }
        BigDecimal range = candle.high().subtract(candle.low()).abs();
        BigDecimal body = candle.close().subtract(candle.open()).abs();
        if (range.signum() <= 0) return scale(0.5d);
        return body.divide(range, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateMicroPullbackDepthPct(List<CandleInput> candles, BigDecimal last) {
        if (candles.isEmpty() || !positive(last)) return scale(0);
        int start = Math.max(0, candles.size() - 5);
        BigDecimal recentHigh = candles.subList(start, candles.size()).stream()
                .map(CandleInput::high)
                .filter(ScalpingFeatureCalculator::positive)
                .max(BigDecimal::compareTo)
                .orElse(last);
        return pct(last, recentHigh);
    }

    private static BigDecimal estimateSqueezeScore(BigDecimal atrPct, BigDecimal rangePct, BigDecimal spreadPct) {
        double score = 100.0d;
        score -= Math.min(40.0d, safe(atrPct) * 50.0d);
        score -= Math.min(35.0d, safe(rangePct) * 12.0d);
        score -= Math.min(25.0d, safe(spreadPct) * 80.0d);
        return scale(Math.max(0.0d, Math.min(100.0d, score)));
    }

    private static BigDecimal estimateBreakoutPressure(BigDecimal priceChangePct,
                                                       BigDecimal priceFromWindowHigh,
                                                       BigDecimal volumeRatio) {
        double score = 0.0d;
        score += Math.max(0.0d, safe(priceChangePct)) * 8.0d;
        score += Math.max(0.0d, 0.45d - safe(priceFromWindowHigh)) * 3.0d;
        score += Math.max(0.0d, safe(volumeRatio) - 1.0d) * 2.5d;
        return scale(score);
    }

    private static BigDecimal estimateBullishStructureScore(List<BigDecimal> closes) {
        if (closes.size() < 4) return scale(0.5d);
        int positives = 0;
        int checks = 0;
        for (int i = Math.max(1, closes.size() - 6); i < closes.size(); i++) {
            if (!positive(closes.get(i - 1)) || !positive(closes.get(i))) continue;
            if (closes.get(i).compareTo(closes.get(i - 1)) >= 0) positives++;
            checks++;
        }
        if (checks == 0) return scale(0.5d);
        return scale((double) positives / (double) checks * 5.0d);
    }

    private static double estimateAdxLike(List<BigDecimal> closes) {
        if (closes.size() < 4) return 15.0d;
        double up = 0.0d;
        double down = 0.0d;
        for (int i = 1; i < closes.size(); i++) {
            double delta = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            if (delta >= 0) up += delta;
            else down += Math.abs(delta);
        }
        double total = up + down;
        if (total <= 0.0d) return 10.0d;
        return Math.abs(up - down) / total * 50.0d + 10.0d;
    }

    private static double calculateRsi(List<BigDecimal> prices, int period) {
        if (prices.size() < period + 1) return 50.0d;
        double gains = 0.0d;
        double losses = 0.0d;
        int start = Math.max(1, prices.size() - period);
        for (int i = start; i < prices.size(); i++) {
            double delta = prices.get(i).subtract(prices.get(i - 1)).doubleValue();
            if (delta >= 0) gains += delta;
            else losses += Math.abs(delta);
        }
        if (losses == 0.0d && gains == 0.0d) return 50.0d;
        if (losses == 0.0d) return 100.0d;
        double rs = gains / losses;
        return 100.0d - (100.0d / (1.0d + rs));
    }

    private static double ema(List<BigDecimal> values, int period) {
        if (values.isEmpty()) return 0.0d;
        double alpha = 2.0d / (period + 1.0d);
        double ema = values.get(0).doubleValue();
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).doubleValue() * alpha + ema * (1.0d - alpha);
        }
        return ema;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal value) {
        if (!positive(base) || value == null) return scale(0);
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

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP) : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}



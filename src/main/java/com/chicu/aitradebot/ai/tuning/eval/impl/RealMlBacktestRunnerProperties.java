package com.chicu.aitradebot.ai.tuning.eval.impl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.ml.backtest")
public class RealMlBacktestRunnerProperties {

    /**
     * TTL warmup (мс). 0 или отрицательное = отключить TTL (всегда прогревать).
     */
    private long warmupTtlMs = 60_000L;

    /**
     * warmupLimit = candlesLimit * warmupMultiplier
     */
    private double warmupMultiplier = 2.0;

    /**
     * clamp warmupLimit
     */
    private int warmupMin = 500;
    private int warmupMax = 20_000;

    /**
     * candlesLimit defaults + clamp
     */
    private int defaultCandlesLimit = 900;
    private int candlesLimitMin = 50;
    private int candlesLimitMax = 20_000;

    /**
     * logging flags
     */
    private boolean logWarmupInfo = true;

    public double safeWarmupMultiplier() {
        return Math.max(0.1, warmupMultiplier);
    }

    public int safeWarmupMin() {
        return Math.max(1, warmupMin);
    }

    public int safeWarmupMax() {
        return Math.max(safeWarmupMin(), warmupMax);
    }

    public int safeCandlesLimitMin() {
        return Math.max(1, candlesLimitMin);
    }

    public int safeCandlesLimitMax() {
        return Math.max(safeCandlesLimitMin(), candlesLimitMax);
    }

    public int safeDefaultCandlesLimit() {
        int d = Math.max(1, defaultCandlesLimit);
        int min = safeCandlesLimitMin();
        int max = safeCandlesLimitMax();
        if (d < min) d = min;
        if (d > max) d = max;
        return d;
    }
}
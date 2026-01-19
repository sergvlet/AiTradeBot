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
}

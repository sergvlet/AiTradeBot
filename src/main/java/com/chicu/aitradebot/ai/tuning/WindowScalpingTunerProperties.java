package com.chicu.aitradebot.ai.tuning;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Data
@Validated
@ConfigurationProperties(prefix = "aitrade.ai.tuning.window-scalping")
public class WindowScalpingTunerProperties {

    private String modelVersion = "ws-tuner-v1";

    @Min(1)
    private int candidates = 40;

    @DecimalMin("0.0")
    private BigDecimal minAbsImprove = new BigDecimal("0.02");

    @DecimalMin("0.0")
    private BigDecimal minRelImprove = new BigDecimal("0.03");

    private BigDecimal baselineTooBadScore = new BigDecimal("-1.00");

    @DecimalMin("0.0")
    private BigDecimal baselineTooBadMinDelta = new BigDecimal("0.01");

    @Min(1)
    private int defaultPeriodDays = 14;

    @Min(1)
    private int minCandlesLimit = 50;

    @Min(1)
    private int defaultCandlesLimit = 500;

    @Min(1)
    private int warmupMultiplier = 2;

    @Min(1)
    private int minWarmupLimit = 500;

    @Min(1)
    private int maxWarmupLimit = 20_000;

    @DecimalMin("0.0")
    private BigDecimal epsilon = new BigDecimal("0.0001");

    private boolean logSkipAsInfo = false;

    // helpers
    public int safeCandidates() { return Math.max(1, candidates); }
    public int safeMinCandlesLimit() { return Math.max(1, minCandlesLimit); }
    public int safeDefaultCandlesLimit() { return Math.max(safeMinCandlesLimit(), defaultCandlesLimit); }
    public int safeWarmupMultiplier() { return Math.max(1, warmupMultiplier); }
    public int safeMinWarmupLimit() { return Math.max(1, minWarmupLimit); }
    public int safeMaxWarmupLimit() { return Math.max(safeMinWarmupLimit(), maxWarmupLimit); }
}
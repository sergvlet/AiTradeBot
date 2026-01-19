package com.chicu.aitradebot.ai.tuning;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "aitrade.ai.tuning.window-scalping")
public class WindowScalpingTunerProperties {

    /**
     * Версия "модели" (просто идентификатор тюнера).
     */
    private String modelVersion = "ws-tuner-v1";

    /**
     * Сколько кандидатов гоняем.
     */
    private int candidates = 40;

    /**
     * Порог улучшения: абсолютный.
     */
    private BigDecimal minAbsImprove = new BigDecimal("0.02");

    /**
     * Порог улучшения: относительный (от |score0|).
     */
    private BigDecimal minRelImprove = new BigDecimal("0.03");

    /**
     * Если baseline очень плохой — допускаем меньший порог.
     */
    private BigDecimal baselineTooBadScore = new BigDecimal("-1.00");
    private BigDecimal baselineTooBadMinDelta = new BigDecimal("0.01");

    /**
     * Дефолтный период, если UI не прислал.
     */
    private int defaultPeriodDays = 14;

    /**
     * Минимальный candlesLimit.
     */
    private int minCandlesLimit = 50;

    /**
     * Дефолтный candlesLimit, если нигде нет.
     */
    private int defaultCandlesLimit = 500;

    /**
     * warmupLimit = candlesLimit * multiplier, в пределах [minWarmupLimit..maxWarmupLimit]
     */
    private int warmupMultiplier = 2;
    private int minWarmupLimit = 500;
    private int maxWarmupLimit = 20_000;

    /**
     * Нормализация "минус ноль" для profit/dd и т.п.
     * Если |value| < epsilon → 0.
     */
    private BigDecimal epsilon = new BigDecimal("0.0001");

    /**
     * Логи: skip в DEBUG (чтобы не спамить).
     */
    private boolean logSkipAsInfo = false;
}

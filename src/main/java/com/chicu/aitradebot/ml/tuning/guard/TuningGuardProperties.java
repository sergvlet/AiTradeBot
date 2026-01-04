package com.chicu.aitradebot.ml.tuning.guard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ml.tuning.guard")
public class TuningGuardProperties {

    private boolean enabled = true;

    /** Минимальная пауза между тюнингами (в часах) */
    private long minHoursBetween = 6;

    /** Максимальный относительный delta на поле (0.25 = ±25%) */
    private double maxDeltaPct = 0.25;

    /** Запретить TP < SL */
    private boolean requireTpGteSl = true;
}

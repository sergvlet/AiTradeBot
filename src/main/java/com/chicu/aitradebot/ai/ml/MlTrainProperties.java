package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки обучения ML (sidecar /train).
 *
 * Важно: НЕ помечаем как @Component/@Configuration,
 * чтобы не получить 2 бина при ConfigurationPropertiesScan.
 */
@Data
@ConfigurationProperties(prefix = "ml.train")
public class MlTrainProperties {

    private boolean enabled = true;
    private boolean autoApply = true;

    /** Cooldown между запусками train для chatId+strategy+symbol+tf. */
    private long cooldownMinutes = 10;

    /** Сколько дней назад брать сэмплы. */
    private int lookbackDays = 14;

    /** Минимум сэмплов для обучения. */
    private int minSamples = 50;

    /** Максимум строк, которые отправляем в /train за один запуск. */
    private int rowsLimit = 5000;

    private double thresholdAutoEnable = 0.62;
    private long holdOffSeconds = 30;
}

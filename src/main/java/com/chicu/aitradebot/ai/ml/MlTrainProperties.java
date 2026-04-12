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

    /**
     * Минимум сэмплов для обычного обучения / переобучения.
     * Для prepare-start лучше держать отдельный, более мягкий порог.
     */
    private int minSamples = 50;

    /**
     * Минимум строк именно для подготовки модели перед стартом стратегии.
     * Нужен, чтобы не блокировать первый запуск из-за слишком жёсткого общего порога.
     */
    private int prepareMinSamples = 60;

    /** Максимум строк, которые отправляем в /train за один запуск. */
    private int rowsLimit = 5000;

    private double thresholdAutoEnable = 0.62;
    private long holdOffSeconds = 30;
}
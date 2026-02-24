package com.chicu.aitradebot.ai.ml;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки обучения ML (sidecar /train).
 *
 * Важно: НЕ помечаем как @Component, чтобы не получить 2 бина при ConfigurationPropertiesScan.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ml.train")
public class MlTrainProperties {

    /** Включить обучение (эндпоинт /train должен быть доступен). */
    private boolean enabled = true;

    /** Автоматически применять модель в StrategySettings (mlModelKey/mlModelVersion + включить gate если режим не MANUAL). */
    private boolean autoApply = true;

    /** Cooldown между запусками train для chatId+strategy+symbol+tf. */
    @Min(0)
    private long cooldownMinutes = 10;

    /** Сколько дней назад брать сэмплы. */
    @Min(1)
    @Max(365)
    private int lookbackDays = 14;

    /** Минимум сэмплов для обучения. */
    @Min(50)
    private int minSamples = 300;

    /**
     * Максимум строк, которые отправляем в /train за один запуск.
     * Чтобы не убить память и сеть.
     */
    @Positive
    private int rowsLimit = 5000;

    /** Если pWin >= thresholdAutoEnable — можно авто-включать gate (опционально). */
    @Min(0)
    @Max(1)
    private double thresholdAutoEnable = 0.62;

    /** "Пауза" после обучения (опционально). */
    @Min(0)
    private long holdOffSeconds = 30;
}
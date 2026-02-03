package com.chicu.aitradebot.ai.ml;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MlProperties
 * ============
 * Зачем нужен этот класс:
 * - читает настройки из application.properties по префиксу "ml.*"
 * - хранит их в одном месте, чтобы:
 *   1) MlHealthProbe понимал, включён ML или нет
 *   2) MlClient знал baseUrl/таймауты/apiKey
 *
 * ВАЖНО ПРО SPRING:
 * - Здесь НЕ должно быть @Component
 * - Бин создаётся через @EnableConfigurationProperties(MlProperties.class) в MlConfig
 *   (иначе будет 2 бина и autowire сломается)
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ml")
public class MlProperties {

    /**
     * Главный рубильник ML.
     * false -> ML полностью выключен, никакие /health и /predict не вызываются.
     *
     * application.properties:
     * ml.enabled=true|false
     */
    private boolean enabled = true;

    /**
     * Базовый URL python-сервиса.
     * Пример: http://127.0.0.1:8001
     *
     * application.properties:
     * ml.base-url=http://127.0.0.1:8001
     */
    private String baseUrl = "http://127.0.0.1:8001";

    /**
     * (Опционально) ключ авторизации для ML сервиса.
     * Если пусто — заголовок не отправляем.
     *
     * application.properties:
     * ml.api-key=
     */
    private String apiKey = "";

    /**
     * Таймаут соединения (мс) именно для ML клиента.
     *
     * application.properties:
     * ml.connect-timeout-ms=1000
     */
    private int connectTimeoutMs = 1000;

    /**
     * Таймаут чтения ответа (мс) именно для ML клиента.
     *
     * application.properties:
     * ml.read-timeout-ms=8000
     */
    private int readTimeoutMs = 8000;

    /**
     * Как логировать недоступность ML при старте.
     * Значения: WARN или DEBUG
     *
     * application.properties:
     * ml.health-startup-log-level=WARN|DEBUG
     */
    private String healthStartupLogLevel = "WARN";
}

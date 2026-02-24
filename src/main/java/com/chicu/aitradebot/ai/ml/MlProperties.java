package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml")
public class MlProperties {

    /**
     * Включает ML слой (MlClient + MlGateway + health probe).
     */
    private boolean enabled = true;

    /**
     * Base URL sidecar, без завершающего "/".
     * Пример: http://127.0.0.1:8002
     */
    private String baseUrl = "http://127.0.0.1:8002";

    /**
     * API key (опционально). Пустая строка == null.
     */
    private String apiKey = "";

    /**
     * HTTP timeouts.
     */
    private long connectTimeoutMs = 1500;
    private long readTimeoutMs = 8000;

    /**
     * Нужно для MlHttpClient.defaultHttp(connect, read, write).
     * Если 0/<=0 — будет равен readTimeoutMs в MlConfig.
     */
    private long writeTimeoutMs = 8000;

    // =====================================================
    // Health / startup logging
    // =====================================================

    /**
     * ✅ Backward-compat:
     * раньше MlHealthProbe брал props.getHealthStartupLogLevel().
     * Сейчас probe читает property ml.health.startupLogLevel,
     * но это поле оставляем, чтобы старые конфиги не ломались.
     */
    private String healthStartupLogLevel = "INFO";

    /**
     * Новая настройка для health-логики (можно не использовать напрямую — она читается через @Value).
     * Если хочешь — можно задавать в application.properties:
     * ml.health.startupLogLevel=INFO
     */
    private Health health = new Health();

    @Data
    public static class Health {

        /**
         * Уровень логирования старта health-check.
         * DEBUG / INFO / WARN / ERROR
         */
        private String startupLogLevel = "INFO";

        /**
         * Таймаут ожидания ответа health, мс (если sidecar подвисает).
         * Сейчас фактически управляется readTimeoutMs, но оставлено как явная настройка.
         */
        private long timeoutMs = 5000;

        /**
         * Максимум сообщений при старте (защита от спама).
         */
        private int maxStartupLogs = 5;
    }
}
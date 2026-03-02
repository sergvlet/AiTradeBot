package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml")
public class MlProperties {

    /** Включает ML слой (MlClient + MlGateway + health probe). */
    private boolean enabled = true;

    /** Base URL sidecar, без завершающего "/". */
    private String baseUrl = "http://127.0.0.1:8002";

    /** API key (опционально). Пустая строка == null. */
    private String apiKey = "";

    /** HTTP timeouts. */
    private long connectTimeoutMs = 1500;
    private long readTimeoutMs = 8000;

    /** Если 0/<=0 — будет равен readTimeoutMs в MlConfig. */
    private long writeTimeoutMs = 8000;

    // =====================================================
    // Health / startup logging
    // =====================================================

    /** Backward-compat: раньше MlHealthProbe брал props.getHealthStartupLogLevel(). */
    private String healthStartupLogLevel = "INFO";

    /** Новая настройка для health-логики */
    private Health health = new Health();

    @Data
    public static class Health {
        private String startupLogLevel = "INFO";
        private long timeoutMs = 5000;
        private int maxStartupLogs = 5;
    }
}
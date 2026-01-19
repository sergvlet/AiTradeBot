package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml.service")
public class MlProperties {
    /**
     * Например: http://127.0.0.1:8099
     */
    private String baseUrl;

    /**
     * Таймаут на HTTP-вызовы в миллисекундах
     */
    private int timeoutMs = 5000;
}

package com.chicu.aitradebot.ai.ml.sidecar.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml.sidecar")
public class MlSidecarProperties {

    /**
     * Пример: http://127.0.0.1:8001
     */
    private String baseUrl = "http://127.0.0.1:8001";

    /**
     * Защита sidecar (если включишь в python).
     */
    private String apiKey = "";

    private long connectTimeoutMs = 1000;
    private long readTimeoutMs = 8000;
}

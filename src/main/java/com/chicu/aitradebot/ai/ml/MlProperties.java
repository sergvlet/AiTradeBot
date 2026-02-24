package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml")
public class MlProperties {

    private boolean enabled = true;

    private String baseUrl = "http://127.0.0.1:8002";
    private String apiKey = "";

    private long connectTimeoutMs = 1000;
    private long readTimeoutMs = 8000;

    // ✅ нужно для MlHttpClient.defaultHttp(connect, read, write)
    private long writeTimeoutMs = 8000;

    // ✅ нужно для MlHealthProbe.getHealthStartupLogLevel()
    private String healthStartupLogLevel = "INFO";
}
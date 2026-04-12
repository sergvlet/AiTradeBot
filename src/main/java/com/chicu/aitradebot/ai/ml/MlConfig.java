package com.chicu.aitradebot.ai.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MlProperties.class)
public class MlConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ml", name = "enabled", havingValue = "true")
    public MlClient mlClient(MlProperties props, ObjectMapper om) {

        long connect = Math.max(1, props.getConnectTimeoutMs());
        long read = Math.max(1, props.getReadTimeoutMs());
        long write = props.getWriteTimeoutMs() > 0 ? props.getWriteTimeoutMs() : read;

        OkHttpClient http = MlHttpClient.defaultHttp(connect, read, write);

        String apiKey = props.getApiKey();
        if (apiKey != null && apiKey.isBlank()) apiKey = null;

        return new MlHttpClient(http, om, trimSlash(props.getBaseUrl()), apiKey);
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
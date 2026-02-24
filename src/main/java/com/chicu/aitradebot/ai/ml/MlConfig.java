package com.chicu.aitradebot.ai.ml;


import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MlConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ml", name = "enabled", havingValue = "true")
    public MlClient mlClient(MlProperties props, ObjectMapper om) {

        OkHttpClient http = MlHttpClient.defaultHttp(
                props.getConnectTimeoutMs(),
                props.getReadTimeoutMs(),
                props.getReadTimeoutMs() // writeTimeout = readTimeout
        );

        String apiKey = props.getApiKey();

        // Можно оставить interceptor (не мешает), но тогда главное — не задублировать заголовок.
        // Если MlHttpClient сам добавляет X-API-KEY — interceptor не нужен.
        if (apiKey != null && !apiKey.isBlank()) {
            String key = apiKey.trim();
            Interceptor auth = chain -> {
                Request req = chain.request().newBuilder()
                        .header("X-API-KEY", key)
                        .build();
                return chain.proceed(req);
            };
            http = http.newBuilder().addInterceptor(auth).build();
        }

        return new MlHttpClient(http, om, trimSlash(props.getBaseUrl()), apiKey);
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
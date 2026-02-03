package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dataset.MlStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        MlStorageProperties.class,
        MlProperties.class
})
public class MlConfig {

    /**
     * KeyFactory нужен как bean (иначе могут падать сервисы, если они ждут DI).
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelKeyFactory modelKeyFactory() {
        return new ModelKeyFactory();
    }

    /**
     * ML HTTP клиент (для /health, /predict и любых вызовов ML).
     *
     * Зачем здесь таймауты:
     * - чтобы python sidecar НЕ мог повесить торговлю,
     * - но при этом не ронять приложение, если ML выключен или недоступен.
     *
     * Важно:
     * - OkHttpClient берём из общего HttpClientConfig (пул, DNS, keep-alive и т.д.)
     * - если его нет — создаём локальный fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public MlClient mlClient(ObjectProvider<OkHttpClient> okProvider,
                             ObjectMapper om,
                             MlProperties props) {

        OkHttpClient base = okProvider.getIfAvailable(() ->
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofMillis(1500))
                        .readTimeout(Duration.ofMillis(8000))
                        .callTimeout(Duration.ofSeconds(15))
                        .build()
        );

        // Берём таймауты из ml.* (новая схема)
        int connectMs = clamp(props.getConnectTimeoutMs(), 200, 30_000);
        int readMs    = clamp(props.getReadTimeoutMs(), 200, 120_000);

        // callTimeout — верхняя граница на весь вызов.
        // Делаем чуть больше readTimeout, чтобы не обрубало на ровном месте.
        int callMs = clamp(readMs + 1000, 500, 180_000);

        OkHttpClient tuned = base.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .readTimeout(Duration.ofMillis(readMs))
                .callTimeout(Duration.ofMillis(callMs))
                .build();

        return new MlClient(tuned, om, props);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

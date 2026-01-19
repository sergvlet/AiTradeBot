package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dataset.MlStorageProperties;
import com.chicu.aitradebot.ai.ml.sidecar.props.MlSidecarProperties;
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
        MlSidecarProperties.class,
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
     * Нужен для MlHealthProbe (и любых legacy вызовов).
     *
     * ВАЖНО:
     * - OkHttpClient берём из твоего HttpClientConfig (чтобы не было дубля по имени).
     * - если вдруг OkHttpClient bean отсутствует — создадим локальный fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public MlClient mlClient(ObjectProvider<OkHttpClient> okProvider,
                             ObjectMapper om,
                             MlProperties props) {

        OkHttpClient base = okProvider.getIfAvailable(() ->
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build()
        );

        int timeoutMs = Math.max(500, props.getTimeoutMs());

        OkHttpClient tuned = base.newBuilder()
                .callTimeout(Duration.ofMillis(timeoutMs))
                .build();

        return new MlClient(tuned, om, props);
    }
}

package com.chicu.aitradebot.config.http;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class MarketRestTemplateConfig {

    @Bean
    public PoolingHttpClientConnectionManager marketConnManager() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);
        cm.setDefaultMaxPerRoute(50);
        return cm;
    }

    @Bean
    public CloseableHttpClient marketHttpClient(PoolingHttpClientConnectionManager marketConnManager) {

        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))            // подключение
                .setConnectionRequestTimeout(Timeout.ofSeconds(3))  // ждать свободный коннект из пула
                .setResponseTimeout(Timeout.ofSeconds(12))          // ✅ общий лимит ответа (важно!)
                .build();

        return HttpClients.custom()
                .setConnectionManager(marketConnManager)
                .setDefaultRequestConfig(cfg)
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofSeconds(20))
                .build();
    }

    @Bean
    @Qualifier("marketRestTemplate")
    public RestTemplate marketRestTemplate(CloseableHttpClient marketHttpClient) {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(marketHttpClient));
    }
}

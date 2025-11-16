// com/chicu/aitradebot/config/HttpLoggingConfig.java
package com.chicu.aitradebot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

@Configuration
public class HttpLoggingConfig {

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter f = new CommonsRequestLoggingFilter();
        f.setIncludeClientInfo(true);
        f.setIncludeQueryString(true);
        f.setIncludeHeaders(true);     // видно заголовки (осторожно с auth)
        f.setIncludePayload(true);     // видно тело JSON
        f.setMaxPayloadLength(20_000);
        f.setAfterMessagePrefix("HTTP REQUEST >>> ");
        return f;
    }
}

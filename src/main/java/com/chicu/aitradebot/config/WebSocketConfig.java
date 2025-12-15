package com.chicu.aitradebot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // брокер для топиков
        registry.enableSimpleBroker("/topic", "/queue");
        // префикс для @MessageMapping (если используешь)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // ВАЖНО: endpoint ДОЛЖЕН совпадать с тем, что в JS
        registry.addEndpoint("/ws/strategy")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}

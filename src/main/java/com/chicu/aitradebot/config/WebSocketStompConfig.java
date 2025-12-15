package com.chicu.aitradebot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        registry.addEndpoint("/ws/strategy")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws/strategy")
                .setAllowedOriginPatterns("*");

        log.info("✅ WebSocket STOMP endpoint /ws/strategy зарегистрирован");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {

        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");

        log.info("✅ SimpleBroker включён на /topic");
    }
}

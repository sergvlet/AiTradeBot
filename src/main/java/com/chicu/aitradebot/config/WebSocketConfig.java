package com.chicu.aitradebot.config;

import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CandleWebSocketHandler candleWs;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(candleWs, "/ws/candles")
                .setAllowedOrigins("*");
    }
}

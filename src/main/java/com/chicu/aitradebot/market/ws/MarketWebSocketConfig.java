package com.chicu.aitradebot.market.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class MarketWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        registry.addHandler(new CandleWebSocketHandler(), "/ws/candles")
                .setAllowedOrigins("*");

        registry.addHandler(new TradeWebSocketHandler(), "/ws/trades")
                .setAllowedOrigins("*");
    }
}

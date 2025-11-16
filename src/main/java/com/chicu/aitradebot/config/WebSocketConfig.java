package com.chicu.aitradebot.config;

import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CandleWebSocketHandler candleWebSocketHandler;
    private final TradeWebSocketHandler tradeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(candleWebSocketHandler, "/ws/candles")
                .setAllowedOrigins("*");

        registry.addHandler(tradeWebSocketHandler, "/ws/trades")
                .setAllowedOrigins("*");
    }
}

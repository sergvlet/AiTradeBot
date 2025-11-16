package com.chicu.aitradebot.config;

import com.chicu.aitradebot.web.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.web.ws.TradeWebSocketHandler;
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
        // Свечи: /ws/candles/BTCUSDT
        registry.addHandler(candleWebSocketHandler, "/ws/candles/*")
                .setAllowedOrigins("*");

        // Сделки: /ws/trades/{chatId}/{symbol}
        registry.addHandler(tradeWebSocketHandler, "/ws/trades/*/*")
                .setAllowedOrigins("*");
    }
}

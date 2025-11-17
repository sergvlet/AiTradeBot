package com.chicu.aitradebot.config;

import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CandleWebSocketHandler candleWebSocketHandler;
    private final TradeWebSocketHandler tradeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        log.info("🌐 WebSocketConfig: регистрируем /ws/candles и /ws/trades");

        registry.addHandler(candleWebSocketHandler, "/ws/candles")
                .setAllowedOriginPatterns("*");

        registry.addHandler(tradeWebSocketHandler, "/ws/trades")
                .setAllowedOriginPatterns("*");
    }
}

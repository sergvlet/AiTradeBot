package com.chicu.aitradebot.config;

import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import com.chicu.aitradebot.market.ws.CandleWebSocketHandler;
import com.chicu.aitradebot.market.ws.MarketStreamWebSocketHandler;
import com.chicu.aitradebot.market.ws.TradeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** ✔ теперь только SPOT WS */
    private final BinanceSpotWebSocketClient binanceSpotWsClient;

    // ==============================
    //  REGISTER BEANS
    // ==============================

    @Bean
    public CandleWebSocketHandler candleWebSocketHandler() {
        return new CandleWebSocketHandler(binanceSpotWsClient);
    }

    @Bean
    public MarketStreamWebSocketHandler marketStreamWebSocketHandler() {
        return new MarketStreamWebSocketHandler();
    }

    @Bean
    public TradeWebSocketHandler tradeWebSocketHandler() {
        return new TradeWebSocketHandler();
    }

    // ==============================
    //  MAP URL → HANDLERS
    // ==============================

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        registry.addHandler(candleWebSocketHandler(), "/ws/candles")
                .setAllowedOrigins("*");

        registry.addHandler(marketStreamWebSocketHandler(), "/ws/market")
                .setAllowedOrigins("*");

        registry.addHandler(tradeWebSocketHandler(), "/ws/trades")
                .setAllowedOrigins("*");
    }
}

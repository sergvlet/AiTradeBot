package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.exchange.binance.ws.BinanceFuturesWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleWebSocketHandler extends TextWebSocketHandler {

    private final BinanceFuturesWebSocketClient binanceWs;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String query = session.getUri().getQuery();
        String symbol = query.split("symbol=")[1].split("&")[0];
        String timeframe = query.split("timeframe=")[1];

        log.info("🔌 WS CONNECT: {} {}", symbol, timeframe);

        String key = symbol + "|" + timeframe;

        // Подписываем на бинарный поток Binance
        binanceWs.subscribe(key, symbol, timeframe, (rawJson) -> {

            // ❗ Ничего не парсим — отправляем ОРИГИНАЛИСЬ, как от Binance
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(rawJson));
                }
            } catch (Exception e) {
                log.warn("WS send error {}", e.getMessage());
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("🧹 WS CLOSED {}", status);
    }
}

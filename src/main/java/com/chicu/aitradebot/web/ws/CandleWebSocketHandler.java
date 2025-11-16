package com.chicu.aitradebot.web.ws;

import com.chicu.aitradebot.web.ws.dto.RealtimeCandleDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CandleWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // symbol -> sessions
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions =
            new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String symbol = resolveSymbol(session);
        sessions.computeIfAbsent(symbol, s -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("🕸️ WS Candle connected: symbol={}, session={}", symbol, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.forEach((symbol, set) -> {
            if (set.remove(session)) {
                log.info("🕸️ WS Candle disconnected: symbol={}, session={}", symbol, session.getId());
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Клиент нам ничего не шлёт — игнорируем. Можно логировать при необходимости.
    }

    private String resolveSymbol(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        // /ws/candles/BTCUSDT
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }

    public void broadcastCandle(String symbol, RealtimeCandleDto dto) {
        Set<WebSocketSession> set = sessions.get(symbol);
        if (set == null || set.isEmpty()) return;

        try {
            String payload = mapper.writeValueAsString(dto);
            TextMessage msg = new TextMessage(payload);
            for (WebSocketSession s : set) {
                if (s.isOpen()) {
                    s.sendMessage(msg);
                }
            }
        } catch (IOException e) {
            log.error("Ошибка отправки свечи по WS", e);
        }
    }
}

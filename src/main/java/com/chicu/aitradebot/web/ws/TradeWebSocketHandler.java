package com.chicu.aitradebot.web.ws;

import com.chicu.aitradebot.web.ws.dto.RealtimeTradeDto;
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
public class TradeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // key = chatId:symbol
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions =
            new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String key = resolveKey(session);
        sessions.computeIfAbsent(key, s -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("🕸️ WS Trade connected: key={}, session={}", key, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.forEach((k, set) -> {
            if (set.remove(session)) {
                log.info("🕸️ WS Trade disconnected: key={}, session={}", k, session.getId());
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Можно обработать команды от клиента, но нам не нужно — игнорируем
    }

    private String resolveKey(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        // /ws/trades/{chatId}/{symbol}
        String[] parts = path.split("/");
        String chatId = parts[parts.length - 2];
        String symbol = parts[parts.length - 1];
        return chatId + ":" + symbol;
    }

    public void broadcastTrade(Long chatId, String symbol, RealtimeTradeDto dto) {
        String key = chatId + ":" + symbol;
        Set<WebSocketSession> set = sessions.get(key);
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
            log.error("Ошибка отправки сделки по WS", e);
        }
    }
}

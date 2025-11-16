package com.chicu.aitradebot.market.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public class MarketStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("🔌 WebSocket клиент подключен");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("❌ WebSocket клиент отключен");
    }

    public static void broadcast(String json) {
        sessions.forEach(s -> {
            try {
                s.sendMessage(new TextMessage(json));
            } catch (Exception ignored) {}
        });
    }
}

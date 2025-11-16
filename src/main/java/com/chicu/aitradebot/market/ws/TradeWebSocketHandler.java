package com.chicu.aitradebot.market.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket /ws/trades
 * Клиент: ws://host/ws/trades?chatId=123&symbol=BTCUSDT
 * Сервер шлёт сделки (ордеры/PNL) только тому, кто подписан на этот chatId+symbol.
 */
@Component
@Slf4j
public class TradeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * key = chatId|SYMBOL
     */
    private final Map<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        String query = uri != null ? uri.getQuery() : null;

        long chatId = 0L;
        String symbol = "BTCUSDT";

        if (query != null) {
            Map<String, String> q = parseQuery(query);
            if (q.containsKey("chatId")) {
                try {
                    chatId = Long.parseLong(q.get("chatId"));
                } catch (NumberFormatException ignored) {}
            }
            if (q.containsKey("symbol")) {
                symbol = q.get("symbol").toUpperCase(Locale.ROOT);
            }
        }

        String key = buildKey(chatId, symbol);
        session.getAttributes().put("chatId", chatId);
        session.getAttributes().put("symbol", symbol);
        session.getAttributes().put("subKey", key);

        subscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(session);

        log.info("🟢 WS TRADES подключен: {} chatId={} symbol={}", session.getId(), chatId, symbol);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object keyObj = session.getAttributes().get("subKey");
        if (keyObj != null) {
            String key = keyObj.toString();
            Set<WebSocketSession> set = subscribers.get(key);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    subscribers.remove(key);
                }
            }
        }
        log.info("🔴 WS TRADES закрыт: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Можно сделать ping/pong или фильтры, пока не нужно.
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private String buildKey(long chatId, String symbol) {
        return chatId + "|" + symbol.toUpperCase(Locale.ROOT);
    }

    /**
     * Вызови это из OrderService / стратегии, когда создаётся или закрывается сделка.
     * Поля подобраны под твой strategy-chart.js.
     */
    public void broadcastTrade(long chatId, String symbol, Map<String, Object> tradePayload) {
        String key = buildKey(chatId, symbol);
        Set<WebSocketSession> set = subscribers.get(key);
        if (set == null || set.isEmpty()) {
            return;
        }

        try {
            String json = mapper.writeValueAsString(tradePayload);
            TextMessage msg = new TextMessage(json);

            for (WebSocketSession s : set) {
                if (s.isOpen()) {
                    try {
                        s.sendMessage(msg);
                    } catch (IOException e) {
                        log.warn("WS TRADES send error {}: {}", s.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ broadcastTrade error chatId={} symbol={} : {}", chatId, symbol, e.getMessage());
        }
    }
}

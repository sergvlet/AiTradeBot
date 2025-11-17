package com.chicu.aitradebot.market.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket-хэндлер для real-time сделок (BUY/SELL):
 *
 *   /ws/trades?symbol=BTCUSDT
 *
 * Вызывается из:
 *    MarketStreamManager.pushTrade()
 *    SmartFusionStrategy (когда делает ордер)
 *
 * Пушит JSON:
 * {
 *    "symbol": "BTCUSDT",
 *    "price": 94800.5,
 *    "qty": 0.002,
 *    "side": "BUY",
 *    "ts": 1731628400000
 * }
 */
@Slf4j
@Component
public class TradeWebSocketHandler implements WebSocketHandler {

    /**
     * Каналы:
     *   key:  SYMBOL → "BTCUSDT"
     *   val:  Set<WebSocketSession>
     */
    private final Map<String, Set<WebSocketSession>> channels = new ConcurrentHashMap<>();

    /**
     * Обратный индекс для удаления:
     *   sessionId → SYMBOL
     */
    private final Map<String, String> sessionChannel = new ConcurrentHashMap<>();

    // ==========================================================================
    // WebSocketHandler API
    // ==========================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        Map<String, String> params = parseQuery(uri);

        String symbol = params.getOrDefault("symbol", "").trim().toUpperCase(Locale.ROOT);

        if (symbol.isBlank()) {
            log.warn("❌ /ws/trades: symbol не указан, закрываю сессию {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        channels
                .computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet())
                .add(session);

        sessionChannel.put(session.getId(), symbol);

        log.info("🔌 WS /ws/trades CONNECT id={} symbol={}", session.getId(), symbol);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // Клиент нам ничего не должен слать
        log.debug("📩 WS /ws/trades message from {}: {}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable e) throws Exception {
        log.warn("⚠️ WS /ws/trades transport error id={} : {}", session != null ? session.getId() : "null", e.getMessage());
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
        removeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("🔌 WS /ws/trades CLOSED id={} status={}", session.getId(), status);
        removeSession(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ==========================================================================
    // Публичный метод для MarketStreamManager
    // ==========================================================================

    /**
     * Рассылка сделки всем подписчикам символа.
     *
     * @param tsMillis timestamp (millis)
     * @param symbol   BTCUSDT
     * @param data     поля: symbol, price, qty, side, ts
     */
    public void broadcastTrade(long tsMillis, String symbol, Map<String, Object> data) {
        if (symbol == null || data == null) return;

        String sym = symbol.toUpperCase(Locale.ROOT);

        Set<WebSocketSession> sessions = channels.get(sym);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String json = toJson(data);

        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) continue;
            try {
                s.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("⚠️ WS /ws/trades send error id={} : {}", s.getId(), e.getMessage());
            }
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void removeSession(WebSocketSession session) {
        if (session == null) return;

        String id = session.getId();
        String symbol = sessionChannel.remove(id);

        if (symbol != null) {
            Set<WebSocketSession> set = channels.get(symbol);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    channels.remove(symbol);
                }
            }
        } else {
            // fallback
            channels.values().forEach(set -> set.remove(session));
        }
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("\"").append(e.getKey()).append("\":");

            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append("\"").append(v).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> parseQuery(URI uri) {
        if (uri == null) return Collections.emptyMap();
        String q = uri.getQuery();
        if (q == null || q.isBlank()) return Collections.emptyMap();

        Map<String, String> res = new ConcurrentHashMap<>();
        for (String p : q.split("&")) {
            if (p.isBlank()) continue;

            int idx = p.indexOf("=");
            if (idx < 0) {
                res.put(decode(p), "");
            } else {
                String key = decode(p.substring(0, idx));
                String val = decode(p.substring(idx + 1));
                res.put(key, val);
            }
        }
        return res;
    }

    private String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}

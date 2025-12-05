package com.chicu.aitradebot.market.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 💸 WebSocket для стрима сделок по символам.
 *
 * Маршрут: /ws/trades?symbol=BTCUSDC
 *
 * Логика:
 *  - при подключении клиент указывает symbol;
 *  - храним сессии по symbol;
 *  - метод broadcastTrade(symbol, payload) шлёт событие только тем, кто слушает этот symbol.
 *
 * ВАЖНО:
 *  - здесь нет логики стратегий / ордеров;
 *  - это чистый транспорт от backend → браузер.
 */
@Slf4j
@Component
public class TradeWebSocketHandler implements WebSocketHandler {

    /**
     * Каналы: symbol → множество сессий, подписанных на этот символ.
     * symbol в верхнем регистре (BTCUSDC, ETHUSDT и т.п.).
     */
    private static final Map<String, Set<WebSocketSession>> CHANNELS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        String query = uri != null && uri.getQuery() != null ? uri.getQuery() : "";

        Map<String, String> params = parseQuery(query);
        String symbol = Optional.ofNullable(params.get("symbol"))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .orElse("BTCUSDT");

        CHANNELS.computeIfAbsent(symbol, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(session);

        log.info("🔌 [WS-TRADES] CONNECT symbol={} from {} (subscribers={})",
                symbol, session.getRemoteAddress(), CHANNELS.get(symbol).size());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // Обычно клиент сюда ничего важного не шлёт, можно игнорировать либо логировать пинги.
        if (message instanceof TextMessage text) {
            log.debug("💬 [WS-TRADES] msg from {}: {}",
                    session.getRemoteAddress(), text.getPayload());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("⚠️ [WS-TRADES] Transport error from {}: {}",
                session.getRemoteAddress(), exception.getMessage(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Удаляем сессию из всех каналов (обычно ровно из одного).
        CHANNELS.forEach((symbol, sessions) -> {
            if (sessions.remove(session)) {
                log.info("❌ [WS-TRADES] DISCONNECT {} from symbol={} (subscribers={})",
                        session.getRemoteAddress(), symbol, sessions.size());
            }
        });
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * Глобальный метод для отправки сделки по конкретному символу.
     *
     * @param symbol  символ (BTCUSDC и т.п., регистр не важен)
     * @param payload строка JSON или любой объект (toString()).
     */
    public static void broadcastTrade(String symbol, Object payload) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        String key = symbol.toUpperCase(Locale.ROOT);
        Set<WebSocketSession> sessions = CHANNELS.get(key);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String text;
        if (payload == null) {
            text = "";
        } else if (payload instanceof String s) {
            text = s;
        } else {
            text = payload.toString();
        }

        TextMessage msg = new TextMessage(text);

        sessions.forEach(session -> {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(msg);
            } catch (IOException e) {
                log.warn("⚠️ [WS-TRADES] Ошибка при отправке сделки [{}] клиенту {}: {}",
                        key, session.getRemoteAddress(), e.getMessage());
            }
        });
    }

    // ====================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================

    private Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }

        String[] pairs = query.split("&");
        Map<String, String> res = new HashMap<>();

        for (String p : pairs) {
            if (p.isEmpty()) continue;

            int idx = p.indexOf('=');
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

package com.chicu.aitradebot.market.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🌐 WebSocket-канал для общего рыночного стрима (типы тикеров, агрегированные цены и т.п.).
 *
 * Маршрут (обычно): /ws/market
 *
 * Использование:
 *  - фронт подключается к /ws/market — получает все события, которые backend шлёт через broadcast().
 *  - backend (любой сервис) вызывает MarketStreamWebSocketHandler.broadcast(jsonStringOrObject).
 *
 * НИКАКОЙ бизнес-логики стратегий внутри.
 */
@Slf4j
public class MarketStreamWebSocketHandler extends TextWebSocketHandler {

    /**
     * Активные WebSocket-сессии фронта.
     */
    private static final Set<WebSocketSession> SESSIONS = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.add(session);
        log.info("🔌 [WS-MARKET] CONNECT from {} (total={})",
                session.getRemoteAddress(), SESSIONS.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Обычно фронт сюда ничего полезного не шлёт — можно игнорировать или использовать как ping.
        log.debug("💬 [WS-MARKET] from {}: {}",
                session.getRemoteAddress(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session);
        log.info("❌ [WS-MARKET] DISCONNECT {} (status={}, total={})",
                session.getRemoteAddress(), status, SESSIONS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("⚠️ [WS-MARKET] Transport error from {}: {}",
                session.getRemoteAddress(), exception.getMessage(), exception);
    }

    /**
     * Глобальная рассылка рыночных данных всем подключённым клиентам.
     *
     * @param payload String или любой объект (будет сериализован через toString()).
     */
    public static void broadcast(Object payload) {
        if (SESSIONS.isEmpty()) {
            return;
        }

        String text;
        if (payload == null) {
            text = "";
        } else if (payload instanceof String s) {
            text = s;
        } else {
            // Минимальная сериализация без лишних зависимостей.
            text = payload.toString();
        }

        TextMessage msg = new TextMessage(text);

        SESSIONS.forEach(session -> {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(msg);
            } catch (IOException e) {
                log.warn("⚠️ [WS-MARKET] Ошибка при отправке сообщения клиенту {}: {}",
                        session.getRemoteAddress(), e.getMessage());
            }
        });
    }
}

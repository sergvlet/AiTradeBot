package com.chicu.aitradebot.market.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CandleWebSocketHandler implements WebSocketHandler {

    private final Map<WebSocketSession, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private record SessionInfo(String symbol, String timeframe) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String query = session.getUri().getQuery(); // symbol=BTCUSDT&timeframe=1s

            String symbol = "BTCUSDT";
            String timeframe = "1s";

            if (query != null) {
                for (String p : query.split("&")) {
                    String[] kv = p.split("=");
                    if (kv.length == 2) {
                        if (kv[0].equals("symbol")) symbol = kv[1].toUpperCase();
                        if (kv[0].equals("timeframe")) timeframe = kv[1];
                    }
                }
            }

            sessions.put(session, new SessionInfo(symbol, timeframe));
            log.info("🔌 WS подключен [{} / {}]", symbol, timeframe);

        } catch (Exception e) {
            log.error("❌ WS afterConnectionEstablished error: {}", e.getMessage());
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {}

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.warn("⚠️ WS error: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("⏹ WS закрыт");
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ----------------------------------------------------------------------
    // 📤 ЭТО ГЛАВНЫЙ МЕТОД (его вызывaет SmartFusionCandleService)
    // ----------------------------------------------------------------------
    public void broadcastTick(String symbol, String timeframe, Object candle) {
        sessions.forEach((session, info) -> {
            try {
                if (!info.symbol.equalsIgnoreCase(symbol)) return;
                if (!info.timeframe.equalsIgnoreCase(timeframe)) return;

                if (!session.isOpen()) return;

                String json = mapper.writeValueAsString(candle);
                session.sendMessage(new TextMessage(json));

            } catch (Exception e) {
                log.error("❌ WS send error: {}", e.getMessage());
            }
        });
    }
}

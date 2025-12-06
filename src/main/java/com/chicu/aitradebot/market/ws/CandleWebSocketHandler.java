package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.exchange.binance.ws.BinanceSpotWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 🕯 WebSocket-канал для свечей (kline) с Binance SPOT.
 *
 * Маршрут фронта:
 *     /ws/candles?symbol=ETHUSDT&timeframe=1m
 *
 * Логика:
 *  - парсим symbol и timeframe;
 *  - подписываемся на Binance SPOT WebSocket;
 *  - каждое raw kline-событие отправляем в браузер как TextMessage.
 */
@Slf4j
@RequiredArgsConstructor
public class CandleWebSocketHandler extends TextWebSocketHandler {

    /** ✔ Только SPOT, фьючи полностью удалены */
    private final BinanceSpotWebSocketClient spotWs;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {

        URI uri = session.getUri();
        String query = uri != null && uri.getQuery() != null ? uri.getQuery() : "";
        Map<String, String> params = QueryUtils.parseQuery(query);

        String symbol = Optional.ofNullable(params.get("symbol"))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .orElse("BTCUSDT");

        String timeframe = Optional.ofNullable(params.get("timeframe"))
                .orElse("1m");

        log.info("🔌 [WS-SPOT-CANDLES] CONNECT symbol={} timeframe={} from {}",
                symbol, timeframe, session.getRemoteAddress());

        String streamSymbol = symbol.toLowerCase(Locale.ROOT);

        // ========================================================
        // Подключение к Binance Spot WS (kline)
        // ========================================================
        spotWs.setMessageHandler((streamName, jsonRaw) -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(jsonRaw));
                }
            } catch (IOException e) {
                log.warn("⚠️ Ошибка отправки свечей {} {}: {}", symbol, timeframe, e.getMessage());
            }
        });

        spotWs.subscribeKline(streamSymbol, timeframe);
    }


    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("💬 [WS-SPOT-CANDLES] msg from {}: {}",
                session.getRemoteAddress(), message.getPayload());
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                      org.springframework.web.socket.CloseStatus status) {

        log.info("❌ [WS-SPOT-CANDLES] DISCONNECT {} (status={})",
                session.getRemoteAddress(), status);

        // Отписываться не обязательно — SPOT клиент сам держит канал
        // но при желании можешь вызвать unsubscribe()
    }


    // =====================================================================
    // Query Utils
    // =====================================================================

    private static class QueryUtils {

        static Map<String, String> parseQuery(String query) {
            if (query == null || query.isBlank()) return Map.of();

            String[] pairs = query.split("&");
            java.util.Map<String, String> res = new java.util.HashMap<>();

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

        private static String decode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }
    }
}

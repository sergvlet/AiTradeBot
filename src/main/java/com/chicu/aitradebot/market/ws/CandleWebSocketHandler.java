package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
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
 * WebSocket-хэндлер для стрима свечей:
 *
 *   /ws/candles?symbol=BTCUSDT&timeframe=1m
 *
 * SmartFusionCandleService вызывает broadcastTick(...), а этот класс
 * пушит JSON-ы всем подписанным клиентам по каналу "symbol|timeframe".
 */
@Slf4j
@Component
public class CandleWebSocketHandler implements WebSocketHandler {

    /**
     * Канал = SYMBOL|TF → набор сессий.
     *   key:  "BTCUSDT|1m"
     *   val:  Set<WebSocketSession>
     */
    private final Map<String, Set<WebSocketSession>> channels = new ConcurrentHashMap<>();

    /**
     * Быстрый обратный индекс:
     *   sessionId → channelKey
     */
    private final Map<String, String> sessionChannel = new ConcurrentHashMap<>();

    // ==========================================================================
    // WebSocketHandler API
    // ==========================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        Map<String, String> params = parseQuery(uri);

        String symbol = params.getOrDefault("symbol", "").trim();
        String timeframe = params.getOrDefault("timeframe", "1m").trim();

        if (symbol.isEmpty()) {
            log.warn("❌ /ws/candles: symbol не передан, закрываю сессию {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        String sym = symbol.toUpperCase(Locale.ROOT);
        String tf = timeframe;
        String channel = channelKey(sym, tf);

        channels
                .computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(session);

        sessionChannel.put(session.getId(), channel);

        log.info("✅ WS /ws/candles CONNECT id={} channel={} (symbol={}, tf={})",
                session.getId(), channel, sym, tf);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // Клиент нам ничего не должен отправлять — просто логируем на всякий.
        log.debug("📩 WS /ws/candles message from {}: {}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("⚠️ WS /ws/candles transport error id={} : {}",
                session != null ? session.getId() : "null",
                exception.getMessage(), exception);

        if (session != null && session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
        removeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        log.info("🔌 WS /ws/candles CLOSED id={} status={}", session.getId(), closeStatus);
        removeSession(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ==========================================================================
    // Паблик для свечей: вызывается из SmartFusionCandleService
    // ==========================================================================

    /**
     * Пуш свечи всем подписчикам канала (symbol + timeframe).
     *
     * @param symbol    BTCUSDT
     * @param timeframe 1s / 1m / 5m / ...
     * @param c         свеча SmartFusionCandleService.Candle (ts, o, h, l, c)
     */
    public void broadcastTick(String symbol, String timeframe, SmartFusionCandleService.Candle c) {
        if (symbol == null || timeframe == null || c == null) {
            return;
        }

        String sym = symbol.toUpperCase(Locale.ROOT);
        String tf = timeframe.trim();
        String channel = channelKey(sym, tf);

        Set<WebSocketSession> sessions = channels.get(channel);
        if (sessions == null || sessions.isEmpty()) {
            // никого нет на этом канале — просто выходим
            return;
        }

        // Небольшой цвет, чтобы фронт мог как-то различать (по желанию).
        String color = "g";

        // Фронт ждёт именно такое имя поля времени: "t" = millis.
        String json = "{"
                + "\"t\":" + c.getTime()
                + ",\"o\":" + c.open()
                + ",\"h\":" + c.high()
                + ",\"l\":" + c.low()
                + ",\"c\":" + c.close()
                + ",\"tf\":\"" + tf + "\""
                + ",\"s\":\"" + color + "\""
                + "}";

        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) {
                continue;
            }
            try {
                s.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("⚠️ WS /ws/candles push error to {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void removeSession(WebSocketSession session) {
        if (session == null) return;

        String id = session.getId();
        String channel = sessionChannel.remove(id);

        if (channel != null) {
            Set<WebSocketSession> set = channels.get(channel);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    channels.remove(channel);
                }
            }
        } else {
            // fallback — вдруг где-то не успели записать
            channels.values().forEach(set -> set.remove(session));
        }
    }

    private String channelKey(String symbol, String timeframe) {
        return symbol.toUpperCase(Locale.ROOT) + "|" + timeframe;
    }

    private Map<String, String> parseQuery(URI uri) {
        if (uri == null) return Collections.emptyMap();
        String q = uri.getQuery();
        if (q == null || q.isBlank()) return Collections.emptyMap();

        Map<String, String> res = new ConcurrentHashMap<>();
        String[] parts = q.split("&");
        for (String part : parts) {
            if (part.isBlank()) continue;
            int idx = part.indexOf('=');
            if (idx < 0) {
                String key = decode(part);
                res.put(key, "");
            } else {
                String key = decode(part.substring(0, idx));
                String val = decode(part.substring(idx + 1));
                res.put(key, val);
            }
        }
        return res;
    }

    private String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}

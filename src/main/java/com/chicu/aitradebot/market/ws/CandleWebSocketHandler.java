package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * Универсальный realtime WebSocket канал свечей
 * Поддерживает:
 *   - авто-poll Binance → tick сообщения
 *   - внешнюю отправку tick (broadcastTick)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandleWebSocketHandler extends TextWebSocketHandler {

    private final ExchangeClientFactory exchangeClientFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    /** CHANNEL = "BTCUSDT|1m" */
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    private static class ChannelState {
        final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
        volatile ScheduledFuture<?> task;
    }

    // ======================================================
    // 🔥 Публичный метод: позволяет вручную отправлять tick
    // ======================================================
    public void broadcastTick(String symbol, String timeframe, CandleProvider.Candle c) {
        String key = symbol.toUpperCase() + "|" + timeframe;

        ChannelState state = channels.get(key);
        if (state == null || state.sessions.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> candle = Map.of(
                    "time", c.time(),
                    "open", c.open(),
                    "high", c.high(),
                    "low",  c.low(),
                    "close", c.close(),
                    "volume", c.volume()
            );

            Map<String, Object> payload = Map.of(
                    "type", "tick",
                    "symbol", symbol,
                    "timeframe", timeframe,
                    "candle", candle
            );

            String json = mapper.writeValueAsString(payload);
            TextMessage msg = new TextMessage(json);

            for (WebSocketSession s : state.sessions) {
                if (s.isOpen()) s.sendMessage(msg);
            }

        } catch (Exception e) {
            log.error("broadcastTick error", e);
        }
    }

    // ======================================================
    // CONNECTION
    // ======================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {

        URI uri = session.getUri();
        if (uri == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        String symbol = Optional.ofNullable(params.getFirst("symbol"))
                .orElse("BTCUSDT").toUpperCase();

        String tf = Optional.ofNullable(params.getFirst("timeframe"))
                .orElse("1m");

        String key = symbol + "|" + tf;

        ChannelState state = channels.computeIfAbsent(key, k -> new ChannelState());
        state.sessions.add(session);

        log.info("✅ WS CONNECT {} ({})", key, session.getId());

        startPollingTaskIfNeeded(key, symbol, tf, state);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            URI uri = session.getUri();
            if (uri == null) return;

            MultiValueMap<String, String> params =
                    UriComponentsBuilder.fromUri(uri).build().getQueryParams();

            String symbol = params.getFirst("symbol").toUpperCase();
            String tf = params.getFirst("timeframe");

            String key = symbol + "|" + tf;

            ChannelState state = channels.get(key);
            if (state != null) {
                state.sessions.remove(session);

                if (state.sessions.isEmpty()) {
                    if (state.task != null) state.task.cancel(false);
                    channels.remove(key);
                    log.info("🧹 WS channel {} stopped", key);
                }
            }

        } catch (Exception e) {
            log.error("WS close error", e);
        }
    }

    // ======================================================
    // AUTO POLLING
    // ======================================================

    private void startPollingTaskIfNeeded(
            String key,
            String symbol,
            String tf,
            ChannelState state
    ) {
        if (state.task != null && !state.task.isCancelled()) return;

        long period = switch (tf) {
            case "1s" -> 1000;
            case "1m" -> 3000;
            case "5m" -> 5000;
            case "15m" -> 10_000;
            case "1h" -> 30_000;
            default -> 4000;
        };

        state.task = scheduler.scheduleAtFixedRate(
                () -> pollOne(symbol, tf, state),
                0,
                period,
                TimeUnit.MILLISECONDS
        );

        log.info("▶️ Polling started {} ({} ms)", key, period);
    }

    private void pollOne(String symbol, String tf, ChannelState state) {
        try {
            if (state.sessions.isEmpty()) return;

            ExchangeClient cl = exchangeClientFactory.getClient("BINANCE", NetworkType.MAINNET);

            List<ExchangeClient.Kline> k = cl.getKlines(symbol, tf, 1);
            if (k == null || k.isEmpty()) return;

            ExchangeClient.Kline bar = k.get(0);

            // создаём CandleProvider.Candle
            CandleProvider.Candle c = new CandleProvider.Candle(
                    bar.openTime(),
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume()
            );

            broadcastTick(symbol, tf, c);

        } catch (Exception e) {
            log.error("pollOne error {} {}", symbol, tf, e);
        }
    }
}

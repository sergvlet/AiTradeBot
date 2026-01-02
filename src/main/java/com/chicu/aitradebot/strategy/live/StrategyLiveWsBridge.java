package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyLiveWsBridge {

    private final SimpMessagingTemplate ws;
    private final UiStrategyLayerService uiLayers;

    /**
     * key = chatId|strategy|type|symbol
     */
    private final Map<String, Integer> lastEventHash = new ConcurrentHashMap<>();

    // =====================================================
    // PUBLIC API
    // =====================================================
    public void publish(StrategyLiveEvent ev) {

        if (ev == null) {
            log.warn("LIVE publish called with NULL event");
            return;
        }

        if (ev.getChatId() == null || ev.getStrategyType() == null) {
            log.warn(
                    "LIVE SKIP missing chatId/strategy type={} chatId={} strategy={}",
                    ev.getType(), ev.getChatId(), ev.getStrategyType()
            );
            return;
        }

        ev.normalize();

        String symbol = normalizeSymbol(ev.getSymbol());
        ev.setSymbol(symbol);

        // =================================================
        // TIME NORMALIZATION
        // =================================================
        switch (ev.getType()) {

            case "price" -> {
                ev.setTime(StrategyLiveEvent.nowMillis());
            }

            case "candle" -> {
                if (ev.getTime() <= 0) {
                    log.debug(
                            "DROP candle without time chatId={} strategy={} symbol={}",
                            ev.getChatId(), ev.getStrategyType(), ev.getSymbol()
                    );
                    return;
                }
            }

            default -> {
                if (ev.getTime() <= 0) {
                    ev.setTime(StrategyLiveEvent.nowMillis());
                }
            }
        }

        // =================================================
        // 🔕 DEDUP (кроме price / candle)
        // =================================================
        if (shouldDedup(ev.getType())) {
            String key = buildKey(ev);
            int hash = safeHash(ev);

            Integer prev = lastEventHash.put(key, hash);
            if (prev != null && prev == hash) {
                log.trace(
                        "DEDUP SKIP type={} chatId={} strategy={} symbol={}",
                        ev.getType(), ev.getChatId(), ev.getStrategyType(), ev.getSymbol()
                );
                return;
            }
        }

        // =================================================
        // 🔇 ЛОГИРОВАНИЕ (АККУРАТНО)
        // =================================================

        if (log.isDebugEnabled()) {
            log.debug(
                    "LIVE → type={} chatId={} strategy={} symbol={}",
                    ev.getType(),
                    ev.getChatId(),
                    ev.getStrategyType(),
                    ev.getSymbol()
            );
        }

        // =================================================
        // WS PUSH
        // =================================================
        String dest = "/topic/strategy/"
                      + ev.getChatId()
                      + "/"
                      + ev.getStrategyType().name();

        ws.convertAndSend(dest, ev);

        // =================================================
        // UI LAYER
        // =================================================
        try {
            persistUiLayer(ev);
        } catch (Exception e) {
            log.warn(
                    "UI persist failed type={} chatId={} strategy={} symbol={}",
                    ev.getType(), ev.getChatId(), ev.getStrategyType(), symbol, e
            );
        }
    }

    // =====================================================
    // UI PERSIST
    // =====================================================
    private void persistUiLayer(StrategyLiveEvent ev) {

        if (ev.getSymbol() == null) return;

        Instant time = Instant.ofEpochMilli(ev.getTime());

        switch (ev.getType()) {

            case "levels" -> {
                if (ev.getLevels() == null || ev.getLevels().isEmpty()) return;

                List<Double> levels = ev.getLevels().stream()
                        .filter(l -> l != null && l.getPrice() != null)
                        .map(l -> l.getPrice().doubleValue())
                        .toList();

                if (!levels.isEmpty()) {
                    uiLayers.saveLevels(
                            ev.getChatId(),
                            ev.getStrategyType(),
                            ev.getSymbol(),
                            time,
                            levels
                    );
                }
            }

            case "zone" -> {
                if (ev.getZone() == null) return;
                if (ev.getZone().getTop() == null || ev.getZone().getBottom() == null) return;

                uiLayers.saveZone(
                        ev.getChatId(),
                        ev.getStrategyType(),
                        ev.getSymbol(),
                        time,
                        ev.getZone().getTop().doubleValue(),
                        ev.getZone().getBottom().doubleValue(),
                        ev.getZone().getColor()
                );
            }

            case "tp_sl" -> {
                if (ev.getTpSl() == null) return;

                Double tp = ev.getTpSl().getTp() != null
                        ? ev.getTpSl().getTp().doubleValue()
                        : null;

                Double sl = ev.getTpSl().getSl() != null
                        ? ev.getTpSl().getSl().doubleValue()
                        : null;

                uiLayers.saveTpSl(
                        ev.getChatId(),
                        ev.getStrategyType(),
                        ev.getSymbol(),
                        time,
                        tp,
                        sl
                );
            }

            default -> {
                // intentionally ignored
            }
        }
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private boolean shouldDedup(String type) {
        return !"price".equals(type) && !"candle".equals(type);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase();
        return s.isEmpty() ? null : s;
    }

    private String buildKey(StrategyLiveEvent ev) {
        return ev.getChatId()
               + "|" + ev.getStrategyType()
               + "|" + ev.getType()
               + "|" + ev.getSymbol();
    }

    private int safeHash(StrategyLiveEvent ev) {
        try {
            return ev.hashCode();
        } catch (Exception e) {
            return System.identityHashCode(ev);
        }
    }
}

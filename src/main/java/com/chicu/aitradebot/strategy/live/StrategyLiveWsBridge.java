package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyLiveWsBridge {

    private final SimpMessagingTemplate ws;
    private final UiStrategyLayerService uiLayers;

    private final Map<String, Integer> lastEventHash = new ConcurrentHashMap<>();
    private static final int MAX_DEDUP_KEYS = 50_000;

    public void publish(StrategyLiveEvent ev) {
        if (ev == null) {
            log.warn("LIVE publish called with NULL event");
            return;
        }
        if (ev.getChatId() == null || ev.getStrategyType() == null) {
            log.warn("LIVE SKIP missing chatId/strategy type={} chatId={} strategy={}", ev.getType(), ev.getChatId(), ev.getStrategyType());
            return;
        }

        ev.normalize();
        if (ev.getType() == null || ev.getType().isBlank()) {
            log.warn("LIVE SKIP missing type chatId={} strategy={} symbol={}", ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
            return;
        }

        String symbol = normalizeSymbol(ev.getSymbol());
        ev.setSymbol(symbol);

        switch (ev.getType()) {
            case "price" -> ev.setTime(StrategyLiveEvent.nowMillis());
            case "candle" -> {
                if (ev.getTime() <= 0) {
                    log.debug("DROP candle without time chatId={} strategy={} symbol={}", ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
            }
            default -> {
                if (ev.getTime() <= 0) ev.setTime(StrategyLiveEvent.nowMillis());
            }
        }

        if (shouldDedup(ev.getType())) {
            if (lastEventHash.size() > MAX_DEDUP_KEYS) {
                lastEventHash.clear();
                log.warn("LIVE DEDUP map cleared (size>{})", MAX_DEDUP_KEYS);
            }
            String key = buildKey(ev);
            int hash;
            try { hash = ev.dedupHash(); }
            catch (Exception e) { hash = safeHash(ev); }
            Integer prev = lastEventHash.put(key, hash);
            if (prev != null && prev == hash) return;
        }

        String dest = "/topic/strategy/" + ev.getChatId() + "/" + ev.getStrategyType().name();
        ws.convertAndSend(dest, ev);

        try {
            persistUiLayer(ev);
        } catch (Exception e) {
            log.warn("UI persist failed type={} chatId={} strategy={} symbol={}", ev.getType(), ev.getChatId(), ev.getStrategyType(), symbol, e);
        }
    }

    private void persistUiLayer(StrategyLiveEvent ev) {
        if (ev.getSymbol() == null) return;
        Instant time = Instant.ofEpochMilli(ev.getTime() > 0 ? ev.getTime() : StrategyLiveEvent.nowMillis());

        switch (ev.getType()) {
            case "levels" -> {
                if (ev.getLevels() == null || ev.getLevels().isEmpty()) {
                    uiLayers.clearLevels(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                var levels = ev.getLevels().stream()
                        .filter(l -> l != null && l.getPrice() != null)
                        .map(l -> l.getPrice().doubleValue())
                        .toList();
                if (levels.isEmpty()) {
                    uiLayers.clearLevels(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                uiLayers.saveLevels(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time, levels);
            }
            case "zone" -> {
                if (ev.getZone() == null || ev.getZone().getTop() == null || ev.getZone().getBottom() == null) {
                    uiLayers.clearZone(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                uiLayers.saveZone(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time,
                        ev.getZone().getTop().doubleValue(), ev.getZone().getBottom().doubleValue(), ev.getZone().getColor());
            }
            case "tp_sl" -> {
                if (ev.getTpSl() == null) {
                    uiLayers.clearTpSl(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                Double tp = ev.getTpSl().getTp() != null ? ev.getTpSl().getTp().doubleValue() : null;
                Double sl = ev.getTpSl().getSl() != null ? ev.getTpSl().getSl().doubleValue() : null;
                if (tp == null && sl == null) {
                    uiLayers.clearTpSl(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                uiLayers.saveTpSl(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time, tp, sl);
            }
            case "window_zone" -> {
                if (ev.getWindowZone() == null || ev.getWindowZone().getHigh() == null || ev.getWindowZone().getLow() == null) {
                    uiLayers.clearWindowZone(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                uiLayers.saveWindowZone(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time,
                        ev.getWindowZone().getHigh().doubleValue(), ev.getWindowZone().getLow().doubleValue());
            }
            case "price_line" -> {
                if (ev.getPriceLine() == null || ev.getPriceLine().getName() == null || ev.getPriceLine().getPrice() == null) {
                    uiLayers.clearPriceLines(ev.getChatId(), ev.getStrategyType(), ev.getSymbol());
                    return;
                }
                uiLayers.upsertPriceLine(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time,
                        ev.getPriceLine().getName(), ev.getPriceLine().getPrice().doubleValue(), ev.getPriceLine().getColor());
            }
            case "trade" -> {
                if (ev.getTrade() == null || ev.getTrade().getSide() == null || ev.getTrade().getPrice() == null) {
                    return;
                }
                Double qty = ev.getTrade().getQty() != null ? ev.getTrade().getQty().doubleValue() : null;
                uiLayers.appendTrade(ev.getChatId(), ev.getStrategyType(), ev.getSymbol(), time,
                        ev.getTrade().getSide(), ev.getTrade().getPrice().doubleValue(), qty);
            }
            default -> {
                // ema_series / series_bundle пока только live WS, без persist в replay.
            }
        }
    }

    private boolean shouldDedup(String type) {
        return !"price".equals(type) && !"candle".equals(type);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private String buildKey(StrategyLiveEvent ev) {
        String sym = ev.getSymbol() != null ? ev.getSymbol() : "NA";
        return ev.getChatId() + "|" + ev.getStrategyType() + "|" + ev.getType() + "|" + sym;
    }

    private int safeHash(StrategyLiveEvent ev) {
        try { return ev.hashCode(); }
        catch (Exception e) { return System.identityHashCode(ev); }
    }
}

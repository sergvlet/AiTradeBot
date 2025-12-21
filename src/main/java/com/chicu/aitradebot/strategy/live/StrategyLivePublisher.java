package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyLivePublisher {

    private final StrategyLiveWsBridge bridge;

    // =====================================================
    // 🧩 HELPERS
    // =====================================================

    private static long now(Instant ts) {
        return ts != null ? ts.toEpochMilli() : StrategyLiveEvent.nowMillis();
    }

    private static String sanitizeSymbol(String symbol) {
        return symbol == null ? null : symbol.trim();
    }

    private static boolean baseOk(Long chatId, StrategyType strategyType, String symbol) {
        return chatId != null && strategyType != null && sanitizeSymbol(symbol) != null && !sanitizeSymbol(symbol).isBlank();
    }

    // =====================================================
    // 🕯 CANDLE
    // =====================================================
    public void pushCandleOhlc(Long chatId,
                               StrategyType strategyType,
                               String symbol,
                               String timeframe,
                               BigDecimal open,
                               BigDecimal high,
                               BigDecimal low,
                               BigDecimal close,
                               BigDecimal volume,
                               Instant ts) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("candle")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .time(now(ts))
                        .kline(
                                StrategyLiveEvent.CandlePayload.builder()
                                        .open(open)
                                        .high(high)
                                        .low(low)
                                        .close(close)
                                        .volume(volume)
                                        .timeframe(timeframe)
                                        .build()
                        )
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 💲 PRICE
    // =====================================================
    public void pushPriceTick(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              BigDecimal price,
                              Instant ts) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (price == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("price")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .price(price)
                        .time(now(ts))
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🟣 LEVELS
    // =====================================================
    public void pushLevels(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           List<BigDecimal> levels) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (levels == null || levels.isEmpty()) return;

        List<StrategyLiveEvent.LevelPayload> payload =
                levels.stream()
                        .filter(Objects::nonNull)
                        .map(p -> StrategyLiveEvent.LevelPayload.builder()
                                .price(p)
                                .build())
                        .toList();

        if (payload.isEmpty()) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("levels")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .levels(payload)
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка уровней (важно: раньше UI мог навсегда оставаться со старыми уровнями)
     */
    public void clearLevels(Long chatId,
                            StrategyType strategyType,
                            String symbol) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("levels")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .levels(List.of()) // 👈 явная очистка
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🟠 ZONE (общая зона)
    // =====================================================
    public void pushZone(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         BigDecimal top,
                         BigDecimal bottom) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (top == null || bottom == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .zone(
                                StrategyLiveEvent.ZonePayload.builder()
                                        .top(top.max(bottom))
                                        .bottom(top.min(bottom))
                                        .color("rgba(59,130,246,0.12)")
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка общей зоны
     */
    public void clearZone(Long chatId,
                          StrategyType strategyType,
                          String symbol) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .zone(null) // 👈 явная очистка
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🎯 ACTIVE LEVEL
    // =====================================================
    public void pushActiveLevel(Long chatId,
                                StrategyType strategyType,
                                String symbol,
                                BigDecimal level,
                                String role) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (level == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("active_level")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .activeLevel(
                                StrategyLiveEvent.ActiveLevelPayload.builder()
                                        .price(level)
                                        .role(role)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка активного уровня
     */
    public void clearActiveLevel(Long chatId,
                                 StrategyType strategyType,
                                 String symbol) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("active_level")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .activeLevel(null) // 👈 очистка
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🔴 BUY / SELL ZONE
    // =====================================================
    public void pushTradeZone(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String side,
                              BigDecimal top,
                              BigDecimal bottom) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (side == null || top == null || bottom == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("trade_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tradeZone(
                                StrategyLiveEvent.TradeZonePayload.builder()
                                        .side(side)
                                        .top(top)
                                        .bottom(bottom)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка trade-zone
     */
    public void clearTradeZone(Long chatId,
                               StrategyType strategyType,
                               String symbol) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("trade_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tradeZone(null)
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🧾 ORDER
    // =====================================================
    public void pushOrder(Long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String orderId,
                          String side,
                          BigDecimal price,
                          BigDecimal qty,
                          String status) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("order")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .order(
                                StrategyLiveEvent.OrderPayload.builder()
                                        .orderId(orderId)
                                        .side(side)
                                        .price(price)
                                        .qty(qty)
                                        .status(status)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🟢 TP / SL
    // =====================================================
    public void pushTpSl(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         BigDecimal tp,
                         BigDecimal sl) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        // ✅ ВАЖНО: tp/sl могут быть null, когда нужно очистить линии в UI
        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("tp_sl")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tpSl(
                                (tp == null && sl == null)
                                        ? null
                                        : StrategyLiveEvent.TpSlPayload.builder()
                                        .tp(tp)
                                        .sl(sl)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Явная очистка TP/SL
     */
    public void clearTpSl(Long chatId,
                          StrategyType strategyType,
                          String symbol) {
        pushTpSl(chatId, strategyType, symbol, null, null);
    }

    // =====================================================
    // 📊 METRIC
    // =====================================================
    public void pushMetric(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           double pnlPct) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("metric")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .metric(pnlPct)
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // ▶ STATE
    // =====================================================
    public void pushState(Long chatId,
                          StrategyType strategyType,
                          String symbol,
                          boolean running) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("state")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .state(running ? "running" : "stopped")
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🧲 MAGNET
    // =====================================================
    public void pushMagnet(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           BigDecimal target,
                           double strength) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (target == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("magnet")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .magnet(
                                StrategyLiveEvent.MagnetPayload.builder()
                                        .target(target)
                                        .strength(strength)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
// 🚦 SIGNAL (V4 — ИЗ StrategyEngine)
// =====================================================
    public void pushSignal(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           String timeframe,
                           com.chicu.aitradebot.strategy.core.signal.Signal signal) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (signal == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("signal")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .time(StrategyLiveEvent.nowMillis())
                        .signal(
                                StrategyLiveEvent.SignalPayload.builder()
                                        .name(signal.getType().name())   // BUY / SELL / HOLD
                                        .reason(signal.getReason())       // 👈 КЛЮЧЕВО
                                        .confidence(signal.getConfidence())
                                        .timeframe(timeframe)
                                        .build()
                        )
                        .build();

        bridge.publish(event);
    }


    // =====================================================
    // 📈 TRADE
    // =====================================================
    public void pushTrade(Long chatId,
                          StrategyType strategyType,
                          String symbol,
                          String side,
                          BigDecimal price,
                          BigDecimal qty,
                          Instant ts) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;
        if (side == null || price == null || qty == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("trade")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .trade(
                                StrategyLiveEvent.TradePayload.builder()
                                        .side(side)
                                        .price(price)
                                        .qty(qty)
                                        .build()
                        )
                        .time(now(ts))
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 📍 PRICE LINE
    // =====================================================
    public void pushPriceLine(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String name,
                              BigDecimal price) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        // ✅ ВАЖНО: price может быть null, когда нужно очистить линию в UI
        StrategyLiveEvent.PriceLinePayload payload =
                (price == null)
                        ? null
                        : StrategyLiveEvent.PriceLinePayload.builder()
                        .name(name)
                        .price(price)
                        .build();

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("price_line")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .priceLine(payload)
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка всех price-line в UI (JS должен на это реагировать: payload=null)
     */
    public void clearPriceLines(Long chatId,
                                StrategyType strategyType,
                                String symbol) {
        pushPriceLine(chatId, strategyType, symbol, null, null);
    }

    // =====================================================
    // 🔲 WINDOW ZONE
    // =====================================================
    public void pushWindowZone(Long chatId,
                               StrategyType strategyType,
                               String symbol,
                               BigDecimal high,
                               BigDecimal low) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        // ✅ ВАЖНО: high/low могут быть null, когда нужно очистить зону
        StrategyLiveEvent.WindowZonePayload payload =
                (high == null || low == null)
                        ? null
                        : StrategyLiveEvent.WindowZonePayload.builder()
                        .high(high)
                        .low(low)
                        .build();

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("window_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .windowZone(payload)
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    /**
     * ✅ Очистка window-zone
     */
    public void clearWindowZone(Long chatId,
                                StrategyType strategyType,
                                String symbol) {
        pushWindowZone(chatId, strategyType, symbol, null, null);
    }

    // =====================================================
    // 📊 ATR (если payload есть в StrategyLiveEvent)
    // =====================================================
    public void pushAtr(Long chatId,
                        StrategyType strategyType,
                        String symbol,
                        double atr,
                        double volatilityPct) {

        symbol = sanitizeSymbol(symbol);
        if (!baseOk(chatId, strategyType, symbol)) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("atr")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .atr(
                                StrategyLiveEvent.AtrPayload.builder()
                                        .atr(atr)
                                        .volatilityPct(volatilityPct)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }
}

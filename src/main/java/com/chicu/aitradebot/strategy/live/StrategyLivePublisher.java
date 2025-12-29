package com.chicu.aitradebot.strategy.live;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.signal.Signal;
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

    /**
     * epoch millis — UI V4 / LightweightCharts
     */
    private static long nowMs(Instant ts) {
        return ts != null ? ts.toEpochMilli() : System.currentTimeMillis();
    }


    /**
     * null если пусто
     */
    private static String sanitizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * true = можно публиковать
     */
    private static boolean baseOk(Long chatId, StrategyType type, String symbol) {
        return symbol != null && type != null;
    }


    private static String sanitizeTf(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase();
        return s.isBlank() ? null : s;
    }

    /**
     * ЕДИНАЯ защита + лог
     */
    private boolean guard(Long chatId, StrategyType type, String symbol, String event) {

        if (symbol == null || type == null) {
            log.warn("⚠️ LIVE SKIP [{}] chatId={}, type={}, symbol={}",
                    event, chatId, type, symbol);
            return false;
        }

        // chatId обязателен ТОЛЬКО для стратегии
        if (chatId == null && !event.equals("candle") && !event.equals("price")) {
            log.warn("⚠️ LIVE SKIP [{}] missing chatId", event);
            return false;
        }

        return true;
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
        timeframe = sanitizeTf(timeframe);
        if (!guard(chatId, strategyType, symbol, "candle")) return;

        if (open == null || high == null || low == null || close == null) {
            log.warn("❌ CANDLE invalid OHLC chatId={} symbol={}", chatId, symbol);
            return;
        }

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("candle")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .timeframe(timeframe) // ✅ ОБЯЗАТЕЛЬНО
                        .time(nowMs(ts))
                        .kline(
                                StrategyLiveEvent.CandlePayload.builder()
                                        .open(open)
                                        .high(high)
                                        .low(low)
                                        .close(close)
                                        .volume(volume)
                                        .build()
                        )
                        .build()
        );
    }


    // =====================================================
// 💲 PRICE
// =====================================================
    public void pushPriceTick(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String timeframe,   // ✅ ДОБАВЛЕНО
                              BigDecimal price,
                              Instant ts) {

        symbol = sanitizeSymbol(symbol);
        timeframe = sanitizeTf(timeframe);
        if (!guard(chatId, strategyType, symbol, "price")) return;
        if (price == null) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("price")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .timeframe(timeframe) // ✅ ТЕПЕРЬ СУЩЕСТВУЕТ
                        .price(price)
                        .time(nowMs(ts))
                        .build()
        );
    }

    // =====================================================
// 💲 PRICE (BACKWARD COMPATIBILITY)
// =====================================================
    public void pushPriceTick(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              BigDecimal price,
                              Instant ts) {

        // ⬅️ делегируем в новый метод без таймфрейма
        pushPriceTick(chatId, strategyType, symbol, null, price, ts);
    }



    // =====================================================
    // 🟣 LEVELS
    // =====================================================
    public void pushLevels(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           List<BigDecimal> levels) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "levels")) return;
        if (levels == null || levels.isEmpty()) return;

        List<StrategyLiveEvent.LevelPayload> payload =
                levels.stream()
                        .filter(Objects::nonNull)
                        .map(p -> StrategyLiveEvent.LevelPayload.builder().price(p).build())
                        .toList();

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("levels")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .levels(payload)
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearLevels(Long chatId, StrategyType strategyType, String symbol) {
        pushLevels(chatId, strategyType, symbol, List.of());
    }

    // =====================================================
    // 🟠 ZONE
    // =====================================================
    public void pushZone(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         BigDecimal top,
                         BigDecimal bottom) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "zone")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .zone(
                                top == null || bottom == null ? null :
                                        StrategyLiveEvent.ZonePayload.builder()
                                                .top(top.max(bottom))
                                                .bottom(top.min(bottom))
                                                .color("rgba(59,130,246,0.12)")
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearZone(Long chatId, StrategyType strategyType, String symbol) {
        pushZone(chatId, strategyType, symbol, null, null);
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
        if (!guard(chatId, strategyType, symbol, "active_level")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("active_level")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .activeLevel(
                                level == null ? null :
                                        StrategyLiveEvent.ActiveLevelPayload.builder()
                                                .price(level)
                                                .role(role)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearActiveLevel(Long chatId, StrategyType strategyType, String symbol) {
        pushActiveLevel(chatId, strategyType, symbol, null, null);
    }

    // =====================================================
    // 🔴 TRADE ZONE
    // =====================================================
    public void pushTradeZone(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String side,
                              BigDecimal top,
                              BigDecimal bottom) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "trade_zone")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("trade_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tradeZone(
                                side == null ? null :
                                        StrategyLiveEvent.TradeZonePayload.builder()
                                                .side(side)
                                                .top(top)
                                                .bottom(bottom)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearTradeZone(Long chatId, StrategyType strategyType, String symbol) {
        pushTradeZone(chatId, strategyType, symbol, null, null, null);
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
        if (!guard(chatId, strategyType, symbol, "order")) return;

        bridge.publish(
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
                        .time(nowMs(
                                null))
                        .build()
        );
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
        if (!guard(chatId, strategyType, symbol, "tp_sl")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("tp_sl")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tpSl(
                                tp == null && sl == null ? null :
                                        StrategyLiveEvent.TpSlPayload.builder()
                                                .tp(tp)
                                                .sl(sl)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearTpSl(Long chatId, StrategyType strategyType, String symbol) {
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
        if (!guard(chatId, strategyType, symbol, "metric")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("metric")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .metric(pnlPct)
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    // =====================================================
    // ▶ STATE
    // =====================================================
    public void pushState(Long chatId,
                          StrategyType strategyType,
                          String symbol,
                          boolean running) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "state")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("state")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .state(running ? "running" : "stopped")
                        .time(nowMs(
                                null))
                        .build()
        );
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
        if (!guard(chatId, strategyType, symbol, "magnet")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("magnet")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .magnet(
                                target == null ? null :
                                        StrategyLiveEvent.MagnetPayload.builder()
                                                .target(target)
                                                .strength(strength)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    // =====================================================
    // 🚦 SIGNAL
    // =====================================================
    public void pushSignal(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           String timeframe,
                           Signal signal) {

        symbol = sanitizeSymbol(symbol);
        timeframe = sanitizeTf(timeframe);
        if (!guard(chatId, strategyType, symbol, "signal")) return;
        if (signal == null) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("signal")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .signal(
                                StrategyLiveEvent.SignalPayload.builder()
                                        .name(signal.getType().name())
                                        .reason(signal.getReason())
                                        .confidence(signal.getConfidence())
                                        .timeframe(timeframe)
                                        .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
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
        if (!guard(chatId, strategyType, symbol, "trade")) return;

        bridge.publish(
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
                        .time(nowMs(
                                ts))
                        .build()
        );
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
        if (!guard(chatId, strategyType, symbol, "price_line")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("price_line")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .priceLine(
                                price == null ? null :
                                        StrategyLiveEvent.PriceLinePayload.builder()
                                                .name(name)
                                                .price(price)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearPriceLines(Long chatId, StrategyType strategyType, String symbol) {
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
        if (!guard(chatId, strategyType, symbol, "window_zone")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("window_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .windowZone(
                                high == null || low == null ? null :
                                        StrategyLiveEvent.WindowZonePayload.builder()
                                                .high(high)
                                                .low(low)
                                                .build()
                        )
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    public void clearWindowZone(Long chatId, StrategyType strategyType, String symbol) {
        pushWindowZone(chatId, strategyType, symbol, null, null);
    }

    // =====================================================
    // 📊 ATR
    // =====================================================
    public void pushAtr(Long chatId,
                        StrategyType strategyType,
                        String symbol,
                        double atr,
                        double volatilityPct) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "atr")) return;

        bridge.publish(
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
                        .time(nowMs(
                                null))
                        .build()
        );
    }

    // =====================================================
    // ⏸ COOLDOWN
    // =====================================================
    public void pushCooldown(Long chatId,
                             StrategyType strategyType,
                             String symbol,
                             long secondsLeft) {

        symbol = sanitizeSymbol(symbol);
        if (!guard(chatId, strategyType, symbol, "cooldown")) return;

        bridge.publish(
                StrategyLiveEvent.builder()
                        .type("cooldown")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .metric(secondsLeft > 0 ? (double) secondsLeft : null)
                        .time(nowMs(
                                null))
                        .build()
        );
    }
}

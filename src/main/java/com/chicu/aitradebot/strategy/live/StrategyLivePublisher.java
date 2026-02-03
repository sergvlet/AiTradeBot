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

    private static long nowMs(Instant ts) {
        return ts != null ? ts.toEpochMilli() : System.currentTimeMillis();
    }

    private static String sanitizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(); // ✅ КРИТИЧНО: единый формат символа
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeTf(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase();
        return s.isBlank() ? null : s;
    }

    /**
     * 🔒 ЕДИНАЯ защита
     */
    private boolean guard(Long chatId, StrategyType type, String symbol, String event) {

        if (symbol == null || type == null) {
            log.warn("⚠️ LIVE SKIP [{}] chatId={}, type={}, symbol={}",
                    event, chatId, type, symbol);
            return false;
        }

        // 🔥 chatId ОБЯЗАТЕЛЕН для ВСЕХ событий
        if (chatId == null) {
            log.warn("⚠️ LIVE SKIP [{}] missing chatId", event);
            return false;
        }

        return true;
    }

    /**
     * ✅ ЕДИНАЯ публикация с нормализацией (чтобы символ/таймфрейм/время всегда были валидны)
     */
    private void publish(StrategyLiveEvent ev) {
        if (ev == null) return;
        ev.normalize();               // ✅ ВОТ ЭТОГО ТЕБЕ НЕ ХВАТАЛО
        bridge.publish(ev);
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
            log.warn("❌ LIVE CANDLE invalid OHLC chatId={} symbol={}", chatId, symbol);
            return;
        }

        long timeMs = nowMs(ts);

        StrategyLiveEvent ev =
                StrategyLiveEvent.builder()
                        .type("candle")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .time(timeMs)
                        .kline(
                                StrategyLiveEvent.CandlePayload.builder()
                                        .open(open)
                                        .high(high)
                                        .low(low)
                                        .close(close)
                                        .volume(volume)
                                        .timeframe(timeframe) // ✅ полезно для UI/диагностики
                                        .build()
                        )
                        .build();

        publish(ev);
    }

    // =====================================================
    // 💲 PRICE
    // =====================================================

    public void pushPriceTick(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              Instant ts) {

        symbol = sanitizeSymbol(symbol);
        timeframe = sanitizeTf(timeframe);
        if (!guard(chatId, strategyType, symbol, "price")) return;
        if (price == null) return;

        log.debug("📤 LIVE PUBLISH PRICE chatId={} {} {} tf={} price={}",
                chatId, strategyType, symbol, timeframe, price);

        publish(
                StrategyLiveEvent.builder()
                        .type("price")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .timeframe(timeframe)
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

        publish(
                StrategyLiveEvent.builder()
                        .type("levels")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .levels(payload)
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
                StrategyLiveEvent.builder()
                        .type("metric")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .metric(pnlPct)
                        .time(nowMs(null))
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

        publish(
                StrategyLiveEvent.builder()
                        .type("state")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .state(running ? "running" : "stopped")
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(ts))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
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
                        .time(nowMs(null))
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

        publish(
                StrategyLiveEvent.builder()
                        .type("cooldown")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .metric(secondsLeft > 0 ? (double) secondsLeft : null)
                        .time(nowMs(null))
                        .build()
        );
    }
    private void logCandleClosed(Long chatId,
                                 StrategyType strategyType,
                                 String symbol,
                                 String timeframe,
                                 long time,
                                 BigDecimal open,
                                 BigDecimal high,
                                 BigDecimal low,
                                 BigDecimal close,
                                 BigDecimal volume) {

        log.info(
                "🕯 CANDLE CLOSED [{}] {} {} tf={} O={} H={} L={} C={} V={}",
                chatId,
                strategyType,
                symbol,
                timeframe,
                open, high, low, close, volume
        );
    }

    // =====================================================
    // ✅ ADAPTERS for MarketStreamServiceImpl (compile fix)
    // =====================================================

    /**
     * MarketStreamServiceImpl ожидает publishAggTick(...).
     * В текущем UI достаточно price tick (qty можно не публиковать отдельным событием).
     */
    public void publishAggTick(long chatId,
                               StrategyType strategyType,
                               String symbol,
                               String timeframe,
                               BigDecimal price,
                               BigDecimal qty,
                               long tradeTsMs) {
        // qty пока не используем — при желании можно добавить отдельный "trade" event
        pushPriceTick(chatId, strategyType, symbol, timeframe, price,
                tradeTsMs > 0 ? Instant.ofEpochMilli(tradeTsMs) : null);
    }

    /**
     * MarketStreamServiceImpl ожидает publishCandle(chatId, type, UnifiedKline).
     * UnifiedKline у тебя, судя по ошибкам, без record-аксессоров, поэтому читаем через reflection.
     */
    public void publishCandle(long chatId, StrategyType strategyType, com.chicu.aitradebot.market.model.UnifiedKline kline) {
        if (kline == null) return;

        String symbol = asString(readAny(kline, "symbol", "getSymbol"));
        String tf     = asString(readAny(kline, "timeframe", "getTimeframe", "interval", "getInterval"));

        BigDecimal open   = asBigDecimal(readAny(kline, "open", "getOpen"));
        BigDecimal high   = asBigDecimal(readAny(kline, "high", "getHigh"));
        BigDecimal low    = asBigDecimal(readAny(kline, "low", "getLow"));
        BigDecimal close  = asBigDecimal(readAny(kline, "close", "getClose"));
        BigDecimal volume = asBigDecimal(readAny(kline, "volume", "getVolume"));

        Long timeMs = asLong(readAny(kline,
                "openTime", "getOpenTime",
                "startTime", "getStartTime",
                "time", "getTime",
                "closeTime", "getCloseTime"
        ));

        pushCandleOhlc(
                chatId,
                strategyType,
                symbol,
                tf,
                open,
                high,
                low,
                close,
                volume,
                timeMs != null && timeMs > 0 ? Instant.ofEpochMilli(timeMs) : null
        );
    }

    // =====================================================
    // 🧩 Reflection helpers (compile-safe for any UnifiedKline shape)
    // =====================================================

    private static Object readAny(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;

        Class<?> c = target.getClass();
        for (String name : methodNames) {
            if (name == null || name.isBlank()) continue;
            try {
                // 1) method with no args
                var m = c.getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignore) {
                // 2) try boolean getter style for fields, e.g. isClosed()
                if (!name.startsWith("get") && !name.startsWith("is")) {
                    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    try {
                        var m2 = c.getMethod("get" + cap);
                        return m2.invoke(target);
                    } catch (Exception ignore2) {
                        try {
                            var m3 = c.getMethod("is" + cap);
                            return m3.invoke(target);
                        } catch (Exception ignore3) {
                            // continue
                        }
                    }
                }
            } catch (Exception e) {
                // если метод есть, но падает — просто пропускаем
            }
        }
        return null;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof java.time.Instant it) return it.toEpochMilli();
        if (v instanceof java.util.Date d) return d.getTime();
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }



}

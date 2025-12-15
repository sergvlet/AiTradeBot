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

        if (chatId == null || strategyType == null || symbol == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("candle")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .time(ts != null ? ts.toEpochMilli() : StrategyLiveEvent.nowMillis())
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

        if (chatId == null || strategyType == null || symbol == null || price == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("price")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .price(price)
                        .time(ts != null ? ts.toEpochMilli() : StrategyLiveEvent.nowMillis())
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

        if (chatId == null || strategyType == null || symbol == null) return;
        if (levels == null || levels.isEmpty()) return;

        List<StrategyLiveEvent.LevelPayload> payload =
                levels.stream()
                        .filter(Objects::nonNull)
                        .map(p -> StrategyLiveEvent.LevelPayload.builder()
                                .price(p)
                                .build())
                        .toList();

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

    // =====================================================
    // 🟠 ZONE (общая зона)
    // =====================================================
    public void pushZone(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         BigDecimal top,
                         BigDecimal bottom) {

        if (chatId == null || strategyType == null || symbol == null) return;
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

    // =====================================================
    // 🎯 ACTIVE LEVEL
    // =====================================================
    public void pushActiveLevel(Long chatId,
                                StrategyType strategyType,
                                String symbol,
                                BigDecimal level,
                                String role) {

        if (chatId == null || strategyType == null || symbol == null || level == null) return;

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

    // =====================================================
    // 🔴 BUY / SELL ZONE
    // =====================================================
    public void pushTradeZone(Long chatId,
                              StrategyType strategyType,
                              String symbol,
                              String side,
                              BigDecimal top,
                              BigDecimal bottom) {

        if (chatId == null || strategyType == null || symbol == null) return;
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

        if (chatId == null || strategyType == null || symbol == null) return;

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

        if (chatId == null || strategyType == null || symbol == null) return;
        if (tp == null && sl == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("tp_sl")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .tpSl(
                                StrategyLiveEvent.TpSlPayload.builder()
                                        .tp(tp)
                                        .sl(sl)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 📊 METRIC
    // =====================================================
    public void pushMetric(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           double pnlPct) {

        if (chatId == null || strategyType == null || symbol == null) return;

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

        if (chatId == null || strategyType == null || symbol == null) return;

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

        if (chatId == null || strategyType == null || symbol == null || target == null) return;

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
    // 🚦 SIGNAL
    // =====================================================
    public void pushSignal(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           String name,
                           double confidence) {

        if (chatId == null || strategyType == null || symbol == null || name == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("signal")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .signal(
                                StrategyLiveEvent.SignalPayload.builder()
                                        .name(name)
                                        .value(confidence)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
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

        if (chatId == null || strategyType == null || symbol == null) return;
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
                        .time(ts != null ? ts.toEpochMilli() : StrategyLiveEvent.nowMillis())
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

        if (chatId == null || strategyType == null || symbol == null || price == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("price_line")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .priceLine(
                                StrategyLiveEvent.PriceLinePayload.builder()
                                        .name(name)
                                        .price(price)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 🔲 WINDOW ZONE
    // =====================================================
    public void pushWindowZone(Long chatId,
                               StrategyType strategyType,
                               String symbol,
                               BigDecimal high,
                               BigDecimal low) {

        if (chatId == null || strategyType == null || symbol == null) return;
        if (high == null || low == null) return;

        StrategyLiveEvent event =
                StrategyLiveEvent.builder()
                        .type("window_zone")
                        .chatId(chatId)
                        .strategyType(strategyType)
                        .symbol(symbol)
                        .windowZone(
                                StrategyLiveEvent.WindowZonePayload.builder()
                                        .high(high)
                                        .low(low)
                                        .build()
                        )
                        .time(StrategyLiveEvent.nowMillis())
                        .build();

        bridge.publish(event);
    }

    // =====================================================
    // 📊 ATR (если payload есть в StrategyLiveEvent)
    // =====================================================
    public void pushAtr(Long chatId,
                        StrategyType strategyType,
                        String symbol,
                        double atr,
                        double volatilityPct) {

        if (chatId == null || strategyType == null || symbol == null) return;

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

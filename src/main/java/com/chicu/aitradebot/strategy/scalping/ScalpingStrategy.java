package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.LiveCandleAggregator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@StrategyBinding(StrategyType.SCALPING)
@Component
@RequiredArgsConstructor
public class ScalpingStrategy implements TradingStrategy {

    private final ScalpingStrategySettingsService settingsService;
    private final OrderService orderService;
    private final StrategyLivePublisher live;
    private final LiveCandleAggregator candleAggregator;

    // ============================================================
    // STATE
    // ============================================================
    private static class State {
        Instant startedAt;
        String symbol;
        boolean active = false;

        Deque<BigDecimal> window = new ArrayDeque<>();

        BigDecimal entryPrice;
        OrderSide entrySide;
        BigDecimal entryQty;

        // ✅ анти-спам WS (дедупликация)
        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;

        BigDecimal lastSentEntry;
        BigDecimal lastSentTp;
        BigDecimal lastSentSl;
    }

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    // ============================================================
    // START / STOP / INFO
    // ============================================================
    @Override
    public synchronized void start(Long chatId, String symbol) {
        ScalpingStrategySettings cfg = settingsService.getOrCreate(chatId);

        State st = new State();
        st.active = true;
        st.startedAt = Instant.now();

        st.symbol = (symbol != null && !symbol.isBlank())
                ? symbol.toUpperCase()
                : (cfg.getSymbol() != null ? cfg.getSymbol() : "BTCUSDT");

        states.put(chatId, st);

        cfg.setActive(true);
        cfg.setSymbol(st.symbol);
        settingsService.save(cfg);

        live.pushState(chatId, StrategyType.SCALPING, st.symbol, true);
        log.info("⚡ SCALPING START chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public synchronized void stop(Long chatId, String ignore) {
        State st = states.remove(chatId);
        if (st == null) return;

        ScalpingStrategySettings cfg = settingsService.getOrCreate(chatId);
        cfg.setActive(false);
        settingsService.save(cfg);

        candleAggregator.flush(
                chatId,
                StrategyType.SCALPING,
                st.symbol,
                cfg.getTimeframe(),
                TimeframeUtils.toMillis(cfg.getTimeframe())
        );

        live.pushState(chatId, StrategyType.SCALPING, st.symbol, false);
        log.info("⛔ SCALPING STOP chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public boolean isActive(Long chatId) {
        State st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        State st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public String getThreadName(Long chatId) {
        State st = states.get(chatId);
        return st == null ? "scalping-" + chatId : "scalping-" + chatId + "-" + st.symbol;
    }

    // ============================================================
    // MAIN LOGIC
    // ============================================================
    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {

        State st = states.get(chatId);
        if (st == null || !st.active || price == null) return;

        ScalpingStrategySettings s = settingsService.getOrCreate(chatId);

        if (symbol != null && !symbol.isBlank() && (st.symbol == null || !symbol.equalsIgnoreCase(st.symbol))) {
            st.symbol = symbol.toUpperCase();
        }

        Instant time = ts != null ? ts : Instant.now();

        // 🔥 PRICE → UI
        live.pushPriceTick(chatId, StrategyType.SCALPING, st.symbol, price, time);

        // 🕯 CANDLES → UI
        long tfMillis = TimeframeUtils.toMillis(s.getTimeframe());
        candleAggregator.onPriceTick(
                chatId,
                StrategyType.SCALPING,
                st.symbol,
                s.getTimeframe(),
                tfMillis,
                price,
                time
        );

        // ================= WINDOW (скользящее) =================
        st.window.addLast(price);
        if (st.window.size() > s.getWindowSize()) st.window.removeFirst();
        if (st.window.size() < s.getWindowSize()) return;

        BigDecimal min = null, max = null;
        for (BigDecimal p : st.window) {
            if (min == null || p.compareTo(min) < 0) min = p;
            if (max == null || p.compareTo(max) > 0) max = p;
        }

        // 🔲 ШАГ 1: WINDOW ZONE (high/low окна) — слать ЗДЕСЬ
        if (min != null && max != null) {
            boolean changed = (st.lastWindowHigh == null || st.lastWindowLow == null
                               || max.compareTo(st.lastWindowHigh) != 0
                               || min.compareTo(st.lastWindowLow) != 0);

            if (changed) {
                // ✅ правильный контракт: window_zone
                live.pushWindowZone(chatId, StrategyType.SCALPING, st.symbol, max, min);
                st.lastWindowHigh = max;
                st.lastWindowLow = min;
            }
        }

        // ================= ШАГ 4: ATR / High-Low filter =================

// диапазон окна в %
        BigDecimal rangePct = (min != null && max != null && min.signum() > 0)
                ? max.subtract(min).divide(min, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

// ❗ ИСПРАВЛЕНИЕ: используем ТОЛЬКО priceChangeThreshold
        double minVolatility = s.getPriceChangeThreshold() / 100.0;

        if (rangePct.doubleValue() < minVolatility) {
            log.debug("⛔ Volatility filter blocked entry rangePct={} minVolatility={}",
                    rangePct, minVolatility);
        }


        // ================= ENTRY =================
        BigDecimal first = st.window.getFirst();
        BigDecimal last  = st.window.getLast();

        double diff = last.subtract(first)
                .divide(first, 6, RoundingMode.HALF_UP)
                .doubleValue();

        double threshold = s.getPriceChangeThreshold() / 100.0;

        if (st.entryPrice == null
            && Math.abs(diff) >= threshold
            && rangePct.doubleValue() >= minVolatility) {

            st.entrySide = diff > 0 ? OrderSide.BUY : OrderSide.SELL;
            st.entryQty  = BigDecimal.valueOf(s.getOrderVolume());

            Order o = orderService.placeMarket(
                    chatId, st.symbol, st.entrySide.name(),
                    st.entryQty, price, StrategyType.SCALPING.name()
            );

            st.entryPrice = (o != null && o.getPrice() != null) ? o.getPrice() : price;

            // 📍 ШАГ 2: ENTRY LINE — слать ЗДЕСЬ (после фиксации entryPrice)
            if (st.lastSentEntry == null || st.entryPrice.compareTo(st.lastSentEntry) != 0) {
                live.pushPriceLine(chatId, StrategyType.SCALPING, st.symbol, "ENTRY", st.entryPrice);
                st.lastSentEntry = st.entryPrice;
            }

            // 🎯 ШАГ 3: TP/SL (можно сразу один раз отправить при входе)
            pushTpSlLines(chatId, s, st);

            // сигналы/трейд — по желанию (у тебя уже есть)
            live.pushSignal(chatId, StrategyType.SCALPING, st.symbol,
                    st.entrySide == OrderSide.BUY ? "BUY" : "SELL", Math.abs(diff));

            live.pushTrade(chatId, StrategyType.SCALPING, st.symbol, st.entrySide.name(),
                    st.entryPrice, st.entryQty, time);

            log.info("🚀 SCALP ENTRY chatId={} side={} entry={}", chatId, st.entrySide, st.entryPrice);
            return;
        }

        // ================= TP / SL =================
        if (st.entryPrice != null && st.entrySide != null) {

            // 🎯 ШАГ 3: TP/SL линии — слать ЗДЕСЬ (можно обновлять, но с дедупликацией)
            pushTpSlLines(chatId, s, st);

            double tp = s.getTakeProfitPct() / 100.0;
            double sl = s.getStopLossPct() / 100.0;

            BigDecimal tpPrice = (st.entrySide == OrderSide.BUY)
                    ? st.entryPrice.multiply(BigDecimal.valueOf(1 + tp))
                    : st.entryPrice.multiply(BigDecimal.valueOf(1 - tp));

            BigDecimal slPrice = (st.entrySide == OrderSide.BUY)
                    ? st.entryPrice.multiply(BigDecimal.valueOf(1 - sl))
                    : st.entryPrice.multiply(BigDecimal.valueOf(1 + sl));

            boolean hitTp = (st.entrySide == OrderSide.BUY)
                    ? price.compareTo(tpPrice) >= 0
                    : price.compareTo(tpPrice) <= 0;

            boolean hitSl = (st.entrySide == OrderSide.BUY)
                    ? price.compareTo(slPrice) <= 0
                    : price.compareTo(slPrice) >= 0;

            if (hitTp || hitSl) {

                OrderSide exit = (st.entrySide == OrderSide.BUY) ? OrderSide.SELL : OrderSide.BUY;

                orderService.placeMarket(
                        chatId, st.symbol, exit.name(),
                        st.entryQty, price, StrategyType.SCALPING.name()
                );

                live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, hitTp ? "TP" : "SL", 0);

                // опционально: очистить линии (если UI поддерживает), либо просто entry/tp/sl перестанут обновляться
                st.entryPrice = null;
                st.entrySide = null;
                st.entryQty = null;
                st.lastSentEntry = null;
                st.lastSentTp = null;
                st.lastSentSl = null;

                log.info("🏁 SCALP EXIT chatId={} reason={}", chatId, hitTp ? "TP" : "SL");
            }
        }
    }

    private void pushTpSlLines(Long chatId, ScalpingStrategySettings s, State st) {
        if (st.entryPrice == null || st.entrySide == null) return;

        double tp = s.getTakeProfitPct() / 100.0;
        double sl = s.getStopLossPct() / 100.0;

        BigDecimal tpPrice = (st.entrySide == OrderSide.BUY)
                ? st.entryPrice.multiply(BigDecimal.valueOf(1 + tp))
                : st.entryPrice.multiply(BigDecimal.valueOf(1 - tp));

        BigDecimal slPrice = (st.entrySide == OrderSide.BUY)
                ? st.entryPrice.multiply(BigDecimal.valueOf(1 - sl))
                : st.entryPrice.multiply(BigDecimal.valueOf(1 + sl));

        boolean tpChanged = (st.lastSentTp == null || tpPrice.compareTo(st.lastSentTp) != 0);
        boolean slChanged = (st.lastSentSl == null || slPrice.compareTo(st.lastSentSl) != 0);

        if (tpChanged || slChanged) {
            // ✅ событие tp_sl (одно)
            live.pushTpSl(chatId, StrategyType.SCALPING, st.symbol, tpPrice, slPrice);

            // ✅ линии (чтобы UI рисовал)
            live.pushPriceLine(chatId, StrategyType.SCALPING, st.symbol, "TP", tpPrice);
            live.pushPriceLine(chatId, StrategyType.SCALPING, st.symbol, "SL", slPrice);

            st.lastSentTp = tpPrice;
            st.lastSentSl = slPrice;
        }
    }
}

package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
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
    // STATE (LIVE + CACHE)
    // ============================================================
    private static class State {
        Instant startedAt;
        String symbol;
        boolean active;

        ScalpingStrategySettings settings;

        Deque<BigDecimal> window = new ArrayDeque<>();

        BigDecimal entryPrice;
        OrderSide entrySide;
        BigDecimal entryQty;

        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;
    }

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    // ============================================================
    // START / STOP
    // ============================================================
    @Override
    public synchronized void start(Long chatId, String symbol) {

        ScalpingStrategySettings cfg = settingsService.getOrCreate(chatId);

        State st = new State();
        st.active = true;
        st.startedAt = Instant.now();
        st.settings = cfg;

        st.symbol = (symbol != null && !symbol.isBlank())
                ? symbol.toUpperCase()
                : cfg.getSymbol();

        // 🔥 полный сброс позиции
        st.entryPrice = null;
        st.entrySide = null;
        st.entryQty = null;
        st.window.clear();

        states.put(chatId, st);

        cfg.setActive(true);
        cfg.setSymbol(st.symbol);
        settingsService.save(cfg);

        live.pushState(chatId, StrategyType.SCALPING, st.symbol, true);
        live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, null, Signal.hold("started"));

        log.info("▶ SCALPING START chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public synchronized void stop(Long chatId, String ignore) {

        State st = states.remove(chatId);
        if (st == null) return;

        ScalpingStrategySettings cfg = st.settings;
        cfg.setActive(false);
        settingsService.save(cfg);

        live.clearTpSl(chatId, StrategyType.SCALPING, st.symbol);
        live.clearPriceLines(chatId, StrategyType.SCALPING, st.symbol);
        live.clearWindowZone(chatId, StrategyType.SCALPING, st.symbol);

        live.pushState(chatId, StrategyType.SCALPING, st.symbol, false);

        log.info("⏹ SCALPING STOP chatId={} symbol={}", chatId, st.symbol);
    }

    // ============================================================
    // INFO
    // ============================================================
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
        return st == null
                ? "SCALPING-" + chatId
                : "SCALPING-" + chatId + "-" + st.symbol;
    }

    // ============================================================
    // MAIN LOGIC — LIVE
    // ============================================================
    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {

        State st = states.get(chatId);
        if (st == null || !st.active || price == null) return;

        // защита от рассинхрона
        if (!st.symbol.equalsIgnoreCase(symbol)) return;

        ScalpingStrategySettings s = st.settings;
        Instant time = ts != null ? ts : Instant.now();

        // PRICE → UI
        live.pushPriceTick(chatId, StrategyType.SCALPING, st.symbol, price, time);

        // CANDLES
        String tf = s.getTimeframe();
        long tfMillis = TimeframeUtils.toMillis(tf);

        candleAggregator.onPriceTick(
                chatId,
                StrategyType.SCALPING,
                st.symbol,
                tf,
                tfMillis,
                price,
                time
        );

        // WINDOW
        st.window.addLast(price);
        while (st.window.size() > s.getWindowSize()) {
            st.window.removeFirst();
        }

        if (st.window.size() < s.getWindowSize()) {
            live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, null, Signal.hold("warming up"));
            return;
        }

        BigDecimal first = st.window.getFirst();
        if (first.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal min = st.window.stream().min(BigDecimal::compareTo).orElse(price);
        BigDecimal max = st.window.stream().max(BigDecimal::compareTo).orElse(price);

        if (!max.equals(st.lastWindowHigh) || !min.equals(st.lastWindowLow)) {
            live.pushWindowZone(chatId, StrategyType.SCALPING, st.symbol, max, min);
            st.lastWindowHigh = max;
            st.lastWindowLow = min;
        }

        BigDecimal last = st.window.getLast();

        double diff = last.subtract(first)
                .divide(first, 6, RoundingMode.HALF_UP)
                .doubleValue();

        double threshold = s.getPriceChangeThreshold() / 100.0;

        // ENTRY
        if (st.entryPrice == null && Math.abs(diff) >= threshold) {

            st.entrySide = diff > 0 ? OrderSide.BUY : OrderSide.SELL;
            st.entryQty = s.getOrderVolume();

            Order o = orderService.placeMarket(
                    chatId,
                    st.symbol,
                    st.entrySide.name(),
                    st.entryQty,
                    price,
                    StrategyType.SCALPING.name()
            );

            st.entryPrice = (o != null && o.getPrice() != null)
                    ? o.getPrice()
                    : price;

            live.pushPriceLine(chatId, StrategyType.SCALPING, st.symbol, "ENTRY", st.entryPrice);
            pushTpSlLines(chatId, s, st);

            live.pushSignal(
                    chatId,
                    StrategyType.SCALPING,
                    st.symbol,
                    null,
                    st.entrySide == OrderSide.BUY
                            ? Signal.buy(price.doubleValue(), "entry")
                            : Signal.sell(price.doubleValue(), "entry")
            );
            return;
        }

        live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, null, Signal.hold("no signal"));
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private void pushTpSlLines(Long chatId, ScalpingStrategySettings s, State st) {

        double tp = s.getTakeProfitPct() / 100.0;
        double sl = s.getStopLossPct() / 100.0;

        BigDecimal tpPrice = st.entrySide == OrderSide.BUY
                ? st.entryPrice.multiply(BigDecimal.valueOf(1 + tp))
                : st.entryPrice.multiply(BigDecimal.valueOf(1 - tp));

        BigDecimal slPrice = st.entrySide == OrderSide.BUY
                ? st.entryPrice.multiply(BigDecimal.valueOf(1 - sl))
                : st.entryPrice.multiply(BigDecimal.valueOf(1 + sl));

        tpPrice = tpPrice.setScale(8, RoundingMode.HALF_UP);
        slPrice = slPrice.setScale(8, RoundingMode.HALF_UP);

        live.pushTpSl(chatId, StrategyType.SCALPING, st.symbol, tpPrice, slPrice);
    }
}

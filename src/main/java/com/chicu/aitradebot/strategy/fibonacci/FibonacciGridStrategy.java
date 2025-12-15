package com.chicu.aitradebot.strategy.fibonacci;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.LiveCandleAggregator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.FIBONACCI_GRID)
public class FibonacciGridStrategy implements TradingStrategy {

    /** Цена считается «на уровне», если расстояние < 0.05% */
    private static final BigDecimal ACTIVE_DELTA_PCT = new BigDecimal("0.0005");

    private final FibonacciGridStrategySettingsService settingsService;
    private final CandleProvider candleProvider;
    private final OrderService orderService;

    private final StrategyLivePublisher live;
    private final LiveCandleAggregator candleAggregator;
    private final UiStrategyLayerService uiLayerService;

    // =====================================================
    // STATE
    // =====================================================
    private static class State {
        Instant startedAt;
        String symbol;
        boolean active;

        BigDecimal base;
        Instant lastSavedCandleTime;

        List<BigDecimal> levels = new ArrayList<>();

        /** 🔑 УРОВЕНЬ → ОРДЕР */
        Map<BigDecimal, Order> activeOrders = new ConcurrentHashMap<>();
    }

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    // =====================================================
    // GRID LEVELS
    // =====================================================
    private List<BigDecimal> computeLevels(BigDecimal base, FibonacciGridStrategySettings s) {
        if (base == null || base.signum() <= 0) return List.of();

        BigDecimal step = BigDecimal.valueOf(s.getDistancePct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        int half = Math.max(1, s.getGridLevels() / 2);

        List<BigDecimal> levels = new ArrayList<>();
        levels.add(base);

        for (int i = 1; i <= half; i++) {
            BigDecimal mul = step.multiply(BigDecimal.valueOf(i));
            levels.add(base.multiply(BigDecimal.ONE.subtract(mul)));
            levels.add(base.multiply(BigDecimal.ONE.add(mul)));
        }

        levels.sort(Comparator.naturalOrder());

        return levels.stream()
                .map(v -> v.setScale(8, RoundingMode.HALF_UP))
                .limit(s.getGridLevels())
                .toList();
    }

    // =====================================================
    // UTILS
    // =====================================================
    private String resolveSymbol(String arg, FibonacciGridStrategySettings s, State st) {
        if (arg != null && !arg.isBlank()) return arg;
        if (st != null && st.symbol != null) return st.symbol;
        return s.getSymbol();
    }

    private Instant resolveCandleTime(Instant time, long tfMillis) {
        long ms = (time != null ? time : Instant.now()).toEpochMilli();
        return Instant.ofEpochMilli((ms / tfMillis) * tfMillis);
    }

    private BigDecimal resolveBaseFromCandleClose(
            Long chatId,
            String symbol,
            FibonacciGridStrategySettings s,
            BigDecimal fallback
    ) {
        return candleProvider
                .getRecentCandles(chatId, symbol, s.getTimeframe(), 1)
                .stream()
                .reduce((a, b) -> b)
                .map(c -> BigDecimal.valueOf(c.getClose()))
                .orElse(fallback);
    }

    // =====================================================
    // START
    // =====================================================
    @Override
    public synchronized void start(Long chatId, String symbol) {

        FibonacciGridStrategySettings s = settingsService.getOrCreate(chatId);
        String sym = resolveSymbol(symbol, s, null).toUpperCase(Locale.ROOT);

        State st = new State();
        st.active = true;
        st.symbol = sym;
        st.startedAt = Instant.now();
        states.put(chatId, st);

        // сохраняем настройки
        s.setSymbol(sym);
        s.setActive(true);
        settingsService.save(s);

        // =====================================================
        // 🔑 BASE PRICE (CANDLE → LIVE → LAZY INIT)
        // =====================================================
        BigDecimal base = resolveBaseFromCandleClose(chatId, sym, s, null);

        if (base != null && base.signum() > 0) {
            // ✅ есть база — сразу строим сетку
            initGrid(chatId, st, s, base);
            log.info("🟣 FIB base from candle: {}", base);
        } else {
            // ⚠️ базы нет — инициализация будет при первом price tick
            log.warn("⚠️ FIB start without base price, waiting for live tick");
        }

        live.pushState(chatId, StrategyType.FIBONACCI_GRID, sym, true);

        log.info("▶️ FIB START chatId={} symbol={}", chatId, sym);
    }

    // =====================================================
    // STOP
    // =====================================================
    @Override
    public synchronized void stop(Long chatId, String ignored) {

        State st = states.remove(chatId);
        if (st == null) return;

        FibonacciGridStrategySettings s = settingsService.getOrCreate(chatId);
        s.setActive(false);
        settingsService.save(s);

        live.pushState(chatId, StrategyType.FIBONACCI_GRID, st.symbol, false);

        uiLayerService.clearStrategy(chatId, StrategyType.FIBONACCI_GRID, st.symbol);

        log.info("⏹ FIB STOP chatId={} symbol={}", chatId, st.symbol);
    }

    // =====================================================
    // INFO
    // =====================================================
    @Override
    public boolean isActive(Long chatId) {
        return states.containsKey(chatId) && states.get(chatId).active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        State st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public String getThreadName(Long chatId) {
        return "fib-" + chatId;
    }

    // =====================================================
    // PRICE UPDATE — 🔥 ОСНОВНАЯ ЛОГИКА
    // =====================================================
    @Override
    public void onPriceUpdate(Long chatId,
                              String symbol,
                              BigDecimal price,
                              Instant ts) {

        State st = states.get(chatId);
        if (st == null || !st.active || price == null) return;

        FibonacciGridStrategySettings s = settingsService.getOrCreate(chatId);

        String sym = resolveSymbol(symbol, s, st).toUpperCase(Locale.ROOT);
        st.symbol = sym;

        Instant tickTime = ts != null ? ts : Instant.now();

        // =====================================================
        // 📈 PRICE TICK → UI
        // =====================================================
        live.pushPriceTick(
                chatId,
                StrategyType.FIBONACCI_GRID,
                sym,
                price,
                tickTime
        );

        // =====================================================
        // 🟣 LAZY INIT GRID (если старт был без базы)
        // =====================================================
        if (st.base == null || st.levels == null || st.levels.isEmpty()) {

            st.base = price;
            st.levels = computeLevels(price, s);

            long tfMillis = TimeframeUtils.toMillis(s.getTimeframe());
            Instant candleTime = resolveCandleTime(tickTime, tfMillis);

            st.lastSavedCandleTime = candleTime;

            saveLayers(chatId, sym, candleTime, st.levels);

            log.info("🟣 FIB grid initialized from live price {}", price);
        }

        // =====================================================
        // 🔍 ПОИСК БЛИЖАЙШЕГО УРОВНЯ
        // =====================================================
        BigDecimal nearest = null;
        BigDecimal minDelta = null;

        for (BigDecimal lvl : st.levels) {
            if (lvl == null || lvl.signum() <= 0) continue;

            BigDecimal delta = price.subtract(lvl).abs()
                    .divide(lvl, 8, RoundingMode.HALF_UP);

            if (minDelta == null || delta.compareTo(minDelta) < 0) {
                minDelta = delta;
                nearest = lvl;
            }
        }

        if (nearest == null || minDelta == null) return;

        String role = price.compareTo(nearest) >= 0
                ? "SUPPORT"
                : "RESISTANCE";

        // =====================================================
        // 🎯 ACTIVE LEVEL + MAGNET
        // =====================================================
        live.pushActiveLevel(
                chatId,
                StrategyType.FIBONACCI_GRID,
                sym,
                nearest,
                role
        );

        double magnetStrength =
                Math.max(0.0, 1.0 - minDelta.doubleValue());

        live.pushMagnet(
                chatId,
                StrategyType.FIBONACCI_GRID,
                sym,
                nearest,
                magnetStrength
        );

        // =====================================================
        // 🚀 ВХОД В СДЕЛКУ (ОДИН РАЗ НА УРОВЕНЬ)
        // =====================================================
        if (minDelta.compareTo(ACTIVE_DELTA_PCT) < 0
            && !st.activeOrders.containsKey(nearest)) {

            String side = role.equals("SUPPORT") ? "BUY" : "SELL";

            Order order = orderService.placeLimit(
                    chatId,
                    sym,
                    side,
                    BigDecimal.valueOf(s.getBaseOrderVolume()),
                    nearest,
                    "GTC",
                    StrategyType.FIBONACCI_GRID.name()
            );

            if (order == null) return;

            st.activeOrders.put(nearest, order);

            // =================================================
            // 📦 ORDER → UI
            // =================================================
            live.pushOrder(
                    chatId,
                    StrategyType.FIBONACCI_GRID,
                    sym,
                    String.valueOf(order.getId()),
                    side,
                    nearest,
                    order.getQuantity(),
                    order.getStatus()
            );

            // =================================================
            // 🟢 TP / SL
            // =================================================
            BigDecimal tp = side.equals("BUY")
                    ? nearest.multiply(
                    BigDecimal.ONE.add(
                            BigDecimal.valueOf(s.getTakeProfitPct() / 100.0)))
                    : nearest.multiply(
                    BigDecimal.ONE.subtract(
                            BigDecimal.valueOf(s.getTakeProfitPct() / 100.0)));

            BigDecimal sl = side.equals("BUY")
                    ? nearest.multiply(
                    BigDecimal.ONE.subtract(
                            BigDecimal.valueOf(s.getStopLossPct() / 100.0)))
                    : nearest.multiply(
                    BigDecimal.ONE.add(
                            BigDecimal.valueOf(s.getStopLossPct() / 100.0)));

            live.pushTpSl(chatId, StrategyType.FIBONACCI_GRID, sym, tp, sl);

            live.pushTradeZone(
                    chatId,
                    StrategyType.FIBONACCI_GRID,
                    sym,
                    side,
                    tp.max(sl),
                    tp.min(sl)
            );

            log.info("🚀 FIB ENTRY chatId={} {} @ {}", chatId, side, nearest);
        }
    }


    // =====================================================
    // 🔁 REPLAY
    // =====================================================
    @Override
    public void replayLayers(Long chatId) {

        FibonacciGridStrategySettings s = settingsService.getOrCreate(chatId);
        State st = states.computeIfAbsent(chatId, k -> new State());

        String sym = resolveSymbol(null, s, st).toUpperCase(Locale.ROOT);
        st.symbol = sym;

        BigDecimal base = resolveBaseFromCandleClose(chatId, sym, s, BigDecimal.ZERO);
        if (base.signum() <= 0) return;

        st.base = base;
        st.levels = computeLevels(base, s);

        long tfMillis = TimeframeUtils.toMillis(s.getTimeframe());
        Instant candleTime = resolveCandleTime(Instant.now(), tfMillis);

        st.lastSavedCandleTime = candleTime;

        saveLayers(chatId, sym, candleTime, st.levels);

        log.info("🔁 FIB REPLAY chatId={} symbol={} levels={}", chatId, sym, st.levels.size());
    }

    // =====================================================
    // SAVE UI LAYERS + WS
    // =====================================================
    private void saveLayers(Long chatId,
                            String symbol,
                            Instant candleTime,
                            List<BigDecimal> levels) {

        uiLayerService.saveLevels(
                chatId,
                StrategyType.FIBONACCI_GRID,
                symbol,
                candleTime,
                levels
        );

        BigDecimal min = levels.get(0);
        BigDecimal max = levels.get(levels.size() - 1);

        uiLayerService.saveZone(
                chatId,
                StrategyType.FIBONACCI_GRID,
                symbol,
                candleTime,
                max.doubleValue(),
                min.doubleValue(),
                "rgba(59,130,246,0.12)"
        );

        live.pushLevels(chatId, StrategyType.FIBONACCI_GRID, symbol, levels);
        live.pushZone(chatId, StrategyType.FIBONACCI_GRID, symbol, max, min);
    }
    private void initGrid(Long chatId,
                          State st,
                          FibonacciGridStrategySettings s,
                          BigDecimal base) {

        st.base = base;
        st.levels = computeLevels(base, s);

        long tfMillis = TimeframeUtils.toMillis(s.getTimeframe());
        Instant candleTime = resolveCandleTime(Instant.now(), tfMillis);

        st.lastSavedCandleTime = candleTime;

        saveLayers(chatId, st.symbol, candleTime, st.levels);
    }

}

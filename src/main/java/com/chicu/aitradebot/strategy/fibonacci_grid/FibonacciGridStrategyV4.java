package com.chicu.aitradebot.strategy.fibonacci_grid;

import com.chicu.aitradebot.ai.runtime.AdaptiveRuntimeController;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.TradeExecutionService;
import com.chicu.aitradebot.trade.TradeExecutionServiceImpl;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.ui.UiStrategyLayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.FIBONACCI_GRID)
public class FibonacciGridStrategyV4 implements TradingStrategy, AiStrategyOrchestrator.PriceUpdateAware, AiStrategyOrchestrator.CandleCloseAware {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final Duration HOLD_SIGNAL_COOLDOWN = Duration.ofSeconds(2);
    private static final long LOG_EVERY_TICKS = 300L;

    private static final int STARVATION_REARM_CANDLES = 12;
    private static final Duration GRID_REARM_COOLDOWN = Duration.ofMinutes(2);

    private static final int DEFAULT_LEVELS = 6;
    private static final BigDecimal DEFAULT_STEP_PCT = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("0.80");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("1.20");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int PRICE_SCALE = 18;

    private static final String BASE_LINE = "BASE";
    private static final String NEXT_BUY_LINE = "NEXT_BUY";
    private static final String ENTRY_LINE = "ENTRY";

    private static final String COLOR_BASE = "#eab308";
    private static final String COLOR_NEXT_BUY = "#22c55e";
    private static final String COLOR_ENTRY = "#f59e0b";
    private static final String COLOR_GRID_ZONE = "rgba(59,130,246,0.12)";

    private final StrategyLivePublisher live;
    private final FibonacciGridStrategySettingsService fiboSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;
    private final UiStrategyLayerService uiLayers;
    private final SimpMessagingTemplate ws;
    private final AdaptiveRuntimeController adaptiveRuntimeController;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static final class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        FibonacciGridStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;
        long buys;
        long sells;

        BigDecimal basePrice;
        boolean[] levelFired;

        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        Instant lastEntryAt;

        String lastHoldReason;
        Instant lastHoldAt;
        long candlesWithoutEntry;
        Instant lastGridRearmAt;
    }

    @Override
    public void start(Long chatId, String ignored) {
        StrategySettings ss = loadStrategySettings(chatId);
        FibonacciGridStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.ss = ss;
        st.cfg = cfg;
        st.symbol = safeUpper(ss.getSymbol());
        st.exchange = ss.getExchangeName();
        st.network = ss.getNetworkType();
        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);
        st.levelFired = new boolean[normalizeLevels(cfg)];

        states.put(chatId, st);
        adaptiveRuntimeController.onStrategyStarted(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network, st.symbol, ss != null ? ss.getTimeframe() : null);

        if (st.symbol != null) {
            clearTransientUiLayers(chatId, st.symbol);
        }

        log.info("[FIBO_GRID] ▶ START chatId={} symbol={} levels={} stepPct={} tpPct={} slPct={}",
                chatId,
                st.symbol,
                st.levelFired.length,
                fmtBd(normalizeStepPct(cfg)),
                fmtBd(normalizeTakeProfitPct(cfg)),
                fmtBd(normalizeStopLossPct(cfg)));

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_GRID, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {
        LocalState st = states.remove(chatId);
        if (st == null) {
            return;
        }

        adaptiveRuntimeController.onStrategyStopped(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network);
        String sym = st.symbol;
        if (sym != null) {
            clearTransientUiLayers(chatId, sym);
            safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_GRID, sym, false));
        }

        log.info("[FIBO_GRID] ⏹ STOP chatId={} symbol={} ticks={} buys={} sells={} inPos={} qty={} avgEntry={}",
                chatId,
                sym,
                st.ticks,
                st.buys,
                st.sells,
                st.inPosition,
                fmtBd(st.entryQty),
                fmtBd(st.entryPrice));
    }

    @Override
    public boolean isActive(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs,
                              String exchange,
                              NetworkType network) {
        if (type != StrategyType.FIBONACCI_GRID) {
            return;
        }

        LocalState st = states.get(chatId);
        if (st == null || !st.active) {
            return;
        }

        if (exchange != null && !exchange.isBlank()) {
            st.exchange = exchange.trim().toUpperCase(Locale.ROOT);
        }
        if (network != null) {
            st.network = network;
        }
        if (symbol != null && !symbol.isBlank()) {
            st.symbol = safeUpper(symbol);
        }
        if (timeframe != null && !timeframe.isBlank() && st.ss != null) {
            try {
                st.ss.setTimeframe(timeframe.trim().toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }

        Instant ts = tradeTsMs > 0 ? Instant.ofEpochMilli(tradeTsMs) : Instant.now();
        onPriceUpdate(Long.valueOf(chatId), symbol, price, ts);
    }

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {
        LocalState st = states.get(chatId);
        if (st == null || !st.active) {
            return;
        }

        adaptiveRuntimeController.onTick(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network);
        st.ticks++;

        if (price == null || price.signum() <= 0) {
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        String tickSym = safeUpper(symbolFromTick);
        String stateSym = safeUpper(st.symbol);
        if (stateSym != null && tickSym != null && !stateSym.equals(tickSym)) {
            return;
        }
        if (stateSym == null && tickSym != null) {
            st.symbol = tickSym;
            stateSym = tickSym;
        }

        final String initialSym = stateSym;
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.FIBONACCI_GRID, initialSym, price, time));

        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            FibonacciGridStrategySettings cfg = st.cfg;
            String symbol = safeUpper(st.symbol);

            if (symbol == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symbol, st, "no_fibo_grid_settings", time);
                return;
            }

            int levels = normalizeLevels(cfg);
            BigDecimal stepPct = normalizeStepPct(cfg);
            BigDecimal takeProfitPct = normalizeTakeProfitPct(cfg, ss, st.exchange, st.network);
            BigDecimal stopLossPct = normalizeStopLossPct(cfg);

            if (stepPct.signum() <= 0) {
                pushHoldThrottled(chatId, symbol, st, "step_pct<=0", time);
                return;
            }

            resizeLevelArrayPreserveState(st, levels);

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[FIBO_GRID] tick chatId={} sym={} price={} base={} inPos={} qty={} tp={} sl={}",
                        chatId,
                        symbol,
                        fmtBd(price),
                        fmtBd(st.basePrice),
                        st.inPosition,
                        fmtBd(st.entryQty),
                        fmtBd(st.tp),
                        fmtBd(st.sl));
            }

            if (st.basePrice == null || st.basePrice.signum() <= 0) {
                rearmGrid(st, price, levels, "base_price_set");
                publishRuntimeUi(chatId, symbol, st, time);

                log.info("[FIBO_GRID] 🎯 base price set chatId={} sym={} base={} levels={} stepPct={}",
                        chatId,
                        symbol,
                        fmtBd(st.basePrice),
                        levels,
                        fmtBd(stepPct));

                safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.hold("base_price_set")));
            }

            if (hasOpenPosition(st)) {
                try {
                    BigDecimal exitQty = st.entryQty;
                    BigDecimal exitTp = st.tp;
                    BigDecimal exitSl = st.sl;

                    var exit = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.FIBONACCI_GRID,
                            symbol,
                            price,
                            time,
                            true,
                            exitQty,
                            exitTp,
                            exitSl,
                            st.exchange,
                            st.network
                    );

                    if (exit.executed()) {
                        st.sells++;

                        log.info("[FIBO_GRID] ✅ EXIT OK chatId={} sym={} price={} qty={} tp={} sl={}",
                                chatId,
                                symbol,
                                fmtBd(price),
                                fmtBd(exitQty),
                                fmtBd(exitTp),
                                fmtBd(exitSl));

                        BigDecimal realizedPnlPct = calcLongPnlPct(st.entryPrice, price);
                        BigDecimal realizedPnlUsd = calcLongPnlUsd(st.entryPrice, price, exitQty);
                        BigDecimal holdSeconds = calcHoldSeconds(st.lastEntryAt, time);
                        appendTradeUi(chatId, symbol, time, "SELL", price, exitQty);
                        pushExitVisuals(chatId, symbol, price, exitQty, time, "tp_sl_exit");
                        adaptiveRuntimeController.onExit(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network, symbol, readTimeframe(st.ss), realizedPnlPct, realizedPnlUsd, "tp_sl_exit", holdSeconds, time);

                        clearPosition(st);
                        rearmGrid(st, price, levels, "rearm_after_exit");
                        publishRuntimeUi(chatId, symbol, st, time);
                        return;
                    }
                } catch (Exception e) {
                    log.error("[FIBO_GRID] ❌ EXIT failed chatId={} sym={} err={}", chatId, symbol, e.getMessage(), e);
                }
            }

            int hitLevel = findHitLevel(st.basePrice, price, stepPct, levels, st.levelFired);
            if (hitLevel < 0) {
                pushHoldThrottled(chatId, symbol, st, "no_level_hit", time);
                return;
            }

            st.levelFired[hitLevel] = true;

            try {
                double score = Math.min(99.0d, 55.0d + (hitLevel * 8.0d));
                BigDecimal confidence = BigDecimal.valueOf(score).divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP);
                syncSyntheticMlConfidence(ss, confidence);

                var entry = tradeExecutionService.executeEntry(
                        chatId,
                        StrategyType.FIBONACCI_GRID,
                        symbol,
                        price,
                        confidence,
                        time,
                        ss,
                        takeProfitPct,
                        stopLossPct
                );

                if (!entry.executed()) {
                    st.levelFired[hitLevel] = false;
                    String reason = safeReason(entry.reason());
                    log.info("[FIBO_GRID] ✋ BUY blocked chatId={} sym={} level={} reason={}", chatId, symbol, hitLevel, reason);
                    pushHoldThrottled(chatId, symbol, st, reason, time);
                    publishRuntimeUi(chatId, symbol, st, time);
                    return;
                }

                if (entry.qty() == null || entry.qty().signum() <= 0 || entry.entryPrice() == null || entry.entryPrice().signum() <= 0) {
                    st.levelFired[hitLevel] = false;
                    log.warn("[FIBO_GRID] ⚠ BUY executed but invalid fill payload chatId={} sym={} level={} qty={} entryPrice={}",
                            chatId,
                            symbol,
                            hitLevel,
                            fmtBd(entry.qty()),
                            fmtBd(entry.entryPrice()));
                    pushHoldThrottled(chatId, symbol, st, "invalid_entry_payload", time);
                    publishRuntimeUi(chatId, symbol, st, time);
                    return;
                }

                st.buys++;
                applyEntry(st, entry.entryPrice(), entry.qty(), entry.tp(), entry.sl());
                st.lastEntryAt = time;
                adaptiveRuntimeController.onEntry(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network, symbol, readTimeframe(st.ss), time);
                appendTradeUi(chatId, symbol, time, "BUY", entry.entryPrice(), entry.qty());
                pushEntryVisuals(chatId, symbol, st, st.entryPrice, st.entryQty, time);
                publishRuntimeUi(chatId, symbol, st, time);

                final double scoreFinal = score;
                safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.buy(scoreFinal, "level_" + hitLevel)));

                log.info("[FIBO_GRID] 🟢 BUY level={} chatId={} sym={} price={} qty={} avgEntry={} posQty={} tp={} sl={}",
                        hitLevel,
                        chatId,
                        symbol,
                        fmtBd(price),
                        fmtBd(entry.qty()),
                        fmtBd(st.entryPrice),
                        fmtBd(st.entryQty),
                        fmtBd(st.tp),
                        fmtBd(st.sl));
            } catch (Exception e) {
                st.levelFired[hitLevel] = false;
                log.error("[FIBO_GRID] ❌ BUY failed chatId={} sym={} level={} err={}", chatId, symbol, hitLevel, e.getMessage(), e);
                pushHoldThrottled(chatId, symbol, st, "buy_failed", time);
                publishRuntimeUi(chatId, symbol, st, time);
            }
        }
    }


@Override
public void onCandleClosed(long chatId,
                           StrategyType type,
                           String symbol,
                           String timeframe,
                           UnifiedKline kline,
                           String exchange,
                           NetworkType network) {
    LocalState st = states.get(chatId);
    if (st == null || !st.active) {
        return;
    }

    Instant now = Instant.now();
    synchronized (st) {
        refreshSettingsIfNeeded(chatId, st, now);

        String eventSymbol = safeUpper(symbol);
        String stateSymbol = safeUpper(st.symbol);
        if (stateSymbol != null && eventSymbol != null && !stateSymbol.equals(eventSymbol)) {
            return;
        }
        if (stateSymbol == null && eventSymbol != null) {
            st.symbol = eventSymbol;
            stateSymbol = eventSymbol;
        }
        if (stateSymbol == null) {
            return;
        }

        if ((st.basePrice == null || st.basePrice.signum() <= 0) && kline != null) {
            BigDecimal closePrice = readKlinePrice(kline, "getClose", "close", "getClosePrice", "closePrice");
            if (closePrice != null && closePrice.signum() > 0) {
                rearmGrid(st, closePrice, normalizeLevels(st.cfg), "base_price_set_from_candle");
            }
        }

        Instant candleTime = resolveKlineTime(kline, now);
        adaptiveRuntimeController.onCandleObserved(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network, stateSymbol, readTimeframe(st.ss), null, null, null, candleTime);

        BigDecimal closePrice = readKlinePrice(kline, "getClose", "close", "getClosePrice", "closePrice");
        maybeRearmStarvedGrid(chatId, stateSymbol, st, closePrice, candleTime);
        publishRuntimeUi(chatId, stateSymbol, st, candleTime);
    }
}

    private void maybeRearmStarvedGrid(Long chatId, String symbol, LocalState st, BigDecimal closePrice, Instant candleTime) {
        if (chatId == null || st == null || symbol == null || symbol.isBlank()) {
            return;
        }
        if (hasOpenPosition(st)) {
            st.candlesWithoutEntry = 0L;
            return;
        }
        if (st.basePrice == null || st.basePrice.signum() <= 0 || closePrice == null || closePrice.signum() <= 0) {
            return;
        }

        st.candlesWithoutEntry++;

        if (st.candlesWithoutEntry < STARVATION_REARM_CANDLES) {
            return;
        }

        if (st.lastGridRearmAt != null
                && candleTime != null
                && Duration.between(st.lastGridRearmAt, candleTime).compareTo(GRID_REARM_COOLDOWN) < 0) {
            return;
        }

        BigDecimal driftPct = calcDistancePct(st.basePrice, closePrice);
        BigDecimal stepPct = normalizeStepPct(st.cfg);
        BigDecimal triggerPct = stepPct.divide(new BigDecimal("2"), PRICE_SCALE, RoundingMode.HALF_UP);
        if (triggerPct.compareTo(new BigDecimal("0.10")) < 0) {
            triggerPct = new BigDecimal("0.10");
        }

        if (driftPct.compareTo(triggerPct) < 0) {
            st.candlesWithoutEntry = 0L;
            return;
        }

        int levels = normalizeLevels(st.cfg);
        BigDecimal previousBase = st.basePrice;
        rearmGrid(st, closePrice, levels, "starvation_recenter");

        log.info("[FIBO_GRID] 🧭 starvation recenter chatId={} sym={} oldBase={} newBase={} driftPct={} candlesWithoutEntry={}",
                chatId,
                symbol,
                fmtBd(previousBase),
                fmtBd(closePrice),
                fmtBd(driftPct),
                STARVATION_REARM_CANDLES);

        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.hold("grid_rearmed_starvation")));
    }

    private BigDecimal calcDistancePct(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0 || to.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = to.subtract(from).abs();
        return diff
                .multiply(ONE_HUNDRED)
                .divide(from, PRICE_SCALE, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st.lastSettingsLoadAt != null
                && Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        st.lastSettingsLoadAt = now;

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            FibonacciGridStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

            String oldSymbol = safeUpper(st.symbol);
            String requestedSymbol = safeUpper(loaded.getSymbol());
            boolean symbolChanged = oldSymbol != null && requestedSymbol != null && !Objects.equals(oldSymbol, requestedSymbol);

            if (symbolChanged && hasOpenPosition(st)) {
                log.warn("[FIBO_GRID] ⚠ symbol change deferred until position close chatId={} oldSymbol={} requestedSymbol={} qty={} avgEntry={}",
                        chatId,
                        oldSymbol,
                        requestedSymbol,
                        fmtBd(st.entryQty),
                        fmtBd(st.entryPrice));

                loaded.setSymbol(oldSymbol);
                requestedSymbol = oldSymbol;
                symbolChanged = false;
            }

            String newFingerprint = buildFingerprint(loaded, cfg);
            boolean changed = !Objects.equals(st.lastFingerprint, newFingerprint);

            st.ss = loaded;
            st.cfg = cfg;
            st.symbol = requestedSymbol;
            st.exchange = loaded.getExchangeName();
            st.network = loaded.getNetworkType();

            if (!changed) {
                return;
            }

            st.lastFingerprint = newFingerprint;

            int levels = normalizeLevels(cfg);
            BigDecimal stepPct = normalizeStepPct(cfg);
            resizeLevelArrayPreserveState(st, levels);

            log.info("[FIBO_GRID] ⚙️ settings updated chatId={} symbol={} levels={} stepPct={} tpPct={} slPct={} inPos={}",
                    chatId,
                    st.symbol,
                    levels,
                    fmtBd(stepPct),
                    fmtBd(normalizeTakeProfitPct(cfg)),
                    fmtBd(normalizeStopLossPct(cfg)),
                    st.inPosition);

            if (symbolChanged) {
                clearTransientUiLayers(chatId, oldSymbol);

                if (!hasOpenPosition(st)) {
                    rearmGrid(st, null, levels, "symbol_changed");
                    clearPosition(st);
                    st.lastHoldReason = null;
                    log.info("[FIBO_GRID] 🔄 grid reset after symbol change chatId={} symbol={}", chatId, st.symbol);
                }
            }

            if (!hasOpenPosition(st) && st.basePrice != null && st.basePrice.signum() > 0) {
                BigDecimal deepestLevel = computeLevelPrice(st.basePrice, stepPct, levels - 1);
                if (deepestLevel == null || deepestLevel.signum() <= 0) {
                    rearmGrid(st, st.basePrice, levels, "invalid_deepest_level");
                }
            }

            if (st.symbol != null) {
                publishRuntimeUi(chatId, st.symbol, st, now);
            }
        } catch (Exception e) {
            log.warn("[FIBO_GRID] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private void applyEntry(LocalState st, BigDecimal entryPrice, BigDecimal qty, BigDecimal tp, BigDecimal sl) {
        if (qty == null || qty.signum() <= 0 || entryPrice == null || entryPrice.signum() <= 0) {
            return;
        }

        if (!st.inPosition || st.entryQty == null || st.entryQty.signum() <= 0 || st.entryPrice == null || st.entryPrice.signum() <= 0) {
            st.inPosition = true;
            st.entryQty = qty;
            st.entryPrice = entryPrice;
        } else {
            BigDecimal currentQty = st.entryQty;
            BigDecimal currentPrice = st.entryPrice;
            BigDecimal sumQty = currentQty.add(qty);

            if (sumQty.signum() <= 0) {
                st.inPosition = false;
                st.entryQty = null;
                st.entryPrice = null;
            } else {
                BigDecimal weighted = currentPrice.multiply(currentQty)
                        .add(entryPrice.multiply(qty))
                        .divide(sumQty, PRICE_SCALE, RoundingMode.HALF_UP);
                st.inPosition = true;
                st.entryQty = sumQty;
                st.entryPrice = weighted;
            }
        }

        st.tp = tp;
        st.sl = sl;
        st.candlesWithoutEntry = 0L;
        st.lastHoldReason = null;
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.lastEntryAt = null;
        st.candlesWithoutEntry = 0L;
    }

    private void pushEntryVisuals(Long chatId, String symbol, LocalState st, BigDecimal price, BigDecimal qty, Instant time) {
        if (chatId == null || symbol == null || st == null) {
            return;
        }
        safeLive(() -> live.pushTrade(chatId, StrategyType.FIBONACCI_GRID, symbol, "BUY", price, qty, time));
        if (price != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, "ENTRY", price));
        }
        if (st.tp != null || st.sl != null) {
            safeLive(() -> live.pushTpSl(chatId, StrategyType.FIBONACCI_GRID, symbol, st.tp, st.sl));
        }
        if (st.tp != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, "TP", st.tp));
        }
        if (st.sl != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, "SL", st.sl));
        }
    }

    private void pushExitVisuals(Long chatId, String symbol, BigDecimal price, BigDecimal qty, Instant time, String reason) {
        if (chatId == null || symbol == null) {
            return;
        }
        safeLive(() -> live.pushTrade(chatId, StrategyType.FIBONACCI_GRID, symbol, "SELL", price, qty, time));
        safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, symbol));
        safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, symbol));
        if (reason != null && !reason.isBlank()) {
            safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.sell(1.0, reason)));
        }
    }

    private void rearmGrid(LocalState st, BigDecimal newBasePrice, int levels, String reason) {
        st.basePrice = newBasePrice != null && newBasePrice.signum() > 0 ? newBasePrice : null;
        st.levelFired = new boolean[Math.max(1, levels)];
        st.lastHoldReason = null;
        st.lastHoldAt = null;
        st.candlesWithoutEntry = 0L;
        st.lastGridRearmAt = Instant.now();

        log.info("[FIBO_GRID] ♻️ grid rearmed symbol={} base={} levels={} reason={}",
                st.symbol,
                fmtBd(st.basePrice),
                Math.max(1, levels),
                reason);
    }

    private boolean hasOpenPosition(LocalState st) {
        return st.inPosition
                && st.entryQty != null && st.entryQty.signum() > 0
                && st.entryPrice != null && st.entryPrice.signum() > 0
                && st.tp != null && st.sl != null;
    }

    private void resizeLevelArrayPreserveState(LocalState st, int levels) {
        int normalized = Math.max(1, levels);
        if (st.levelFired == null) {
            st.levelFired = new boolean[normalized];
            return;
        }
        if (st.levelFired.length == normalized) {
            return;
        }

        boolean[] resized = new boolean[normalized];
        System.arraycopy(st.levelFired, 0, resized, 0, Math.min(st.levelFired.length, normalized));
        st.levelFired = resized;
    }

    private int findHitLevel(BigDecimal base, BigDecimal price, BigDecimal stepPct, int levels, boolean[] fired) {
        if (base == null || base.signum() <= 0 || price == null || price.signum() <= 0 || stepPct == null || stepPct.signum() <= 0) {
            return -1;
        }

        for (int i = 0; i < levels; i++) {
            if (fired != null && i < fired.length && fired[i]) {
                continue;
            }

            BigDecimal levelPrice = computeLevelPrice(base, stepPct, i);
            if (levelPrice == null || levelPrice.signum() <= 0) {
                break;
            }

            if (price.compareTo(levelPrice) <= 0) {
                return i;
            }
        }
        return -1;
    }

    private BigDecimal computeLevelPrice(BigDecimal base, BigDecimal stepPct, int levelIndex) {
        if (base == null || base.signum() <= 0 || stepPct == null || stepPct.signum() <= 0 || levelIndex < 0) {
            return null;
        }

        BigDecimal stepFraction = stepPct.divide(ONE_HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal distance = stepFraction.multiply(BigDecimal.valueOf(levelIndex + 1L));
        BigDecimal multiplier = BigDecimal.ONE.subtract(distance);
        if (multiplier.signum() <= 0) {
            return null;
        }

        return base.multiply(multiplier).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private void publishRuntimeUi(Long chatId, String symbol, LocalState st, Instant candleTime) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        if (st.basePrice == null || st.basePrice.signum() <= 0) {
            clearTransientUiLayers(chatId, symbol);
            return;
        }

        int levels = normalizeLevels(st.cfg);
        BigDecimal stepPct = normalizeStepPct(st.cfg);

        List<Double> uiLevels = new ArrayList<>();
        BigDecimal deepestLevel = null;
        BigDecimal nextBuy = null;

        for (int i = 0; i < levels; i++) {
            BigDecimal levelPrice = computeLevelPrice(st.basePrice, stepPct, i);
            if (levelPrice == null || levelPrice.signum() <= 0) {
                break;
            }

            Double asDouble = toDouble(levelPrice);
            if (asDouble != null) {
                uiLevels.add(asDouble);
            }

            deepestLevel = levelPrice;

            boolean fired = st.levelFired != null && i < st.levelFired.length && st.levelFired[i];
            if (!fired && nextBuy == null) {
                nextBuy = levelPrice;
            }
        }

        try {
            if (uiLevels.isEmpty()) {
                uiLayers.clearLevels(chatId, StrategyType.FIBONACCI_GRID, symbol);
            } else {
                uiLayers.saveLevels(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, uiLevels);
            }

            Double base = toDouble(st.basePrice);
            Double bottom = toDouble(deepestLevel);

            if (base != null && bottom != null) {
                uiLayers.saveZone(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, base, bottom, COLOR_GRID_ZONE);
            } else {
                uiLayers.clearZone(chatId, StrategyType.FIBONACCI_GRID, symbol);
            }

            uiLayers.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, symbol);

            if (base != null) {
                uiLayers.upsertPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, BASE_LINE, base, COLOR_BASE);
            }

            Double nextBuyPrice = toDouble(nextBuy);
            if (nextBuyPrice != null) {
                uiLayers.upsertPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, NEXT_BUY_LINE, nextBuyPrice, COLOR_NEXT_BUY);
            }

            Double entryPrice = toDouble(st.entryPrice);
            if (entryPrice != null && st.inPosition) {
                uiLayers.upsertPriceLine(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, ENTRY_LINE, entryPrice, COLOR_ENTRY);
            }

            Double tp = toDouble(st.tp);
            Double sl = toDouble(st.sl);
            if (st.inPosition && (tp != null || sl != null)) {
                uiLayers.saveTpSl(chatId, StrategyType.FIBONACCI_GRID, symbol, candleTime, tp, sl);
            } else {
                uiLayers.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, symbol);
            }

            pushLayersSnapshot(chatId, symbol, candleTime);
        } catch (Exception e) {
            log.warn("[FIBO_GRID] ⚠ UI publish failed chatId={} symbol={} err={}", chatId, symbol, e.toString());
        }
    }

    private void appendTradeUi(Long chatId, String symbol, Instant time, String side, BigDecimal price, BigDecimal qty) {
        if (symbol == null) {
            return;
        }

        Double p = toDouble(price);
        if (p == null) {
            return;
        }

        try {
            uiLayers.appendTrade(
                    chatId,
                    StrategyType.FIBONACCI_GRID,
                    symbol,
                    time,
                    side,
                    p,
                    toDouble(qty)
            );
        } catch (Exception e) {
            log.warn("[FIBO_GRID] ⚠ appendTrade UI failed chatId={} symbol={} side={} err={}",
                    chatId, symbol, side, e.toString());
        }
    }

    /**
     * Очищаем только runtime-слои стратегии.
     *
     * ВАЖНО:
     * trade-маркеры не трогаем, потому что они должны переживать:
     * - рестарт стратегии
     * - повторный replay
     * - повторную загрузку dashboard
     *
     * Исторические BUY/SELL должны оставаться на графике и добираться отдельным loader-ом
     * из истории сделок биржи / fallback-источников, а не стираться стартом стратегии.
     */
    private void clearTransientUiLayers(Long chatId, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        try {
            uiLayers.clearLevels(chatId, StrategyType.FIBONACCI_GRID, symbol);
            uiLayers.clearZone(chatId, StrategyType.FIBONACCI_GRID, symbol);
            uiLayers.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, symbol);
            uiLayers.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, symbol);
            pushLayersSnapshot(chatId, symbol, Instant.now());
        } catch (Exception e) {
            log.warn("[FIBO_GRID] ⚠ UI transient-clear failed chatId={} symbol={} err={}", chatId, symbol, e.toString());
        }
    }

    private int normalizeLevels(FibonacciGridStrategySettings cfg) {
        return Math.max(1, nz(cfg != null ? cfg.getGridLevels() : null, DEFAULT_LEVELS));
    }

    private BigDecimal normalizeStepPct(FibonacciGridStrategySettings cfg) {
        BigDecimal step = nzBd(cfg != null ? cfg.getDistancePct() : null, DEFAULT_STEP_PCT);
        if (step.signum() <= 0) {
            return DEFAULT_STEP_PCT;
        }
        return step;
    }

    private BigDecimal normalizeTakeProfitPct(FibonacciGridStrategySettings cfg) {
        return normalizeTakeProfitPct(cfg, null, null, null);
    }

    private BigDecimal normalizeTakeProfitPct(FibonacciGridStrategySettings cfg,
                                              StrategySettings ss,
                                              String exchange,
                                              NetworkType network) {
        BigDecimal tp = nzBd(cfg != null ? cfg.getTakeProfitPct() : null, DEFAULT_TP_PCT);
        if (tp.signum() <= 0) {
            tp = DEFAULT_TP_PCT;
        }

        BigDecimal sl = normalizeStopLossPct(cfg);
        if (tradeExecutionService instanceof TradeExecutionServiceImpl impl
                && ss != null
                && ss.getChatId() != null
                && exchange != null
                && network != null) {
            try {
                BigDecimal minHealthyTp = impl.resolveMinHealthyTpPct(ss.getChatId(), exchange, network, sl);
                if (minHealthyTp != null && minHealthyTp.signum() > 0 && minHealthyTp.compareTo(tp) > 0) {
                    tp = minHealthyTp;
                }
            } catch (Exception e) {
                log.debug("[FIBO_GRID] take-profit fee floor skipped chatId={} sym={} err={}",
                        ss.getChatId(),
                        safeUpper(ss.getSymbol()),
                        e.toString());
            }
        }

        return tp;
    }

    private BigDecimal normalizeStopLossPct(FibonacciGridStrategySettings cfg) {
        BigDecimal sl = nzBd(cfg != null ? cfg.getStopLossPct() : null, DEFAULT_SL_PCT);
        if (sl.signum() <= 0) {
            return DEFAULT_SL_PCT;
        }
        return sl;
    }

    private void syncSyntheticMlConfidence(StrategySettings ss, BigDecimal confidence) {
        if (ss == null || confidence == null || confidence.signum() <= 0) {
            return;
        }
        try {
            Method setter = ss.getClass().getMethod("setMlConfidence", BigDecimal.class);
            setter.invoke(ss, confidence.setScale(6, RoundingMode.HALF_UP));
        } catch (Exception ignored) {
        }
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService.getOrCreate(chatId, StrategyType.FIBONACCI_GRID);
    }

    private String buildFingerprint(StrategySettings ss, FibonacciGridStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String exchange = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String network = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String timeframe = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null
                ? String.valueOf(ss.getCachedCandlesLimit())
                : "null";

        String levels = cfg != null ? String.valueOf(cfg.getGridLevels()) : "null";
        String stepPct = cfg != null ? String.valueOf(cfg.getDistancePct()) : "null";
        String tpPct = cfg != null ? String.valueOf(cfg.getTakeProfitPct()) : "null";
        String slPct = cfg != null ? String.valueOf(cfg.getStopLossPct()) : "null";
        String orderVolume = cfg != null ? String.valueOf(cfg.getOrderVolume()) : "null";

        return symbol + "|" + exchange + "|" + network + "|" + timeframe + "|" + candles + "|"
                + levels + "|" + stepPct + "|" + tpPct + "|" + slPct + "|" + orderVolume;
    }

    private void pushLayersSnapshot(Long chatId, String symbol, Instant time) {
        if (chatId == null || symbol == null || symbol.isBlank()) {
            return;
        }

        try {
            StrategyChartDto.Layers layersSnapshot = uiLayers.buildLatestLayersForSnapshot(
                    chatId,
                    StrategyType.FIBONACCI_GRID,
                    symbol
            );
            if (layersSnapshot == null) {
                layersSnapshot = StrategyChartDto.Layers.empty();
            }

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "layers");
            msg.put("chatId", chatId);
            msg.put("strategyType", StrategyType.FIBONACCI_GRID.name());
            msg.put("symbol", symbol);
            msg.put("time", (time != null ? time : Instant.now()).toEpochMilli());
            msg.put("layers", layersSnapshot);

            String baseDest = "/topic/strategy/" + chatId + "/" + StrategyType.FIBONACCI_GRID.name();
            ws.convertAndSend(baseDest, msg);

            String symUpper = safeUpper(symbol);
            if (symUpper != null) {
                ws.convertAndSend(baseDest + "/" + symUpper, msg);
                ws.convertAndSend(baseDest + "/" + symUpper.toLowerCase(Locale.ROOT), msg);
            }
        } catch (Exception e) {
            log.warn("[FIBO_GRID] ⚠ WS layers push failed chatId={} symbol={} err={}", chatId, symbol, e.toString());
        }
    }


private Instant resolveKlineTime(UnifiedKline kline, Instant fallback) {
    Long ts = readKlineLong(kline,
            "getCloseTime",
            "closeTime",
            "getCloseTimeMs",
            "closeTimeMs",
            "getOpenTime",
            "openTime",
            "getOpenTimeMs",
            "openTimeMs");
    if (ts != null && ts > 0L) {
        try {
            return Instant.ofEpochMilli(ts);
        } catch (Exception ignored) {
        }
    }
    return fallback != null ? fallback : Instant.now();
}

private BigDecimal readKlinePrice(UnifiedKline kline, String... methods) {
    Object raw = readKlineValue(kline, methods);
    if (raw == null) {
        return null;
    }
    if (raw instanceof BigDecimal bd) {
        return bd;
    }
    if (raw instanceof Number n) {
        return BigDecimal.valueOf(n.doubleValue());
    }
    try {
        return new BigDecimal(String.valueOf(raw).trim());
    } catch (Exception ignored) {
        return null;
    }
}

private Long readKlineLong(UnifiedKline kline, String... methods) {
    Object raw = readKlineValue(kline, methods);
    if (raw == null) {
        return null;
    }
    if (raw instanceof Number n) {
        return n.longValue();
    }
    try {
        return Long.parseLong(String.valueOf(raw).trim());
    } catch (Exception ignored) {
        return null;
    }
}

private Object readKlineValue(UnifiedKline kline, String... methods) {
    if (kline == null || methods == null) {
        return null;
    }
    for (String methodName : methods) {
        if (methodName == null || methodName.isBlank()) {
            continue;
        }
        try {
            Method method = kline.getClass().getMethod(methodName);
            return method.invoke(kline);
        } catch (Exception ignored) {
        }
    }
    return null;
}

    private static String readTimeframe(StrategySettings ss) {
        return ss != null ? ss.getTimeframe() : null;
    }

    private static BigDecimal calcLongPnlPct(BigDecimal entryPrice, BigDecimal exitPrice) {
        if (entryPrice == null || exitPrice == null || entryPrice.signum() <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return exitPrice.subtract(entryPrice)
                .multiply(ONE_HUNDRED)
                .divide(entryPrice, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal calcLongPnlUsd(BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal qty) {
        if (entryPrice == null || exitPrice == null || qty == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return exitPrice.subtract(entryPrice)
                .multiply(qty)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal calcHoldSeconds(Instant entryAt, Instant exitAt) {
        if (entryAt == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        Instant effectiveExit = exitAt != null ? exitAt : Instant.now();
        long ms = Math.max(0L, Duration.between(entryAt, effectiveExit).toMillis());
        return BigDecimal.valueOf(ms).divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP);
    }

    private void safeLive(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
        }
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (symbol == null) {
            return;
        }

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            Duration sinceLast = Duration.between(st.lastHoldAt, now);
            if (sinceLast.compareTo(HOLD_SIGNAL_COOLDOWN) < 0) {
                return;
            }
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;
        adaptiveRuntimeController.onHold(chatId, StrategyType.FIBONACCI_GRID, st.exchange, st.network, reason, now);
        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.hold(reason)));
    }

    private static String safe(String value) {
        return value == null ? "null" : value.trim();
    }

    private static String safeUpper(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "entry_blocked";
        }
        return value.trim();
    }

    private static int nz(Integer value, int def) {
        return value != null ? value : def;
    }

    private static BigDecimal nzBd(BigDecimal value, BigDecimal def) {
        return value != null ? value : def;
    }

    private static String fmtBd(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static Double toDouble(BigDecimal value) {
        if (value == null) {
            return null;
        }
        double d = value.doubleValue();
        return Double.isFinite(d) ? d : null;
    }
}


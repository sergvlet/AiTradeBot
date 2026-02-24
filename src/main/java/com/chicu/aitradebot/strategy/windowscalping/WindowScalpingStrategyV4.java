package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.events.WindowScalpingSettingsUpdatedEvent;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.WINDOW_SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowScalpingStrategyV4 implements
        TradingStrategy,
        AiStrategyOrchestrator.PriceUpdateAware,
        AiStrategyOrchestrator.CandleCloseAware {

    @Value("${strategy.window.settingsRefreshSeconds:10}")
    private long settingsRefreshSeconds;

    // ✅ hot-update: как часто проверять version в сервисах (если методы getVersion есть)
    @Value("${strategy.window.settingsVersionCheckMs:1000}")
    private long settingsVersionCheckMs;

    @Value("${strategy.window.tickLogEveryTicks:800}")
    private long tickLogEveryTicks;

    @Value("${strategy.window.holdThrottleMs:2500}")
    private long holdThrottleMs;

    // === ML gate ===
    @Value("${strategy.window.mlEnabled:true}")
    private boolean mlEnabled;

    /**
     * true  -> fail-open: если predict недоступен, продолжаем без ML-гейта
     * false -> fail-closed: predict_failed => HOLD и запрет входа
     */
    @Value("${strategy.window.mlFailOpen:true}")
    private boolean mlFailOpen;

    // fallback-порог, если gateMinProb не задан
    @Value("${strategy.window.mlMinProba:0.60}")
    private double mlMinProba;

    // === AUTO-TUNE ON HOLD ===
    @Value("${strategy.window.autoTuneOnHold:true}")
    private boolean autoTuneOnHold;

    @Value("${strategy.window.autoTuneHoldCooldownSeconds:60}")
    private long autoTuneHoldCooldownSeconds;

    @Value("${strategy.window.autoTuneHoldReasons:range_too_small,windowSize<5,no_settings,window_invalid,range_zero,pos_invalid}")
    private String autoTuneHoldReasons;

    // =====================================================
    // ✅ COARSE-ADJUST (локальный, персистентный)
    // =====================================================

    @Value("${strategy.window.coarseAdjustEnabled:true}")
    private boolean coarseAdjustEnabled;

    @Value("${strategy.window.coarseAdjustAfterConsecutive:6}")
    private int coarseAdjustAfterConsecutive;

    @Value("${strategy.window.coarseAdjustCooldownSeconds:120}")
    private long coarseAdjustCooldownSeconds;

    @Value("${strategy.window.coarseAdjustFactor:0.85}")
    private double coarseAdjustFactor;

    @Value("${strategy.window.coarseAdjustMinFloorPct:0.02}")
    private double coarseAdjustMinFloorPct;

    // =====================================================

    private final StrategyLivePublisher live;
    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final ApplicationContext appContext;
    private final MlAutoTuneRuntime autoTuneRuntime;
    private final PositionStore positionStore;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    // =====================================================
    // ✅ КЭШИ
    // =====================================================

    private volatile Object cachedMlBean;
    private volatile boolean mlBeanResolved;

    private volatile String cachedHoldReasonsRaw;
    private volatile Set<String> cachedHoldReasonsSet;

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        WindowScalpingStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        // ✅ hot-update: отслеживание версии (без рестарта)
        Instant lastVersionCheckAt;
        Long lastSsVersion;
        Long lastCfgVersion;

        Deque<BigDecimal> window = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;

        Instant lastTradeClosedAt;
        Instant lastEntryAt;

        long ticks;
        long warmups;
        long entries;
        long exits;

        String lastHoldReason;
        Instant lastHoldAt;

        Instant lastDiagAt;
        Instant lastAutoTuneRequestAt;

        // ✅ для coarse-adjust
        int consecutiveRangeTooSmall;
        Instant lastCoarseAdjustAt;

        // ✅ throttled persist ML confidence (чтобы не спамить БД)
        Instant lastMlConfidenceSaveAt;
        Double lastMlConfidenceSaved;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String symbolHint) {
        start(chatId, symbolHint, null, null);
    }

    @Override
    public void start(Long chatId, String symbolHint, String exchange, NetworkType network) {

        String hintEx = normalizeExchangeOrNull(exchange);

        // ✅ settings грузим всегда (без зависимости от exchange/network)
        StrategySettings ss = loadStrategySettingsAuto(chatId, hintEx, network);
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.ss = ss;
        st.cfg = cfg;

        st.exchange = normalizeExchangeOrNull(ss != null ? ss.getExchangeName() : hintEx);
        st.network = ss != null ? ss.getNetworkType() : network;

        // ✅ символ: только из StrategySettings (если есть), иначе из hint
        String sym = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        if (sym == null) sym = normalizeSymbolOrNull(symbolHint);
        st.symbol = sym;

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

        // ✅ init versions
        st.lastSsVersion = extractEntityVersion(ss);
        st.lastCfgVersion = extractEntityVersion(cfg);
        st.lastVersionCheckAt = st.lastSettingsLoadAt;

        st.window.clear();

        st.inPosition = false;
        st.isLong = true;

        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.entryQty = null;
        st.entryOrderId = null;

        st.lastTradeClosedAt = null;
        st.lastEntryAt = null;

        st.lastHoldReason = null;
        st.lastHoldAt = null;

        st.lastDiagAt = null;
        st.lastAutoTuneRequestAt = null;

        // ✅ для coarse-adjust
        st.consecutiveRangeTooSmall = 0;
        st.lastCoarseAdjustAt = null;

        // ✅ throttled persist ML confidence
        st.lastMlConfidenceSaveAt = null;
        st.lastMlConfidenceSaved = null;

        states.put(chatId, st);

        ensureRuntimeContext(st, ss);

        if (st.exchange != null && st.network != null && isAutoTuneAllowed(ss)) {
            safeAutoTune(() -> autoTuneRuntime.onStrategyStarted(
                    chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network
            ));
        } else {
            if (st.exchange == null || st.network == null) {
                log.warn("[WINDOW] 🧠 skip autoTuneRuntime.onStrategyStarted (нет exchange/network) chatId={} ex={} net={}",
                        chatId, st.exchange, st.network);
            } else if (log.isDebugEnabled()) {
                log.debug("[WINDOW] 🧠 skip autoTuneRuntime.onStrategyStarted (mode={}, autoTuneEnabled={}) chatId={} ex={} net={}",
                        modeOrManual(ss), (ss != null && ss.isAutoTuneEnabled()), chatId, st.exchange, st.network);
            }
        }

        if (ss != null) {
            log.info("[WINDOW] ▶ START chatId={} ex={} net={} symbol={} window={} entryLow%={} minRange%={} TP%={} SL%={} ML={} failOpen={} mlMinFallback={} coarseAdjust={}",
                    chatId,
                    fmtEnumOrString(ss.getExchangeName()),
                    fmtEnumOrString(ss.getNetworkType()),
                    ss.getSymbol(),
                    cfg != null ? cfg.getWindowSize() : null,
                    safeToStr(tryInvoke(cfg, Double.class, BigDecimal.class, "getEntryFromLowPct", "getEntryLowPct", "getEntryLow")),
                    safeToStr(tryInvoke(cfg, Double.class, BigDecimal.class, "getMinRangePct", "getMinRangePctForEntry")),
                    cfg != null ? cfg.getTakeProfitPct() : null,
                    cfg != null ? cfg.getStopLossPct() : null,
                    mlEnabled,
                    mlFailOpen,
                    fmt(mlMinProba),
                    coarseAdjustEnabled
            );
        } else {
            log.warn("[WINDOW] ▶ START chatId={} ex={} net={} symbol={} (StrategySettings не найден — будет HOLD до появления настроек)",
                    chatId, st.exchange, st.network, st.symbol);
        }

        if (st.symbol != null) {
            final String symFinal = st.symbol;
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, symFinal, true));
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symFinal, null,
                    Signal.hold("Стратегия запущена")));
        }

        // ✅ восстановим позицию после рестарта/перезапуска
        if (st.symbol != null) {
            synchronized (st) {
                maybeRestorePositionFromStore(chatId, st, st.symbol, Instant.now());
            }
        }
    }

    @Override
    public void stop(Long chatId, String ignored) {
        stop(chatId, ignored, null, null);
    }

    @Override
    public void stop(Long chatId, String ignored, String exchange, NetworkType network) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        ensureRuntimeContext(st, st.ss);
        st.lastEntryAt = null;

        if (st.exchange != null && st.network != null) {
            safeAutoTune(() -> autoTuneRuntime.onStrategyStopped(chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network));
        }

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, false));
        }

        log.info("[WINDOW] ⏹ STOP chatId={} ex={} net={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
                chatId, st.exchange, st.network, sym, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
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

    // =====================================================
    // ORCHESTRATOR HOOKS
    // =====================================================

    @Override
    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs,
                              String exchange,
                              NetworkType network) {

        if (type != StrategyType.WINDOW_SCALPING) return;

        LocalState st = states.get(chatId);
        if (st != null) {
            String ex = normalizeExchangeOrNull(exchange);
            if (ex != null) st.exchange = ex;
            if (network != null) st.network = network;

            // ✅ FIX: НЕ перезаписываем st.symbol чужим символом.
            String incoming = normalizeSymbolOrNull(symbol);
            String current = normalizeSymbolOrNull(st.symbol);
            if (current == null && incoming != null) {
                st.symbol = incoming;
            } else if (current != null && incoming != null && !current.equals(incoming)) {
                if (st.ticks % Math.max(1, tickLogEveryTicks) == 0) {
                    log.warn("[WINDOW] ⚠ drop price event due to symbol mismatch chatId={} currentSym={} incomingSym={} ex={} net={}",
                            chatId, current, incoming, ex, network);
                }
                return;
            }
        }

        Instant ts = (tradeTsMs > 0) ? Instant.ofEpochMilli(tradeTsMs) : Instant.now();
        onPriceUpdate(chatId, symbol, price, ts);
    }

    @Override
    public void onCandleClosed(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               UnifiedKline kline,
                               String exchange,
                               NetworkType network) {

        if (type != StrategyType.WINDOW_SCALPING) return;
        if (kline == null) return;

        BigDecimal close = extractClosePriceSafe(kline);
        if (close == null || close.signum() <= 0) return;

        long tsMs = extractKlineCloseTimeMsSafe(kline);
        Instant ts = (tsMs > 0) ? Instant.ofEpochMilli(tsMs) : Instant.now();

        LocalState st = states.get(chatId);
        if (st != null) {
            String ex = normalizeExchangeOrNull(exchange);
            if (ex != null) st.exchange = ex;
            if (network != null) st.network = network;

            String incoming = normalizeSymbolOrNull(symbol);
            String current = normalizeSymbolOrNull(st.symbol);
            if (current == null && incoming != null) {
                st.symbol = incoming;
            } else if (current != null && incoming != null && !current.equals(incoming)) {
                if (st.ticks % Math.max(1, tickLogEveryTicks) == 0) {
                    log.warn("[WINDOW] ⚠ drop candle event due to symbol mismatch chatId={} currentSym={} incomingSym={} ex={} net={}",
                            chatId, current, incoming, ex, network);
                }
                return;
            }
        }

        onPriceUpdate(chatId, symbol, close, ts);
    }

    // =====================================================
    // PRICE UPDATE (CORE)
    // =====================================================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        long logEvery = Math.max(1, tickLogEveryTicks);
        long holdMs = Math.max(200, holdThrottleMs);

        if (price == null || price.signum() <= 0) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null ? ts : Instant.now());

        String tickSymbol = normalizeSymbolOrNull(symbolFromTick);
        String cfgSymbol = normalizeSymbolOrNull(st.symbol);

        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) {
            return;
        }

        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symLive = normalizeSymbolOrNull(st.symbol);
        if (symLive != null) {
            safeLive(() -> live.pushPriceTick(chatId, StrategyType.WINDOW_SCALPING, symLive, price, time));
        }

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            WindowScalpingStrategySettings cfg = st.cfg;
            String sym = normalizeSymbolOrNull(st.symbol);

            if (sym == null || cfg == null || ss == null) {
                pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Нет настроек (StrategySettings/CFG/символ).", logEvery, time);
                return;
            }

            ensureRuntimeContext(st, ss);

            int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
            if (windowSize < 5) {
                pushHoldThrottled(chatId, sym, st, "windowSize<5", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Окно слишком маленькое (нужно >= 5).", logEvery, time);
                return;
            }

            st.window.addLast(price);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.window.size() < windowSize) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time, holdMs);
                if (st.ticks % logEvery == 0) {
                    log.info("[WINDOW] ⏳ warmup chatId={} sym={} window={}/{} price={}",
                            chatId, sym, st.window.size(), windowSize, fmtBd(price));
                }
                return;
            }

            BigDecimal high = null;
            BigDecimal low = null;
            for (BigDecimal p : st.window) {
                if (p == null) continue;
                high = (high == null) ? p : high.max(p);
                low = (low == null) ? p : low.min(p);
            }

            if (high == null || low == null || low.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "window_invalid", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Окно некорректно (low/high не рассчитались).", logEvery, time);
                return;
            }

            BigDecimal range = high.subtract(low);
            if (range.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "range_zero", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Диапазон равен нулю (high==low).", logEvery, time);
                return;
            }

            double rangePct = range
                    .divide(low, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            double minRangePct = (cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0);
            if (rangePct + 1e-12 < minRangePct) {
                pushHoldThrottled(chatId, sym, st, "range_too_small", time, holdMs);
                if (st.ticks % logEvery == 0) {
                    log.info("[WINDOW] 💤 range too small chatId={} sym={} rangePct={} < minRangePct={} consecutive={}/{}",
                            chatId, sym, fmt(rangePct), fmt(minRangePct),
                            st.consecutiveRangeTooSmall, Math.max(2, coarseAdjustAfterConsecutive));
                }
                return;
            }

            st.consecutiveRangeTooSmall = 0;

            double pos = price.subtract(low)
                    .divide(range, 10, RoundingMode.HALF_UP)
                    .doubleValue();

            if (Double.isNaN(pos) || Double.isInfinite(pos)) {
                pushHoldThrottled(chatId, sym, st, "pos_invalid", time, holdMs);
                diagLogOccasionally(chatId, st, sym, price, "Позиция в окне (pos) некорректна (NaN/Inf).", logEvery, time);
                return;
            }

            double entryLowPct = (cfg.getEntryFromLowPct() != null ? cfg.getEntryFromLowPct() : 0.0);
            double entryHighPct = (cfg.getEntryFromHighPct() != null ? cfg.getEntryFromHighPct() : 0.0);

            double lowZone = clamp01(entryLowPct / 100.0);
            double highZone = clamp01(1.0 - (entryHighPct / 100.0));

            if (log.isDebugEnabled() && st.ticks % logEvery == 0) {
                log.debug("[WINDOW] tick chatId={} sym={} price={} low={} high={} rangePct={} posPct={}",
                        chatId,
                        sym,
                        fmtBd(price),
                        fmtBd(low),
                        fmtBd(high),
                        fmt(rangePct),
                        fmt(pos * 100.0));
            }

            // =====================================================
            // ENTRY (SPOT LONG)
            // =====================================================
            if (!st.inPosition && pos <= lowZone) {

                final double score = clamp01(
                        (lowZone <= 0.000001) ? 1.0 : (1.0 - (pos / lowZone))
                ) * 100.0;

                BigDecimal diffPctForEntry = BigDecimal.valueOf(Math.max(0.000001, (lowZone - pos) * 100.0));

                BigDecimal tpPct = cfg.getTakeProfitPct();
                BigDecimal slPct = cfg.getStopLossPct();
                if (tpPct == null || tpPct.signum() <= 0 || slPct == null || slPct.signum() <= 0) {
                    pushHoldThrottled(chatId, sym, st, "tp_sl_pct_invalid", time, holdMs);
                    return;
                }

                // =====================================================
                // ✅ ML gate
                // =====================================================
                if (isMlGateAllowed(ss)) {
                    double threshold = resolveMlThreshold(ss);

                    Map<String, Object> feats = buildMlFeatures(
                            chatId, st, sym, price, time,
                            low, high, range, rangePct, pos,
                            lowZone, highZone, windowSize, diffPctForEntry
                    );

                    Prediction pred = tryPredict(feats);

                    if (!pred.ok) {
                        if (!mlFailOpen) {
                            pushHoldThrottled(chatId, sym, st, "predict_failed", time, holdMs);
                            return;
                        } else if (st.ticks % logEvery == 0) {
                            log.warn("[WINDOW] 🤖 ML недоступен (fail-open) chatId={} sym={} reason={}",
                                    chatId, sym, pred.reason);
                        }
                    } else {
                        // ✅ локально обновим (для UI/логов)
                        try { ss.setMlConfidence(BigDecimal.valueOf(pred.proba)); } catch (Exception ignored) {}

                        // ✅ ВАЖНО: throttled persist в БД (иначе не увидишь обновления)
                        maybePersistMlConfidence(st, ss, pred.proba, time);

                        if (st.ticks % logEvery == 0) {
                            log.info("[WINDOW] 🤖 ML proba chatId={} sym={} model={} proba={} threshold={}",
                                    chatId, sym, pred.modelKey, fmt(pred.proba), fmt(threshold));
                        }
                        if (pred.proba + 1e-12 < threshold) {
                            pushHoldThrottled(chatId, sym, st, "ml_below_threshold", time, holdMs);
                            return;
                        }
                    }
                }

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            sym,
                            price,
                            diffPctForEntry,
                            time,
                            ss,
                            tpPct,
                            slPct
                    );

                    if (!res.executed()) {
                        pushHoldThrottled(chatId, sym, st, res.reason(), time, holdMs);
                        return;
                    }

                    st.entries++;
                    st.inPosition = true;
                    st.isLong = true;

                    st.entryPrice = res.entryPrice();
                    st.tp = res.tp();
                    st.sl = res.sl();
                    st.entryQty = res.qty();
                    st.entryOrderId = res.orderId();

                    st.lastEntryAt = time;

                    ensureRuntimeContext(st, ss);

                    if (st.tp != null && st.sl != null) {
                        safeLive(() -> live.pushTpSl(chatId, StrategyType.WINDOW_SCALPING, sym, st.tp, st.sl));
                    }

                    safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                            Signal.buy(score, "Вход у нижней границы окна")));

                    st.window.clear();
                    st.lastHoldReason = null;
                    st.consecutiveRangeTooSmall = 0;

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ ENTRY error chatId={} sym={} err={}", chatId, sym, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time, holdMs);
                    return;
                }
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================

            maybeRestorePositionFromStore(chatId, st, sym, time);

            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                if (st.lastEntryAt != null && Duration.between(st.lastEntryAt, time).toMillis() < 500) {
                    return;
                }

                try {
                    var exRes = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            sym,
                            price,
                            time,
                            true,
                            st.entryQty,
                            st.tp,
                            st.sl,
                            st.exchange,
                            st.network
                    );

                    if (exRes.executed()) {
                        st.exits++;

                        st.inPosition = false;
                        st.entryQty = null;
                        st.entryOrderId = null;
                        st.entryPrice = null;
                        st.tp = null;
                        st.sl = null;

                        st.lastTradeClosedAt = time;
                        st.lastEntryAt = null;

                        ensureRuntimeContext(st, ss);

                        if (st.exchange != null && st.network != null && isAutoTuneAllowed(ss)) {
                            safeAutoTune(() -> autoTuneRuntime.onPositionClosed(
                                    chatId,
                                    StrategyType.WINDOW_SCALPING,
                                    st.exchange,
                                    st.network
                            ));
                        }

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                                Signal.sell(1.0, "Выход по TP/SL")));
                    } else {
                        if (pos >= highZone) {
                            pushHoldThrottled(chatId, sym, st, "in_high_zone_wait_tp", time, holdMs);
                        }
                    }

                } catch (Exception e) {
                    log.error("[WINDOW] ❌ EXIT error chatId={} sym={} err={}", chatId, sym, e.getMessage(), e);
                }
            }

            if (st.ticks % logEvery == 0) {
                log.info("[WINDOW] 📋 chatId={} sym={} ex={} net={} ticks={} window={}/{} inPos={} entries={} exits={} lastHOLD={}",
                        chatId,
                        sym,
                        st.exchange,
                        st.network,
                        st.ticks,
                        st.window.size(),
                        (cfg.getWindowSize() != null ? cfg.getWindowSize() : -1),
                        st.inPosition,
                        st.entries,
                        st.exits,
                        (st.lastHoldReason != null ? (holdReasonRu(st.lastHoldReason) + " [" + st.lastHoldReason + "]") : "-")
                );
            }
        }
    }

    private void diagLogOccasionally(Long chatId, LocalState st, String symbol, BigDecimal price, String msg, long logEvery, Instant now) {
        if (logEvery <= 0) return;
        if (st.ticks % logEvery != 0) return;
        log.info("[WINDOW] 🩺 chatId={} sym={} price={} => {}",
                chatId,
                symbol,
                price != null ? fmtBd(price) : "null",
                msg
        );
        st.lastDiagAt = now;
    }

    // =====================================================
    // SETTINGS REFRESH (HOT-UPDATE)
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        Duration refreshEvery = Duration.ofSeconds(Math.max(1, settingsRefreshSeconds));

        boolean timeDue = (st.lastSettingsLoadAt == null) ||
                          Duration.between(st.lastSettingsLoadAt, now).compareTo(refreshEvery) >= 0;

        boolean versionDue = externalSettingsChanged(chatId, st, now);

        if (!timeDue && !versionDue) return;

        try {
            StrategySettings loaded = loadStrategySettingsAuto(chatId, st.exchange, st.network);
            WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = normalizeSymbolOrNull(st.symbol);

            if (loaded != null) st.ss = loaded;
            if (cfg != null) st.cfg = cfg;

            // ✅ обновим кеш версий после успешной загрузки
            st.lastSsVersion = extractEntityVersion(st.ss);
            st.lastCfgVersion = extractEntityVersion(st.cfg);

            if (loaded != null) {
                String loadedSymbol = normalizeSymbolOrNull(loaded.getSymbol());
                if (!st.inPosition && loadedSymbol != null) st.symbol = loadedSymbol;

                if (loaded.getExchangeName() != null) st.exchange = normalizeExchangeOrNull(loaded.getExchangeName());
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                if (st.ticks % Math.max(1, tickLogEveryTicks) == 0) {
                    log.info("[WINDOW] ⚙️ settings refreshed chatId={} ex={} net={} sym={} window={} minRange%={} TP%={} SL%={}",
                            chatId,
                            st.exchange,
                            st.network,
                            st.symbol,
                            cfg != null ? cfg.getWindowSize() : null,
                            safeToStr(tryInvoke(cfg, Double.class, BigDecimal.class, "getMinRangePct", "getMinRangePctForEntry")),
                            cfg != null ? cfg.getTakeProfitPct() : null,
                            cfg != null ? cfg.getStopLossPct() : null
                    );
                }

                String newSymbol = normalizeSymbolOrNull(st.symbol);
                if (!st.inPosition && oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.window.clear();
                    st.lastHoldReason = null;
                    st.consecutiveRangeTooSmall = 0;

                    log.info("[WINDOW] 🔄 symbol changed {} -> {} (not in position) => clear window", oldSymbol, newSymbol);
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[WINDOW] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    // ✅ version-driven refresh trigger (если сервисы реализуют getVersion)
    private boolean externalSettingsChanged(Long chatId, LocalState st, Instant now) {
        long checkMs = Math.max(250, settingsVersionCheckMs);

        if (st.lastVersionCheckAt != null) {
            long passed = Duration.between(st.lastVersionCheckAt, now).toMillis();
            if (passed < checkMs) return false;
        }
        st.lastVersionCheckAt = now;

        Long ssVer = tryCallLong(
                strategySettingsService,
                "getVersion",
                new Class<?>[]{Long.class, StrategyType.class},
                chatId, StrategyType.WINDOW_SCALPING
        );

        Long cfgVer = tryCallLong(
                windowSettingsService,
                "getVersion",
                new Class<?>[]{Long.class},
                chatId
        );

        if (ssVer != null && st.lastSsVersion != null && !ssVer.equals(st.lastSsVersion)) return true;
        if (cfgVer != null && st.lastCfgVersion != null && !cfgVer.equals(st.lastCfgVersion)) return true;

        if (st.lastSsVersion == null && ssVer != null) st.lastSsVersion = ssVer;
        if (st.lastCfgVersion == null && cfgVer != null) st.lastCfgVersion = cfgVer;

        return false;
    }

    private Long extractEntityVersion(Object entity) {
        if (entity == null) return null;
        Object v = tryInvoke(entity, Long.class, Number.class, "getVersion", "version");
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception ignored) { return null; }
    }

    private Long tryCallLong(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            Object out = m.invoke(target, args);
            if (out == null) return null;
            if (out instanceof Long l) return l;
            if (out instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(out).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    // =====================================================
    // 🔎 Fingerprint helpers (safe across different entity versions)
    // =====================================================

    private static String fmtEnumOrString(Object v) {
        if (v == null) return "null";
        if (v instanceof Enum<?> e) return e.name();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? "null" : s;
    }

    private static String fmtNum(Object v) {
        if (v == null) return "null";
        try {
            if (v instanceof BigDecimal bd) {
                return bd.stripTrailingZeros().toPlainString();
            }
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
                return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "null";
            BigDecimal bd = new BigDecimal(s);
            return bd.stripTrailingZeros().toPlainString();
        } catch (Exception ignore) {
            return "null";
        }
    }

    private static Object tryInvoke(Object target,
                                    Class<?> preferredA,
                                    Class<?> preferredB,
                                    String... methodNames) {
        if (target == null || methodNames == null) return null;

        Class<?> cls = target.getClass();

        for (String mName : methodNames) {
            if (mName == null || mName.isBlank()) continue;

            try {
                Method m = cls.getMethod(mName);
                m.setAccessible(true);
                Object val = m.invoke(target);
                if (val == null) continue;

                if (preferredA != null && preferredA.isInstance(val)) return val;
                if (preferredB != null && preferredB.isInstance(val)) return val;

                if (val instanceof Number || val instanceof Boolean || val instanceof String || val instanceof Enum) {
                    return val;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String buildFingerprint(StrategySettings ss, WindowScalpingStrategySettings cfg) {
        String symbol  = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        String ex      = ss != null ? fmtEnumOrString(ss.getExchangeName()) : "null";
        String net     = ss != null ? fmtEnumOrString(ss.getNetworkType()) : "null";
        String tf      = ss != null ? safeNullable(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String w         = (cfg != null) ? fmtNum(cfg.getWindowSize()) : "null";
        String low       = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getEntryFromLowPct", "getEntryLowPct", "getEntryLow")) : "null";
        String high      = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getEntryFromHighPct", "getEntryHighPct", "getEntryHigh")) : "null";
        String minRange  = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getMinRangePct", "getMinRangePctForEntry")) : "null";
        String maxSpread = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getMaxSpreadPct", "getMaxSpread", "getSpreadThreshold")) : "null";

        String tpPct = (cfg != null) ? fmtNum(cfg.getTakeProfitPct()) : "null";
        String slPct = (cfg != null) ? fmtNum(cfg.getStopLossPct()) : "null";

        String coarseAdjust = (cfg != null) ? fmtEnumOrString(tryInvoke(cfg, Boolean.class, null, "isCoarseAdjustEnabled", "getCoarseAdjustEnabled", "isCoarseAdjust")) : "null";
        String failOpen     = (cfg != null) ? fmtEnumOrString(tryInvoke(cfg, Boolean.class, null, "isFailOpen", "getFailOpen")) : "null";
        String mlEnabledCfg = (cfg != null) ? fmtEnumOrString(tryInvoke(cfg, Boolean.class, null, "isMlEnabled", "getMlEnabled")) : "null";
        String mlMinConf    = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getMlMinConfidence", "getMlMinConf")) : "null";
        String mlFallback   = (cfg != null) ? fmtNum(tryInvoke(cfg, Double.class, BigDecimal.class, "getMlMinFallback", "getMlFallback")) : "null";

        return String.join("|",
                "WINDOW_SCALPING",
                ex, net,
                (symbol != null ? symbol : "null"),
                tf, candles,
                w, low, high, minRange, maxSpread, tpPct, slPct,
                coarseAdjust, failOpen, mlEnabledCfg, mlMinConf, mlFallback
        );
    }

    // =====================================================
    // ✅ STRATEGY SETTINGS LOAD (AUTO)
    // =====================================================

    private StrategySettings loadStrategySettingsAuto(Long chatId, String exchange, NetworkType network) {
        if (chatId == null) return null;

        try {
            return strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING);
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ loadStrategySettingsAuto getOrCreate failed chatId={} err={}", chatId, e.toString());
            return null;
        }
    }

    // =====================================================
    // CONTEXT HELPERS
    // =====================================================

    private void ensureRuntimeContext(LocalState st, StrategySettings ss) {
        if (st == null) return;

        if (st.exchange == null && ss != null && ss.getExchangeName() != null) {
            st.exchange = normalizeExchangeOrNull(ss.getExchangeName());
        } else if (st.exchange != null) {
            st.exchange = normalizeExchangeOrNull(st.exchange);
        }

        if (st.network == null && ss != null && ss.getNetworkType() != null) {
            st.network = ss.getNetworkType();
        }
    }

    // =====================================================
    // MODE / FLAGS
    // =====================================================

    private static AdvancedControlMode modeOrManual(StrategySettings ss) {
        if (ss == null) return AdvancedControlMode.MANUAL;
        AdvancedControlMode m = ss.getAdvancedControlMode();
        return (m != null ? m : AdvancedControlMode.MANUAL);
    }

    private static boolean isManualMode(StrategySettings ss) {
        return modeOrManual(ss) == AdvancedControlMode.MANUAL;
    }

    private boolean isAutoTuneAllowed(StrategySettings ss) {
        return ss != null && ss.isAutoTuneEnabled() && !isManualMode(ss);
    }

    private boolean isMlGateAllowed(StrategySettings ss) {
        return mlEnabled && ss != null && ss.isMlGateEnabled() && !isManualMode(ss);
    }

    private boolean isCoarseAdjustAllowed(StrategySettings ss) {
        return coarseAdjustEnabled && isAutoTuneAllowed(ss);
    }

    // =====================================================
    // ✅ COARSE-ADJUST
    // =====================================================

    private void maybeCoarseAdjustOnRangeTooSmall(Long chatId, String symbol, LocalState st, Instant now) {
        if (!coarseAdjustEnabled) return;
        if (chatId == null || st == null) return;
        if (st.inPosition) return;
        if (!isCoarseAdjustAllowed(st.ss)) return;

        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null) return;

        int afterN = Math.max(2, coarseAdjustAfterConsecutive);
        if (st.consecutiveRangeTooSmall < afterN) return;

        long cd = Math.max(15, coarseAdjustCooldownSeconds);
        if (st.lastCoarseAdjustAt != null) {
            long passed = Duration.between(st.lastCoarseAdjustAt, now).getSeconds();
            if (passed < cd) return;
        }

        double factor = coarseAdjustFactor;
        if (Double.isNaN(factor) || Double.isInfinite(factor) || factor <= 0 || factor >= 1.0) factor = 0.85;

        double floor = coarseAdjustMinFloorPct;
        if (Double.isNaN(floor) || Double.isInfinite(floor) || floor <= 0) floor = 0.02;

        Double oldMinObj = cfg.getMinRangePct();
        double oldMin = (oldMinObj != null ? oldMinObj : 0.0);
        if (oldMin <= 0) oldMin = 0.10;

        double newMin = Math.max(floor, oldMin * factor);
        if (Math.abs(newMin - oldMin) < 1e-12) return;

        try {
            WindowScalpingStrategySettings patch = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .minRangePct(newMin)
                    .build();

            windowSettingsService.update(chatId, patch);

            st.cfg = windowSettingsService.getOrCreate(chatId);
            st.lastCfgVersion = extractEntityVersion(st.cfg);

            st.lastFingerprint = buildFingerprint(st.ss, st.cfg);
            st.lastSettingsLoadAt = now;

            st.window.clear();
            st.consecutiveRangeTooSmall = 0;
            st.lastCoarseAdjustAt = now;

            log.warn("[WINDOW] 🛠️ COARSE-ADJUST chatId={} sym={} minRangePct {} -> {} (after={} cooldown={}s)",
                    chatId, symbol, fmt(oldMin), fmt(newMin), afterN, cd);

        } catch (Exception e) {
            log.warn("[WINDOW] ⚠️ COARSE-ADJUST failed chatId={} sym={} err={}", chatId, symbol, e.toString());
        }
    }

    // =====================================================
    // ML (reflection-safe)
    // =====================================================

    private static class Prediction {
        final boolean ok;
        final String modelKey;
        final double proba;
        final String reason;

        private Prediction(boolean ok, String modelKey, double proba, String reason) {
            this.ok = ok;
            this.modelKey = modelKey;
            this.proba = proba;
            this.reason = reason;
        }

        static Prediction ok(String modelKey, double proba) {
            return new Prediction(true, modelKey, proba, null);
        }

        static Prediction fail(String reason) {
            return new Prediction(false, null, 0.0, reason);
        }
    }

    private double resolveMlThreshold(StrategySettings ss) {
        if (ss != null) {
            BigDecimal gate = ss.getGateMinProb();
            if (gate != null) {
                double v = gate.doubleValue();
                if (!Double.isNaN(v) && !Double.isInfinite(v) && v > 0) return v;
            }
        }
        return mlMinProba;
    }

    private Map<String, Object> buildMlFeatures(
            Long chatId,
            LocalState st,
            String symbol,
            BigDecimal price,
            Instant ts,
            BigDecimal low,
            BigDecimal high,
            BigDecimal range,
            double rangePct,
            double pos,
            double lowZone,
            double highZone,
            int windowSize,
            BigDecimal diffPctForEntry
    ) {
        Map<String, Object> f = new HashMap<>();

        f.put("chatId", chatId);
        f.put("strategy", StrategyType.WINDOW_SCALPING.name());
        f.put("symbol", symbol);
        f.put("exchange", st.exchange);
        f.put("network", st.network != null ? st.network.name() : null);
        f.put("ts", (ts != null ? ts : Instant.now()).toEpochMilli());

        f.put("windowSize", windowSize);
        f.put("price", price.doubleValue());
        f.put("low", low.doubleValue());
        f.put("high", high.doubleValue());
        f.put("range", range.doubleValue());
        f.put("rangePct", rangePct);

        f.put("pos01", pos);
        f.put("posPct", pos * 100.0);

        f.put("lowZone01", lowZone);
        f.put("highZone01", highZone);

        f.put("diffPctForEntry", diffPctForEntry.doubleValue());

        BigDecimal first = st.window.peekFirst();
        BigDecimal last = st.window.peekLast();
        if (first != null && first.signum() > 0 && last != null) {
            double retPct = last.subtract(first)
                    .divide(first, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
            f.put("retWindowPct", retPct);
        } else {
            f.put("retWindowPct", 0.0);
        }

        return f;
    }

    private Prediction tryPredict(Map<String, Object> features) {
        try {
            Object bean = findMlBean();
            if (bean == null) return Prediction.fail("ml_bean_not_found");

            Object resp = invokePredict(bean, features);
            if (resp == null) return Prediction.fail("predict_return_null");

            Boolean ok = (Boolean) readAny(resp, "ok", "isOk", "success", "isSuccess");
            if (ok != null && !ok) {
                Object reason = readAny(resp, "reason", "message", "error");
                return Prediction.fail(reason != null ? String.valueOf(reason) : "predict_not_ok");
            }

            Double proba = readNumber(resp, "proba", "probability", "confidence", "score");
            if (proba == null) return Prediction.fail("no_proba_in_response");

            String modelKey = (String) readAny(resp, "modelKey", "modelId", "key", "model");
            if (modelKey == null) modelKey = "unknown";

            return Prediction.ok(modelKey, proba);

        } catch (Exception e) {
            return Prediction.fail("predict_exception:" + e.getClass().getSimpleName());
        }
    }

    private Object findMlBean() {
        if (mlBeanResolved) return cachedMlBean;

        synchronized (this) {
            if (mlBeanResolved) return cachedMlBean;

            Object bean = null;

            try {
                if (appContext.containsBean("mlPredictionService")) bean = appContext.getBean("mlPredictionService");
                else if (appContext.containsBean("mlService")) bean = appContext.getBean("mlService");
            } catch (Exception ignored) {
            }

            if (bean == null) {
                try {
                    String[] names = appContext.getBeanDefinitionNames();
                    for (String n : names) {
                        Object b;
                        try {
                            b = appContext.getBean(n);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (hasSupportedPredictMethod(b.getClass())) {
                            bean = b;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            cachedMlBean = bean;
            mlBeanResolved = true;
            return cachedMlBean;
        }
    }

    private boolean hasSupportedPredictMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("predict")) continue;

            int pc = m.getParameterCount();
            Class<?>[] pt = m.getParameterTypes();

            if (pc == 1 && Map.class.isAssignableFrom(pt[0])) return true;
            if (pc == 5 && pt[2] == String.class && Map.class.isAssignableFrom(pt[3]) && pt[4] == Instant.class) return true;
            if (pc == 4 && pt[1] == String.class && Map.class.isAssignableFrom(pt[2]) && pt[3] == Instant.class) return true;
        }
        return false;
    }

    private Object invokePredict(Object bean, Map<String, Object> features) throws Exception {
        Class<?> c = bean.getClass();

        Long chatId = (Long) features.get("chatId");
        String symbol = (String) features.get("symbol");
        Instant ts = Instant.ofEpochMilli(((Number) features.getOrDefault("ts", System.currentTimeMillis())).longValue());

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predictWindowScalping")) continue;
            if (m.getParameterCount() != 4) continue;
            return m.invoke(bean, chatId, symbol, features, ts);
        }

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predict")) continue;
            if (m.getParameterCount() != 5) continue;
            return m.invoke(bean, StrategyType.WINDOW_SCALPING, chatId, symbol, features, ts);
        }

        for (Method m : c.getMethods()) {
            if (!m.getName().equals("predict")) continue;
            if (m.getParameterCount() != 1) continue;
            if (!Map.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
            return m.invoke(bean, features);
        }

        return null;
    }

    private Object readAny(Object obj, String... gettersOrFields) {
        try {
            Class<?> c = obj.getClass();
            for (String n : gettersOrFields) {
                Method m = findNoArgMethod(c, n);
                if (m != null) return m.invoke(obj);

                try {
                    var f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Double readNumber(Object obj, String... gettersOrFields) {
        Object v = readAny(obj, gettersOrFields);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignored) {
        }
        return null;
    }

    private Method findNoArgMethod(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (Exception ignored) {
        }
        String cap = !name.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try {
            return c.getMethod("get" + cap);
        } catch (Exception ignored) {
        }
        try {
            return c.getMethod("is" + cap);
        } catch (Exception ignored) {
        }
        return null;
    }

    // =====================================================
    // LIVE HELPERS + AUTO-TUNE on HOLD
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void safeAutoTune(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private Set<String> parsedAutoTuneHoldReasons() {
        String raw = (autoTuneHoldReasons == null ? "" : autoTuneHoldReasons.trim());
        if (raw.equals(cachedHoldReasonsRaw) && cachedHoldReasonsSet != null) {
            return cachedHoldReasonsSet;
        }

        try {
            if (raw.isEmpty()) {
                cachedHoldReasonsRaw = raw;
                cachedHoldReasonsSet = Set.of();
                return cachedHoldReasonsSet;
            }

            String[] parts = raw.split(",");
            Set<String> out = new HashSet<>();
            for (String p : parts) {
                String v = p.trim();
                if (!v.isEmpty()) out.add(v);
            }

            cachedHoldReasonsRaw = raw;
            cachedHoldReasonsSet = Set.copyOf(out);
            return cachedHoldReasonsSet;

        } catch (Exception ignored) {
            cachedHoldReasonsRaw = raw;
            cachedHoldReasonsSet = Set.of();
            return cachedHoldReasonsSet;
        }
    }

    private void maybeRequestAutoTuneOnHold(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (!autoTuneOnHold) return;
        if (autoTuneRuntime == null) return;
        if (st == null) return;
        if (st.inPosition) return;
        if (!isAutoTuneAllowed(st.ss)) return;

        ensureRuntimeContext(st, st.ss);

        if (st.exchange == null || st.network == null) return;

        Set<String> reasons = parsedAutoTuneHoldReasons();
        if (!reasons.isEmpty() && !reasons.contains(reason)) return;

        long cdSec = Math.max(10, autoTuneHoldCooldownSeconds);
        if (st.lastAutoTuneRequestAt != null) {
            long passed = Duration.between(st.lastAutoTuneRequestAt, now).getSeconds();
            if (passed < cdSec) return;
        }

        st.lastAutoTuneRequestAt = now;

        log.warn("[WINDOW] 🧠 AUTO-TUNE(HOLD) chatId={} sym={} reason={} cooldown={}s",
                chatId, symbol, reason, cdSec);

        safeAutoTune(() -> autoTuneRuntime.triggerTuneDebounced(
                chatId,
                StrategyType.WINDOW_SCALPING,
                st.exchange,
                st.network,
                "hold:" + reason,
                Duration.ofSeconds(cdSec)
        ));
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now, long holdMs) {
        if (st == null) return;

        String sym = normalizeSymbolOrNull(symbol);
        if (sym == null) sym = normalizeSymbolOrNull(st.symbol);

        final String symFinal = sym;

        if ("range_too_small".equals(reason)) {
            st.consecutiveRangeTooSmall = Math.max(0, st.consecutiveRangeTooSmall) + 1;
        } else if (!"warming_up".equals(reason)) {
            st.consecutiveRangeTooSmall = 0;
        }

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < holdMs) {
                if ("range_too_small".equals(reason)) {
                    maybeCoarseAdjustOnRangeTooSmall(chatId, symFinal, st, now);
                }
                maybeRequestAutoTuneOnHold(chatId, symFinal, st, reason, now);
                return;
            }
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;

        String ru = holdReasonRu(reason);
        String msg = (ru != null ? (ru + " [" + reason + "]") : ("HOLD: " + reason));

        if (symFinal != null) {
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symFinal, null, Signal.hold(msg)));
        }

        if ("range_too_small".equals(reason)) {
            maybeCoarseAdjustOnRangeTooSmall(chatId, symFinal, st, now);
        }

        maybeRequestAutoTuneOnHold(chatId, symFinal, st, reason, now);
    }

    private String holdReasonRu(String code) {
        if (code == null) return null;

        return switch (code) {
            case "started" -> "Старт стратегии";
            case "no_settings" -> "Нет настроек (StrategySettings/CFG/символ)";
            case "windowSize<5" -> "Размер окна слишком мал (нужно минимум 5)";
            case "warming_up" -> "Прогрев окна (недостаточно тиков)";
            case "window_invalid" -> "Не удалось корректно построить окно (low/high)";
            case "range_zero" -> "Диапазон окна нулевой (high == low)";
            case "range_too_small" -> "Диапазон слишком мал для входа";
            case "pos_invalid" -> "Не удалось вычислить позицию цены в окне";
            case "cooldown" -> "Ожидание после сделки (cooldown)";
            case "tp_sl_pct_invalid" -> "Некорректный TP/SL (проценты должны быть > 0)";
            case "predict_failed" -> "ML-прогноз недоступен";
            case "ml_below_threshold" -> "ML-прогноз ниже порога (вход запрещён)";
            case "entry_failed" -> "Ошибка при входе в сделку";
            case "in_high_zone_wait_tp" -> "Цена у верхней границы — ждём TP";
            case "pos_snapshot_missing" -> "Позиция есть, но нет данных (qty/tp/sl) в PositionStore";
            default -> null;
        };
    }

    // =====================================================
    // KLINE SAFE EXTRACT
    // =====================================================

    private BigDecimal extractClosePriceSafe(UnifiedKline kline) {
        Object v = tryInvokeNoArg(kline, "getClose")
                .or(() -> tryInvokeNoArg(kline, "close"))
                .or(() -> tryInvokeNoArg(kline, "getC"))
                .orElse(null);

        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception ignored) {
        }
        return null;
    }

    private long extractKlineCloseTimeMsSafe(UnifiedKline kline) {
        Object v = tryInvokeNoArg(kline, "getCloseTimeMs")
                .or(() -> tryInvokeNoArg(kline, "getCloseTime"))
                .or(() -> tryInvokeNoArg(kline, "getEndTimeMs"))
                .or(() -> tryInvokeNoArg(kline, "getT"))
                .orElse(null);

        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private Optional<Object> tryInvokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return Optional.ofNullable(m.invoke(target));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    // =====================================================
    // ✅ RESTORE POSITION FROM PositionStore
    // =====================================================

    private void maybeRestorePositionFromStore(Long chatId, LocalState st, String symbol, Instant now) {
        if (chatId == null || st == null) return;

        if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) return;

        String ex = normalizeExchangeOrNull(st.exchange);
        NetworkType net = st.network;
        String sym = normalizeSymbolOrNull(symbol);
        if (sym == null) sym = normalizeSymbolOrNull(st.symbol);

        if (ex == null || net == null || sym == null) return;

        if (!positionStore.isInPosition(chatId, StrategyType.WINDOW_SCALPING, ex, net, sym)) return;

        Optional<PositionStore.PositionSnapshot> opt =
                positionStore.getPosition(chatId, StrategyType.WINDOW_SCALPING, ex, net, sym);

        if (opt.isEmpty()) {
            st.inPosition = false;
            st.isLong = true;

            try {
                positionStore.clearPosition(chatId, StrategyType.WINDOW_SCALPING, ex, net, sym);
            } catch (Exception ignored) {
            }

            pushHoldThrottled(chatId, sym, st, "pos_snapshot_missing", now, Math.max(200, holdThrottleMs));
            if (st.ticks % Math.max(1, tickLogEveryTicks) == 0) {
                log.warn("[WINDOW] ⚠ PositionStore IN_POSITION but snapshot missing => cleared. chatId={} ex={} net={} sym={}",
                        chatId, ex, net, sym);
            }
            return;
        }

        PositionStore.PositionSnapshot snap = opt.get();

        st.inPosition = true;
        st.isLong = true;

        st.entryPrice = (st.entryPrice != null ? st.entryPrice : snap.entryPrice());
        st.entryQty = (st.entryQty != null ? st.entryQty : snap.qty());
        st.tp = (st.tp != null ? st.tp : snap.tp());
        st.sl = (st.sl != null ? st.sl : snap.sl());
        st.entryOrderId = (st.entryOrderId != null ? st.entryOrderId : snap.entryOrderId());

        if (st.lastEntryAt == null) st.lastEntryAt = (snap.openedAt() != null ? snap.openedAt() : now);

        if (st.tp != null && st.sl != null) {
            final String symFinal = sym;
            safeLive(() -> live.pushTpSl(chatId, StrategyType.WINDOW_SCALPING, symFinal, st.tp, st.sl));
        }

        if (st.ticks % Math.max(1, tickLogEveryTicks) == 0) {
            log.info("[WINDOW] ♻️ RESTORED chatId={} sym={} qty={} tp={} sl={} entryPrice={} orderId={}",
                    chatId,
                    sym,
                    st.entryQty != null ? fmtBd(st.entryQty) : "null",
                    st.tp != null ? fmtBd(st.tp) : "null",
                    st.sl != null ? fmtBd(st.sl) : "null",
                    st.entryPrice != null ? fmtBd(st.entryPrice) : "null",
                    st.entryOrderId
            );
        }
    }

    @EventListener
    public void onWindowScalpingSettingsUpdated(WindowScalpingSettingsUpdatedEvent e) {
        LocalState st = states.get(e.chatId());
        if (st == null) return;
        synchronized (st) {
            st.lastSettingsLoadAt = Instant.EPOCH;   // форс refresh
            st.lastFingerprint = null;              // changed=true
            st.lastSsVersion = null;                // сброс версии
            st.lastCfgVersion = null;               // сброс версии
            st.lastVersionCheckAt = Instant.EPOCH;  // форс version check
        }
    }

    @EventListener
    public void onStrategySettingsUpdated(StrategySettingsUpdatedEvent e) {
        if (e == null || e.type() != StrategyType.WINDOW_SCALPING) return;
        LocalState st = states.get(e.chatId());
        if (st == null) return;
        synchronized (st) {
            st.lastSettingsLoadAt = Instant.EPOCH;
            st.lastFingerprint = null;
            st.lastSsVersion = null;
            st.lastCfgVersion = null;
            st.lastVersionCheckAt = Instant.EPOCH;
        }
    }

    // ✅ throttled persist ML confidence (чтобы не спамить БД)
    private void maybePersistMlConfidence(LocalState st, StrategySettings ss, double proba, Instant now) {
        if (st == null || ss == null || now == null) return;
        if (isManualMode(ss)) return;

        // сохраняем не чаще чем раз в 20 секунд
        long minIntervalMs = 20_000L;

        if (st.lastMlConfidenceSaveAt != null) {
            long passed = Duration.between(st.lastMlConfidenceSaveAt, now).toMillis();
            if (passed < minIntervalMs) return;
        }

        // если почти то же самое — не сохраняем
        if (st.lastMlConfidenceSaved != null && Math.abs(st.lastMlConfidenceSaved - proba) < 1e-6) {
            return;
        }

        try {
            ss.setMlConfidence(BigDecimal.valueOf(proba));

            // ✅ важно: сохранить и подменить ссылку, чтобы версия/состояние были актуальны
            StrategySettings saved = strategySettingsService.save(ss);
            if (saved != null) st.ss = saved;

            st.lastMlConfidenceSaveAt = now;
            st.lastMlConfidenceSaved = proba;

        } catch (Exception ignored) {
        }
    }

    // =====================================================
    // UTILS
    // =====================================================

    private static String safeNullable(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? "null" : s;
    }

    private static String normalizeSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchangeOrNull(Object exchange) {
        if (exchange == null) return null;
        String s = (exchange instanceof Enum<?> e) ? e.name() : String.valueOf(exchange);
        s = s.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String safeToStr(Object v) {
        if (v == null) return "null";
        return String.valueOf(v);
    }
}

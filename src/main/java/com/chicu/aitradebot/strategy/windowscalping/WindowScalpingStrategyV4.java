package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    @Value("${strategy.window.tickLogEveryTicks:800}")
    private long tickLogEveryTicks;

    @Value("${strategy.window.holdThrottleMs:2500}")
    private long holdThrottleMs;

    // =====================================================
    // ✅ AUTO MIN-RANGE (полностью автоматический)
    // =====================================================

    @Value("${strategy.window.autoMinRangeEnabled:true}")
    private boolean autoMinRangeEnabled;

    @Value("${strategy.window.autoMinRangeEverySeconds:60}")
    private long autoMinRangeEverySeconds;

    @Value("${strategy.window.autoMinRangeSampleSize:360}")
    private int autoMinRangeSampleSize;

    @Value("${strategy.window.autoMinRangeQuantile:0.30}")
    private double autoMinRangeQuantile;

    @Value("${strategy.window.autoMinRangeMarginFactor:0.90}")
    private double autoMinRangeMarginFactor;

    /** абсолютный минимальный пол (в %), ниже не опускаемся */
    @Value("${strategy.window.autoMinRangeMinFloorPct:0.002}")
    private double autoMinRangeMinFloorPct;

    /** абсолютный максимум (в %), выше не поднимаем */
    @Value("${strategy.window.autoMinRangeMaxCapPct:0.50}")
    private double autoMinRangeMaxCapPct;

    /** минимум наблюдений, прежде чем авто-подстройка начнёт работать */
    @Value("${strategy.window.autoMinRangeMinSamples:60}")
    private int autoMinRangeMinSamples;

    /** насколько должно измениться minRangePct, чтобы сохранять в БД */
    @Value("${strategy.window.autoMinRangeMinDelta:0.0003}")
    private double autoMinRangeMinDelta;

    // =====================================================

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
    // ✅ COARSE-ADJUST
    // =====================================================

    @Value("${strategy.window.coarseAdjustEnabled:true}")
    private boolean coarseAdjustEnabled;

    @Value("${strategy.window.coarseAdjustAfterConsecutive:6}")
    private int coarseAdjustAfterConsecutive;

    @Value("${strategy.window.coarseAdjustCooldownSeconds:120}")
    private long coarseAdjustCooldownSeconds;

    @Value("${strategy.window.coarseAdjustFactor:0.85}")
    private double coarseAdjustFactor;

    /** ✅ просили: минимум не 0.02, а 0.002 */
    @Value("${strategy.window.coarseAdjustMinFloorPct:0.002}")
    private double coarseAdjustMinFloorPct;

    // =====================================================

    private final StrategyLivePublisher live;
    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;

    /** ✅ ML gateway берём лениво */
    private final ObjectProvider<MlGateway> mlGatewayProvider;

    private MlGateway ml() {
        return mlGatewayProvider != null ? mlGatewayProvider.getIfAvailable() : null;
    }

    /** ✅ чтобы не было циклов orchestrator->registry->strategy->orchestrator */
    private final ObjectProvider<AiStrategyOrchestrator> orchestratorProvider;

    private AiStrategyOrchestrator orch() {
        return orchestratorProvider != null ? orchestratorProvider.getIfAvailable() : null;
    }

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    // =====================================================
    // ✅ КЭШИ
    // =====================================================

    private volatile String cachedHoldReasonsRaw;
    private volatile Set<String> cachedHoldReasonsSet;

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        WindowScalpingStrategySettings cfg;

        String symbol;
        String timeframe;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        // ✅ ОКНО ИЗ СВЕЧЕЙ (close)
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

        int consecutiveRangeTooSmall;
        Instant lastCoarseAdjustAt;

        Instant lastMlConfidenceSaveAt;
        Double lastMlConfidenceSaved;

        // ✅ AUTO MIN-RANGE runtime stats
        double[] rangePctSamples;
        int rangePctPtr;
        int rangePctCount;

        Instant lastAutoMinRangeAt;
        Double lastAutoMinRangeApplied;

        // ✅ LIVE: чтобы не спамить одинаковыми зонами/линиями
        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;
        BigDecimal lastBuyZoneTop;
        Instant lastZonePublishedAt;
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

        StrategySettings ss = loadStrategySettingsAuto(chatId, hintEx, network);
        WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.ss = ss;
        st.cfg = cfg;

        st.exchange = normalizeExchangeOrNull(ss != null ? ss.getExchangeName() : hintEx);
        st.network = ss != null ? ss.getNetworkType() : network;

        String sym = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        if (sym == null) sym = normalizeSymbolOrNull(symbolHint);
        st.symbol = sym;

        st.timeframe = normalizeTimeframeOrNull(ss != null ? ss.getTimeframe() : null);

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

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

        st.consecutiveRangeTooSmall = 0;
        st.lastCoarseAdjustAt = null;

        st.lastMlConfidenceSaveAt = null;
        st.lastMlConfidenceSaved = null;

        // ✅ init samples for auto min-range
        int cap = Math.max(50, autoMinRangeSampleSize);
        st.rangePctSamples = new double[cap];
        st.rangePctPtr = 0;
        st.rangePctCount = 0;
        st.lastAutoMinRangeAt = Instant.EPOCH;
        st.lastAutoMinRangeApplied = null;

        st.lastWindowHigh = null;
        st.lastWindowLow = null;
        st.lastBuyZoneTop = null;
        st.lastZonePublishedAt = null;

        states.put(chatId, st);

        ensureRuntimeContext(st, ss);

        AdvancedControlMode mode = modeOrManual(ss);
        boolean gate = (ss != null && ss.isMlGateEnabled() && mode != AdvancedControlMode.MANUAL);
        BigDecimal thrBd = (ss != null ? ss.getGateMinProb() : null);

        log.info("[WINDOW] ▶ START chatId={} ex={} net={} symbol={} tf={} mode={} autoTune={} mlGate={} gateMinProb={} modelVer={} window={} minRange%={} TP%={} SL%={} mlEnabled={} failOpen={} mlMinFallback={} coarseAdjust={} autoMinRange={}",
                chatId,
                fmtEnumOrString(ss != null ? ss.getExchangeName() : st.exchange),
                fmtEnumOrString(ss != null ? ss.getNetworkType() : st.network),
                st.symbol,
                st.timeframe,
                mode,
                (ss != null && ss.isAutoTuneEnabled()),
                gate,
                (thrBd != null ? thrBd.stripTrailingZeros().toPlainString() : "null"),
                (ss != null ? safeNullable(ss.getMlModelVersion()) : "null"),
                cfg != null ? cfg.getWindowSize() : null,
                (cfg != null && cfg.getMinRangePct() != null ? fmt(cfg.getMinRangePct()) : "null"),
                cfg != null ? cfg.getTakeProfitPct() : null,
                cfg != null ? cfg.getStopLossPct() : null,
                mlEnabled,
                mlFailOpen,
                fmt(mlMinProba),
                coarseAdjustEnabled,
                autoMinRangeEnabled
        );

        if (st.symbol != null) {
            final String symFinal = st.symbol;
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, symFinal, true));
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symFinal, null,
                    Signal.hold("Стратегия запущена")));
        }

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

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearTradeZone(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearWindowZone(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, false));
        }

        log.info("[WINDOW] ⏹ STOP chatId={} ex={} net={} symbol={} tf={} ticks={} warmups={} entries={} exits={} inPos={}",
                chatId, st.exchange, st.network, sym, st.timeframe, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
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

    /**
     * ✅ На тиках делаем только EXIT (TP/SL) и live price.
     */
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

        Instant time = (tradeTsMs > 0) ? Instant.ofEpochMilli(tradeTsMs) : Instant.now();

        // обновляем env
        String ex = normalizeExchangeOrNull(exchange);
        if (ex != null) st.exchange = ex;
        if (network != null) st.network = network;

        String tf = normalizeTimeframeOrNull(timeframe);
        if (tf != null) st.timeframe = tf;

        // символ guard
        String incoming = normalizeSymbolOrNull(symbol);
        String current = normalizeSymbolOrNull(st.symbol);
        if (current == null && incoming != null) {
            st.symbol = incoming;
        } else if (current != null && incoming != null && !current.equals(incoming)) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ drop price event due to symbol mismatch chatId={} currentSym={} incomingSym={} ex={} net={}",
                        chatId, current, incoming, ex, network);
            }
            return;
        }

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
                return;
            }

            ensureRuntimeContext(st, ss);

            // восстанавливаем позицию если нужно
            maybeRestorePositionFromStore(chatId, st, sym, time);

            // EXIT только если в позиции
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                if (st.lastEntryAt != null && Duration.between(st.lastEntryAt, time).toMillis() < 500) {
                    return;
                }

                ensureRuntimeContext(st, ss);
                if (st.exchange == null || st.network == null) {
                    pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
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

                        // ✅ маркер выхода
                        safeLive(() -> live.pushTrade(chatId, StrategyType.WINDOW_SCALPING, sym, "SELL", price, st.entryQty, time));

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
                            AiStrategyOrchestrator o = orch();
                            if (o != null) {
                                o.onPositionClosed(chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network);
                            }
                        }

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                                Signal.sell(1.0, "Выход по TP/SL")));
                    }

                } catch (Exception e2) {
                    log.error("[WINDOW] ❌ EXIT error chatId={} sym={} err={}", chatId, sym, e2.getMessage(), e2);
                }
            }
        }
    }

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {
        if (chatId == null) return;
        long tsMs = (ts != null) ? ts.toEpochMilli() : 0L;

        onPriceUpdate(
                chatId.longValue(),
                StrategyType.WINDOW_SCALPING,
                symbolFromTick,
                null,
                price,
                tsMs,
                null,
                null
        );
    }

    /**
     * ✅ На закрытии свечи делаем ОКНО + ENTRY.
     */
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

        BigDecimal close = kline.getClose();
        if (close == null || close.signum() <= 0) return;

        long tsMs = (kline.getCloseTime() > 0 ? kline.getCloseTime() : kline.getOpenTime());
        Instant time = (tsMs > 0) ? Instant.ofEpochMilli(tsMs) : Instant.now();

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        long logEvery = Math.max(1, tickLogEveryTicks);
        long holdMs = Math.max(200, holdThrottleMs);

        // обновляем env
        String ex = normalizeExchangeOrNull(exchange);
        if (ex != null) st.exchange = ex;
        if (network != null) st.network = network;

        String tf = normalizeTimeframeOrNull(timeframe);
        if (tf != null) st.timeframe = tf;

        // символ guard
        String incoming = normalizeSymbolOrNull(symbol);
        String current = normalizeSymbolOrNull(st.symbol);
        if (current == null && incoming != null) {
            st.symbol = incoming;
        } else if (current != null && incoming != null && !current.equals(incoming)) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ drop candle event due to symbol mismatch chatId={} currentSym={} incomingSym={} ex={} net={}",
                        chatId, current, incoming, ex, network);
            }
            return;
        }

        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            WindowScalpingStrategySettings cfg = st.cfg;
            String sym = normalizeSymbolOrNull(st.symbol);

            if (sym == null || cfg == null || ss == null) {
                pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
                diagLogOccasionally(chatId, st, sym, close, "Нет настроек (StrategySettings/CFG/символ).", logEvery, time);
                return;
            }

            ensureRuntimeContext(st, ss);

            // если позиция есть в store — не даём зайти повторно
            maybeRestorePositionFromStore(chatId, st, sym, time);
            if (st.inPosition) return;

            int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
            if (windowSize < 5) {
                pushHoldThrottled(chatId, sym, st, "windowSize<5", time, holdMs);
                return;
            }

            // ✅ окно из свечей
            st.window.addLast(close);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.window.size() < windowSize) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time, holdMs);
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
                return;
            }

            BigDecimal range = high.subtract(low);
            if (range.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "range_zero", time, holdMs);
                return;
            }

            double rangePct = range
                    .divide(low, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            recordRangePctSample(st, rangePct);
            maybeAutoAdjustMinRange(chatId, sym, st, time);

            double minRangePct = (cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0);
            if (rangePct + 1e-12 < minRangePct) {
                pushHoldThrottled(chatId, sym, st, "range_too_small", time, holdMs);
                return;
            }

            st.consecutiveRangeTooSmall = 0;

            double pos = close.subtract(low)
                    .divide(range, 10, RoundingMode.HALF_UP)
                    .doubleValue();

            if (!Double.isFinite(pos)) {
                pushHoldThrottled(chatId, sym, st, "pos_invalid", time, holdMs);
                return;
            }

            double entryLowPct = (cfg.getEntryFromLowPct() != null ? cfg.getEntryFromLowPct() : 0.0);
            double entryHighPct = (cfg.getEntryFromHighPct() != null ? cfg.getEntryFromHighPct() : 0.0);

            double lowZone = clamp01(entryLowPct / 100.0);
            double highZone = clamp01(1.0 - (entryHighPct / 100.0));

            // ✅ LIVE: окно + зона входа (BUY)
            publishZonesThrottled(chatId, st, sym, low, high, range, lowZone, time);

            // ENTRY только на закрытии свечи
            if (pos <= lowZone) {

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

                if (isMlGateAllowed(ss)) {
                    double threshold = resolveMlThreshold(ss);

                    Map<String, Object> feats = buildMlFeatures(
                            chatId, st, sym, close, time,
                            low, high, range, rangePct, pos,
                            lowZone, highZone, windowSize, diffPctForEntry
                    );

                    Prediction pred = tryPredict(feats);

                    if (!pred.ok) {
                        String r = (pred.reason != null ? pred.reason : "predict_failed");
                        if (!mlFailOpen) {
                            pushHoldThrottled(chatId, sym, st, "predict_failed", time, holdMs);
                            return;
                        } else {
                            log.warn("[WINDOW] 🤖 ML FAIL-OPEN chatId={} sym={} reason={}", chatId, sym, r);
                        }
                    } else {
                        maybePersistMlConfidence(st, ss, pred.proba, time);

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
                            close,
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

                    // ✅ TP/SL + линии
                    publishPositionLines(chatId, sym, st);

                    // ✅ маркер входа
                    safeLive(() -> live.pushTrade(chatId, StrategyType.WINDOW_SCALPING, sym, "BUY", st.entryPrice != null ? st.entryPrice : close, st.entryQty, time));

                    safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, sym, null,
                            Signal.buy(score, "Вход у нижней границы окна (по закрытию свечи)")));

                    st.window.clear();
                    st.lastHoldReason = null;
                    st.consecutiveRangeTooSmall = 0;

                } catch (Exception e3) {
                    log.error("[WINDOW] ❌ ENTRY error chatId={} sym={} err={}", chatId, sym, e3.getMessage(), e3);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time, holdMs);
                }
            } else {
                if (pos >= highZone) {
                    pushHoldThrottled(chatId, sym, st, "in_high_zone_wait_tp", time, holdMs);
                }
            }
        }
    }

    private void publishZonesThrottled(Long chatId,
                                       LocalState st,
                                       String sym,
                                       BigDecimal low,
                                       BigDecimal high,
                                       BigDecimal range,
                                       double lowZone01,
                                       Instant now) {

        if (chatId == null || st == null || sym == null) return;
        if (low == null || high == null) return;

        // раз в свечу — но защитимся от спама (например если replay шлёт пачкой)
        if (st.lastZonePublishedAt != null) {
            long ms = Duration.between(st.lastZonePublishedAt, now).toMillis();
            if (ms >= 0 && ms < 400) return;
        }

        boolean sameWindow =
                st.lastWindowHigh != null && st.lastWindowLow != null &&
                st.lastWindowHigh.compareTo(high) == 0 &&
                st.lastWindowLow.compareTo(low) == 0;

        BigDecimal buyTop = null;
        if (range != null && range.signum() > 0) {
            buyTop = low.add(range.multiply(BigDecimal.valueOf(clamp01(lowZone01))))
                    .setScale(Math.max(2, safeScale(low)), RoundingMode.HALF_UP);
        }

        boolean sameBuyTop =
                st.lastBuyZoneTop != null && buyTop != null &&
                st.lastBuyZoneTop.compareTo(buyTop) == 0;

        if (sameWindow && sameBuyTop) return;

        st.lastWindowHigh = high;
        st.lastWindowLow = low;
        st.lastBuyZoneTop = buyTop;
        st.lastZonePublishedAt = now;

        safeLive(() -> live.pushWindowZone(chatId, StrategyType.WINDOW_SCALPING, sym, high, low));

        if (buyTop != null) {
            BigDecimal top = buyTop.max(low);
            BigDecimal bottom = buyTop.min(low);
            safeLive(() -> live.pushTradeZone(chatId, StrategyType.WINDOW_SCALPING, sym, "BUY", top, bottom));
        }
    }

    private int safeScale(BigDecimal price) {
        try {
            int sc = price.scale();
            return Math.max(2, Math.min(8, sc));
        } catch (Exception e) {
            return 4;
        }
    }

    private void publishPositionLines(Long chatId, String sym, LocalState st) {
        if (chatId == null || sym == null || st == null) return;

        if (st.tp != null || st.sl != null) {
            safeLive(() -> live.pushTpSl(chatId, StrategyType.WINDOW_SCALPING, sym, st.tp, st.sl));
        }

        if (st.entryPrice != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.WINDOW_SCALPING, sym, "ENTRY", st.entryPrice));
        }
        if (st.tp != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.WINDOW_SCALPING, sym, "TP", st.tp));
        }
        if (st.sl != null) {
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.WINDOW_SCALPING, sym, "SL", st.sl));
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
    // SETTINGS REFRESH (HOT-UPDATE) — БЕЗ version-check
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        Duration refreshEvery = Duration.ofSeconds(Math.max(1, settingsRefreshSeconds));

        boolean timeDue = (st.lastSettingsLoadAt == null) ||
                          Duration.between(st.lastSettingsLoadAt, now).compareTo(refreshEvery) >= 0;

        if (!timeDue) return;

        try {
            StrategySettings loaded = loadStrategySettingsAuto(chatId, st.exchange, st.network);
            WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = normalizeSymbolOrNull(st.symbol);
            String oldTf = normalizeTimeframeOrNull(st.timeframe);

            if (loaded != null) st.ss = loaded;
            if (cfg != null) st.cfg = cfg;

            if (loaded != null) {
                String loadedSymbol = normalizeSymbolOrNull(loaded.getSymbol());
                if (!st.inPosition && loadedSymbol != null) st.symbol = loadedSymbol;

                String loadedTf = normalizeTimeframeOrNull(loaded.getTimeframe());
                if (!st.inPosition && loadedTf != null) st.timeframe = loadedTf;

                if (loaded.getExchangeName() != null) st.exchange = normalizeExchangeOrNull(loaded.getExchangeName());
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                String newSymbol = normalizeSymbolOrNull(st.symbol);
                String newTf = normalizeTimeframeOrNull(st.timeframe);

                if (!st.inPosition) {
                    boolean symChanged = oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol);
                    boolean tfChanged  = oldTf != null && newTf != null && !oldTf.equals(newTf);

                    if (symChanged || tfChanged) {
                        st.window.clear();
                        st.lastHoldReason = null;
                        st.consecutiveRangeTooSmall = 0;
                        resetRangeSamples(st);

                        st.lastWindowHigh = null;
                        st.lastWindowLow = null;
                        st.lastBuyZoneTop = null;
                        st.lastZonePublishedAt = null;

                        log.info("[WINDOW] 🔄 context changed {}->{} tf {}->{} (not in position) => clear window",
                                oldSymbol, newSymbol, oldTf, newTf);
                    }
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[WINDOW] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    // =====================================================
    // 🔎 Fingerprint helpers
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

    private String buildFingerprint(StrategySettings ss, WindowScalpingStrategySettings cfg) {
        String symbol  = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        String ex      = ss != null ? fmtEnumOrString(ss.getExchangeName()) : "null";
        String net     = ss != null ? fmtEnumOrString(ss.getNetworkType()) : "null";
        String tf      = ss != null ? safeNullable(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String w        = (cfg != null) ? fmtNum(cfg.getWindowSize()) : "null";
        String lowPct   = (cfg != null) ? fmtNum(cfg.getEntryFromLowPct()) : "null";
        String highPct  = (cfg != null) ? fmtNum(cfg.getEntryFromHighPct()) : "null";
        String minRange = (cfg != null) ? fmtNum(cfg.getMinRangePct()) : "null";

        String tpPct = (cfg != null) ? fmtNum(cfg.getTakeProfitPct()) : "null";
        String slPct = (cfg != null) ? fmtNum(cfg.getStopLossPct()) : "null";

        return String.join("|",
                "WINDOW_SCALPING",
                ex, net,
                (symbol != null ? symbol : "null"),
                tf, candles,
                w, lowPct, highPct, minRange, tpPct, slPct
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

        if (st.timeframe == null && ss != null) {
            String tf = normalizeTimeframeOrNull(ss.getTimeframe());
            if (tf != null) st.timeframe = tf;
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
        return coarseAdjustEnabled && ss != null && !isManualMode(ss);
    }

    private boolean isAutoMinRangeAllowed(StrategySettings ss) {
        return autoMinRangeEnabled && ss != null && !isManualMode(ss);
    }

    // =====================================================
    // ✅ AUTO MIN-RANGE CORE
    // =====================================================

    private void resetRangeSamples(LocalState st) {
        if (st == null) return;
        st.rangePctPtr = 0;
        st.rangePctCount = 0;
        st.lastAutoMinRangeAt = Instant.EPOCH;
        st.lastAutoMinRangeApplied = null;
        if (st.rangePctSamples != null) {
            Arrays.fill(st.rangePctSamples, 0.0d);
        }
    }

    private void recordRangePctSample(LocalState st, double rangePct) {
        if (st == null) return;
        if (!Double.isFinite(rangePct)) return;
        if (rangePct <= 0) return;
        if (rangePct > 50) return;
        if (st.rangePctSamples == null || st.rangePctSamples.length == 0) return;

        int i = st.rangePctPtr;
        st.rangePctSamples[i] = rangePct;
        st.rangePctPtr = (i + 1) % st.rangePctSamples.length;
        if (st.rangePctCount < st.rangePctSamples.length) st.rangePctCount++;
    }

    private double quantileFromSamples(LocalState st, double q) {
        if (st == null) return Double.NaN;
        if (st.rangePctCount <= 0) return Double.NaN;
        if (st.rangePctSamples == null) return Double.NaN;

        double qq = q;
        if (!Double.isFinite(qq)) qq = 0.30;
        if (qq < 0) qq = 0;
        if (qq > 1) qq = 1;

        int n = st.rangePctCount;
        double[] tmp = new double[n];

        int cap = st.rangePctSamples.length;
        int start = (st.rangePctPtr - n);
        while (start < 0) start += cap;

        for (int k = 0; k < n; k++) {
            int idx = (start + k) % cap;
            tmp[k] = st.rangePctSamples[idx];
        }

        Arrays.sort(tmp);

        int pos = (int) Math.floor(qq * (n - 1));
        if (pos < 0) pos = 0;
        if (pos >= n) pos = n - 1;
        return tmp[pos];
    }

    private double resolveAutoFloor(LocalState st) {
        double abs = autoMinRangeMinFloorPct;
        if (!Double.isFinite(abs) || abs <= 0) abs = 0.002;

        double p10 = quantileFromSamples(st, 0.10);
        if (!Double.isFinite(p10) || p10 <= 0) return abs;

        double dyn = p10 * 0.80;
        if (!Double.isFinite(dyn) || dyn <= 0) return abs;

        return Math.max(abs, dyn);
    }

    private void maybeAutoAdjustMinRange(Long chatId, String symbol, LocalState st, Instant now) {
        if (chatId == null || st == null || now == null) return;
        if (st.inPosition) return;
        if (!isAutoMinRangeAllowed(st.ss)) return;

        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null) return;

        int need = Math.max(20, autoMinRangeMinSamples);
        if (st.rangePctCount < need) return;

        long everySec = Math.max(15, autoMinRangeEverySeconds);
        if (st.lastAutoMinRangeAt != null) {
            long passed = Duration.between(st.lastAutoMinRangeAt, now).getSeconds();
            if (passed < everySec) return;
        }

        double q = autoMinRangeQuantile;
        if (!Double.isFinite(q)) q = 0.30;
        if (q < 0.05) q = 0.05;
        if (q > 0.80) q = 0.80;

        double base = quantileFromSamples(st, q);
        if (!Double.isFinite(base) || base <= 0) return;

        double margin = autoMinRangeMarginFactor;
        if (!Double.isFinite(margin) || margin <= 0 || margin > 1.0) margin = 0.90;

        double floor = resolveAutoFloor(st);

        double cap = autoMinRangeMaxCapPct;
        if (!Double.isFinite(cap) || cap <= 0) cap = 0.50;
        cap = Math.max(cap, floor);

        double target = base * margin;
        if (!Double.isFinite(target) || target <= 0) return;

        target = Math.max(floor, Math.min(cap, target));

        Double oldObj = cfg.getMinRangePct();
        double old = (oldObj != null && Double.isFinite(oldObj) ? oldObj : 0.0);
        if (old <= 0) old = target;

        double next = old * 0.70 + target * 0.30;

        double eps = autoMinRangeMinDelta;
        if (!Double.isFinite(eps) || eps <= 0) eps = 0.0003;

        if (Math.abs(next - old) < eps) {
            st.lastAutoMinRangeAt = now;
            st.lastAutoMinRangeApplied = old;
            return;
        }

        try {
            WindowScalpingStrategySettings patch = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .minRangePct(next)
                    .build();

            windowSettingsService.update(chatId, patch);

            st.cfg = windowSettingsService.getOrCreate(chatId);
            st.lastFingerprint = buildFingerprint(st.ss, st.cfg);
            st.lastSettingsLoadAt = now;

            st.lastAutoMinRangeAt = now;
            st.lastAutoMinRangeApplied = next;

            log.info("[WINDOW] 🧩 AUTO-MIN-RANGE chatId={} sym={} tf={} minRangePct {} -> {} (target={} baseQ{}={} floor={} cap={} n={})",
                    chatId,
                    symbol,
                    st.timeframe,
                    fmt(old),
                    fmt(next),
                    fmt(target),
                    fmt(q),
                    fmt(base),
                    fmt(floor),
                    fmt(cap),
                    st.rangePctCount
            );

        } catch (Exception e) {
            st.lastAutoMinRangeAt = now;
            log.warn("[WINDOW] ⚠️ AUTO-MIN-RANGE failed chatId={} sym={} err={}", chatId, symbol, e.toString());
        }
    }

    // =====================================================
    // ✅ COARSE-ADJUST
    // =====================================================

    private void maybeCoarseAdjustOnRangeTooSmall(Long chatId, String symbol, LocalState st, Instant now) {
        if (!coarseAdjustEnabled) return;
        if (chatId == null || st == null || now == null) return;
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
        if (!Double.isFinite(factor) || factor <= 0 || factor >= 1.0) factor = 0.85;

        double floor;
        if (autoMinRangeEnabled) {
            floor = resolveAutoFloor(st);
        } else {
            floor = coarseAdjustMinFloorPct;
            if (!Double.isFinite(floor) || floor <= 0) floor = 0.002;
        }

        Double oldMinObj = cfg.getMinRangePct();
        double oldMin = (oldMinObj != null && Double.isFinite(oldMinObj)) ? oldMinObj : 0.0;
        if (oldMin <= 0) oldMin = Math.max(floor, 0.10);

        double newMin = Math.max(floor, oldMin * factor);
        if (Math.abs(newMin - oldMin) < 1e-12) return;

        try {
            WindowScalpingStrategySettings patch = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .minRangePct(newMin)
                    .build();

            windowSettingsService.update(chatId, patch);

            st.cfg = windowSettingsService.getOrCreate(chatId);
            st.lastFingerprint = buildFingerprint(st.ss, st.cfg);
            st.lastSettingsLoadAt = now;

            st.window.clear();
            st.consecutiveRangeTooSmall = 0;
            st.lastCoarseAdjustAt = now;

            log.warn("[WINDOW] 🛠️ COARSE-ADJUST chatId={} sym={} tf={} minRangePct {} -> {} (after={} cooldown={}s floor={})",
                    chatId, symbol, st.timeframe, fmt(oldMin), fmt(newMin), afterN, cd, fmt(floor));

        } catch (Exception e) {
            log.warn("[WINDOW] ⚠️ COARSE-ADJUST failed chatId={} sym={} err={}", chatId, symbol, e.toString());
        }
    }

    // =====================================================
    // ML
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
                if (Double.isFinite(v) && v > 0) return v;
            }
        }
        return mlMinProba;
    }

    private Prediction tryPredict(Map<String, Object> features) {
        try {
            MlGateway gw = ml();
            if (gw == null) return Prediction.fail("ml_gateway_missing");
            if (!mlEnabled) return Prediction.fail("ml_disabled_by_strategy");

            MlPredictResponse r = gw.predict(features);
            if (r == null) return Prediction.fail("predict_null");

            if (!r.isOk()) {
                return Prediction.fail(r.getError() != null ? r.getError() : "predict_not_ok");
            }

            Double p = r.getProba();
            if (p == null || !Double.isFinite(p)) return Prediction.fail("no_proba");

            double proba = Math.max(0.0, Math.min(1.0, p));
            String mk = (r.getModelKey() != null && !r.getModelKey().isBlank())
                    ? r.getModelKey()
                    : (r.getModelVersion() != null && !r.getModelVersion().isBlank() ? r.getModelVersion() : "unknown");

            return Prediction.ok(mk, proba);

        } catch (Exception e) {
            return Prediction.fail("predict_exception:" + e.getClass().getSimpleName());
        }
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
        f.put("strategyType", StrategyType.WINDOW_SCALPING.name());
        f.put("symbol", symbol);
        f.put("exchange", st.exchange);
        f.put("network", st.network != null ? st.network.name() : null);
        f.put("timeframe", st.timeframe);
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

    // =====================================================
    // HOLD / AUTOTUNE hooks
    // =====================================================

    private void safeLive(Runnable r) {
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

        AiStrategyOrchestrator o = orch();
        if (o == null) return;

        log.warn("[WINDOW] 🧠 AUTO-TUNE(HOLD) chatId={} sym={} reason={} cooldown={}s",
                chatId, symbol, reason, cdSec);

        o.triggerTuneDebounced(
                chatId,
                StrategyType.WINDOW_SCALPING,
                st.exchange,
                st.network,
                "hold:" + reason,
                Duration.ofSeconds(cdSec)
        );
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
            case "warming_up" -> "Прогрев окна (недостаточно свечей)";
            case "window_invalid" -> "Не удалось корректно построить окно (low/high)";
            case "range_zero" -> "Диапазон окна нулевой (high == low)";
            case "range_too_small" -> "Диапазон слишком мал для входа";
            case "pos_invalid" -> "Не удалось вычислить позицию цены в окне";
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

        publishPositionLines(chatId, sym, st);
    }

    @EventListener
    public void onWindowScalpingSettingsUpdated(WindowScalpingSettingsUpdatedEvent e) {
        LocalState st = states.get(e.chatId());
        if (st == null) return;
        synchronized (st) {
            st.lastSettingsLoadAt = Instant.EPOCH;
            st.lastFingerprint = null;
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
        }
    }

    private void maybePersistMlConfidence(LocalState st, StrategySettings ss, double proba, Instant now) {
        if (st == null || ss == null || now == null) return;
        if (isManualMode(ss)) return;

        long minIntervalMs = 20_000L;

        if (st.lastMlConfidenceSaveAt != null) {
            long passed = Duration.between(st.lastMlConfidenceSaveAt, now).toMillis();
            if (passed < minIntervalMs) return;
        }

        if (st.lastMlConfidenceSaved != null && Math.abs(st.lastMlConfidenceSaved - proba) < 1e-6) {
            return;
        }

        try {
            ss.setMlConfidence(BigDecimal.valueOf(proba));
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

    private static String normalizeTimeframeOrNull(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase(Locale.ROOT);
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
}
package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleEntity;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleIngestService;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.events.WindowScalpingSettingsUpdatedEvent;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.InMemoryPositionStoreImpl;
import com.chicu.aitradebot.trade.TradeExecutionServiceImpl;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.ZoneId;
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

    @Value("${strategy.window.entryBlockLogThrottleMs:30000}")
    private long entryBlockLogThrottleMs;

    @Value("${strategy.window.entryBlockSummaryLogThrottleMs:120000}")
    private long entryBlockSummaryLogThrottleMs;

    @Value("${strategy.window.restoreRetryCooldownMs:5000}")
    private long restoreRetryCooldownMs;

    @Value("${strategy.window.dustRestoreLogThrottleMs:60000}")
    private long dustRestoreLogThrottleMs;

    @Value("${strategy.window.positionSyncMinIntervalMs:10000}")
    private long positionSyncMinIntervalMs;

    @Value("${strategy.window.zoneExitEnabled:true}")
    private boolean zoneExitEnabled;

    @Value("${strategy.window.zoneExitMinNetProfitPct:0.08}")
    private double zoneExitMinNetProfitPct;

    @Value("${strategy.window.zoneExitMinHoldMs:1500}")
    private long zoneExitMinHoldMs;

    @Value("${strategy.window.zoneExitRequireMomentumFade:false}")
    private boolean zoneExitRequireMomentumFade;

    @Value("${strategy.window.zoneExitMomentumCeilingPct:0.03}")
    private double zoneExitMomentumCeilingPct;


    @Value("${strategy.defaults.exchange:BINANCE}")
    private String defaultExchange;

    @Value("${strategy.defaults.network:TESTNET}")
    private String defaultNetwork;

    @Value("${strategy.defaults.symbol:BTCUSDT}")
    private String defaultSymbol;

    @Value("${strategy.defaults.timeframe:1m}")
    private String defaultTimeframe;

    // =====================================================
    // AUTO MIN-RANGE
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

    @Value("${strategy.window.autoMinRangeMinFloorPct:0.002}")
    private double autoMinRangeMinFloorPct;

    @Value("${strategy.window.autoMinRangeMaxCapPct:0.50}")
    private double autoMinRangeMaxCapPct;

    @Value("${strategy.window.autoMinRangeMinSamples:60}")
    private int autoMinRangeMinSamples;

    @Value("${strategy.window.autoMinRangeMinDelta:0.0003}")
    private double autoMinRangeMinDelta;

    // =====================================================
    // ML gate
    // =====================================================

    @Value("${strategy.window.mlEnabled:true}")
    private boolean mlEnabled;

    @Value("${strategy.window.mlFailOpen:true}")
    private boolean mlFailOpen;

    @Value("${strategy.window.mlMinProba:0.60}")
    private double mlMinProba;

    @Value("${strategy.window.mlPredictThrottleMs:1200}")
    private long mlPredictThrottleMs;

    @Value("${strategy.window.mlLogThrottleMs:30000}")
    private long mlLogThrottleMs;

    @Value("${strategy.window.mlBelowThresholdCooldownMs:2500}")
    private long mlBelowThresholdCooldownMs;

    @Value("${strategy.window.mlRecheckMinPriceMovePct:0.03}")
    private double mlRecheckMinPriceMovePct;

    @Value("${strategy.window.adaptiveMlGateEnabled:true}")
    private boolean adaptiveMlGateEnabled;

    @Value("${strategy.window.adaptiveMlGateOnlyHybrid:true}")
    private boolean adaptiveMlGateOnlyHybrid;

    @Value("${strategy.window.adaptiveMlGateSampleSize:64}")
    private int adaptiveMlGateSampleSize;

    @Value("${strategy.window.adaptiveMlGateQuantile:0.85}")
    private double adaptiveMlGateQuantile;

    @Value("${strategy.window.adaptiveMlGateMarginPct:0.03}")
    private double adaptiveMlGateMarginPct;

    @Value("${strategy.window.adaptiveMlGateFloor:0.18}")
    private double adaptiveMlGateFloor;

    @Value("${strategy.window.adaptiveMlGateMaxThresholdDrop:0.35}")
    private double adaptiveMlGateMaxThresholdDrop;

    @Value("${strategy.window.adaptiveMlGateMinStrongScore:55.0}")
    private double adaptiveMlGateMinStrongScore;

    @Value("${strategy.window.mlAutoTuneOnLowConfidence:false}")
    private boolean mlAutoTuneOnLowConfidence;

    @Value("${strategy.window.mlAutoTuneLowConfidenceAfter:8}")
    private int mlAutoTuneLowConfidenceAfter;

    // =====================================================
    // AUTO-TUNE ON HOLD
    // =====================================================

    @Value("${strategy.window.autoTuneOnHold:false}")
    private boolean autoTuneOnHold;

    @Value("${strategy.window.autoTuneHoldCooldownSeconds:60}")
    private long autoTuneHoldCooldownSeconds;

    @Value("${strategy.window.autoTuneHoldReasons:range_too_small,windowSize<5,no_settings,window_invalid,range_zero,pos_invalid}")
    private String autoTuneHoldReasons;

    // =====================================================
    // COARSE-ADJUST
    // =====================================================

    @Value("${strategy.window.coarseAdjustEnabled:true}")
    private boolean coarseAdjustEnabled;

    @Value("${strategy.window.coarseAdjustAfterConsecutive:6}")
    private int coarseAdjustAfterConsecutive;

    @Value("${strategy.window.coarseAdjustCooldownSeconds:120}")
    private long coarseAdjustCooldownSeconds;

    @Value("${strategy.window.coarseAdjustFactor:0.85}")
    private double coarseAdjustFactor;

    @Value("${strategy.window.coarseAdjustMinFloorPct:0.002}")
    private double coarseAdjustMinFloorPct;

    // =====================================================
    // NOISE FILTER / RISK
    // =====================================================

    @Value("${strategy.window.noiseFilterEnabled:true}")
    private boolean noiseFilterEnabled;

    @Value("${strategy.window.entryBounceMinPosPctOfRange:2.5}")
    private double entryBounceMinPosPctOfRange;

    @Value("${strategy.window.minMomentum1Pct:0.002}")
    private double minMomentum1Pct;

    @Value("${strategy.window.minVolatilityPct:0.006}")
    private double minVolatilityPct;

    @Value("${strategy.window.minWindowReturnPct:-0.12}")
    private double minWindowReturnPct;

    @Value("${strategy.window.minEntryScoreToEnter:10.0}")
    private double minEntryScoreToEnter;

    @Value("${strategy.window.minEntryScoreWithoutMl:16.0}")
    private double minEntryScoreWithoutMl;

    @Value("${strategy.window.minRoomToWindowHighPct:0.05}")
    private double minRoomToWindowHighPct;

    @Value("${strategy.window.tpRoomUsagePct:0.96}")
    private double tpRoomUsagePct;

    @Value("${strategy.window.minRoomNetRewardRisk:0.70}")
    private double minRoomNetRewardRisk;

    @Value("${strategy.window.dynamicTpEnabled:true}")
    private boolean dynamicTpEnabled;

    @Value("${strategy.window.dynamicTpMinPct:0.20}")
    private double dynamicTpMinPct;

    @Value("${strategy.window.dynamicTpMaxPct:0.80}")
    private double dynamicTpMaxPct;

    @Value("${strategy.window.dynamicSlMinPct:0.08}")
    private double dynamicSlMinPct;

    @Value("${strategy.window.dynamicSlMaxPct:0.25}")
    private double dynamicSlMaxPct;

    @Value("${strategy.window.dynamicTpFromRangeFactor:0.90}")
    private double dynamicTpFromRangeFactor;

    @Value("${strategy.window.dynamicSlFromRangeFactor:0.45}")
    private double dynamicSlFromRangeFactor;

    @Value("${strategy.window.dynamicMinRiskReward:1.55}")
    private double dynamicMinRiskReward;

    @Value("${strategy.window.minProfitAfterFeesPct:0.03}")
    private double minProfitAfterFeesPct;

    @Value("${strategy.window.breakEvenEnabled:true}")
    private boolean breakEvenEnabled;

    @Value("${strategy.window.breakEvenTriggerToTpRatio:0.30}")
    private double breakEvenTriggerToTpRatio;

    @Value("${strategy.window.breakEvenOffsetPct:0.06}")
    private double breakEvenOffsetPct;

    @Value("${strategy.window.breakEvenFeeCoverBufferPct:0.08}")
    private double breakEvenFeeCoverBufferPct;

    @Value("${strategy.window.breakEvenProtectedNetBufferPct:0.03}")
    private double breakEvenProtectedNetBufferPct;

    @Value("${strategy.window.postSlCooldownMs:90000}")
    private long postSlCooldownMs;

    @Value("${strategy.window.postSlCooldownCandles:2}")
    private int postSlCooldownCandles;

    @Value("${strategy.window.postTpCooldownMs:5000}")
    private long postTpCooldownMs;

    @Value("${strategy.window.autoMinRangeProtectRaiseSeconds:180}")
    private long autoMinRangeProtectRaiseSeconds;

    @Value("${strategy.window.lowZoneDescendingLookback:4}")
    private int lowZoneDescendingLookback;

    @Value("${strategy.window.minReboundAfterLowTouchPct:0.015}")
    private double minReboundAfterLowTouchPct;

    @Value("${strategy.window.autoMinRangeBlockRaiseOnEntryRejectSeconds:180}")
    private long autoMinRangeBlockRaiseOnEntryRejectSeconds;

    @Value("${strategy.window.autoMinRangeMaxRaiseStepRatio:0.08}")
    private double autoMinRangeMaxRaiseStepRatio;

    @Value("${strategy.window.windowHighProjectionPctOfRange:0.35}")
    private double windowHighProjectionPctOfRange;

    @Value("${strategy.window.minNetRewardRiskForMarket:1.35}")
    private double minNetRewardRiskForMarket;

    private final StrategyLivePublisher live;
    private final WindowScalpingStrategySettingsService windowSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;
    private final OrderRepository orderRepository;
    private final MlSampleIngestService mlSampleIngestService;
    private final ObjectMapper objectMapper;

    private final ObjectProvider<MlGateway> mlGatewayProvider;
    private final ObjectProvider<AiStrategyOrchestrator> orchestratorProvider;

    private MlGateway ml() {
        return mlGatewayProvider != null ? mlGatewayProvider.getIfAvailable() : null;
    }

    private AiStrategyOrchestrator orch() {
        return orchestratorProvider != null ? orchestratorProvider.getIfAvailable() : null;
    }

    private TradeExecutionServiceImpl tradeExecImpl() {
        return (tradeExecutionService instanceof TradeExecutionServiceImpl impl) ? impl : null;
    }

    private static final BigDecimal MIN_RESTORABLE_NOTIONAL = new BigDecimal("10.50");

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

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

        Deque<BigDecimal> window = new ArrayDeque<>();
        Deque<BigDecimal> tickWindow = new ArrayDeque<>();
        Instant lastTickAt;

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;

        Instant lastTradeClosedAt;
        Instant reentryBlockedUntil;
        String reentryBlockReason;
        Instant lastEntryAt;
        Long entryCandleOpenTimeMs;
        String entryCandleTimeframe;

        Instant lastRestoreProbeAt;
        Instant lastRestoreMissAt;
        Instant lastDustRestoreLogAt;
        String lastDustRestoreLogKey;
        Instant lastPositionSyncAt;

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

        double[] rangePctSamples;
        int rangePctPtr;
        int rangePctCount;

        Instant lastAutoMinRangeAt;
        Double lastAutoMinRangeApplied;
        Instant lastAutoMinRangeRaisedAt;
        Double lastAutoMinRangeRaisedFrom;
        Double lastAutoMinRangeRaisedTo;

        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;
        BigDecimal lastBuyZoneTop;
        Instant lastZonePublishedAt;

        Instant lastMlPredictAt;
        BigDecimal lastMlPredictPrice;
        Prediction lastMlPrediction;

        Instant lastMlBelowThresholdAt;
        BigDecimal lastMlBelowThresholdPrice;
        Double lastMlBelowThresholdProba;
        Double lastMlBelowThresholdThreshold;
        int consecutiveMlBelowThreshold;

        double[] mlProbaSamples;
        int mlProbaPtr;
        int mlProbaCount;
        Double lastAdaptiveMlThreshold;
        Instant lastAdaptiveMlLogAt;

        Instant lastMlWarnAt;
        String lastMlWarnReason;

        Instant lastEntryBlockedAt;
        String lastEntryBlockedReason;
        Instant lastEntryBlockedSummaryAt;
        String lastEntryBlockedSummaryReason;
        long lastEntryBlockedRepeatCount;

        Map<String, Object> pendingMlFeatures;
        Instant pendingMlFeatureTs;
        String pendingMlSymbol;
        String pendingMlExchange;
        String pendingMlNetwork;
        String pendingMlTimeframe;
        BigDecimal pendingMlEntryPrice;
        Double pendingMlProba;
        String pendingMlModelKey;
        boolean pendingMlFailOpen;
    }

    private static class Prediction {
        final boolean ok;
        final String modelKey;
        final String modelVersion;
        final String schemaHash;
        final double proba;
        final String reason;

        private Prediction(boolean ok,
                           String modelKey,
                           String modelVersion,
                           String schemaHash,
                           double proba,
                           String reason) {
            this.ok = ok;
            this.modelKey = modelKey;
            this.modelVersion = modelVersion;
            this.schemaHash = schemaHash;
            this.proba = proba;
            this.reason = reason;
        }

        static Prediction ok(String modelKey, String modelVersion, String schemaHash, double proba) {
            return new Prediction(true, modelKey, modelVersion, schemaHash, proba, null);
        }

        static Prediction fail(String reason) {
            return new Prediction(false, null, null, null, 0.0, reason);
        }
    }

    private record EntryRisk(BigDecimal tpPct, BigDecimal slPct, String source) {}

    private static final class OpenLot {
        private BigDecimal qty;
        private final BigDecimal price;
        private final Long orderId;
        private final Instant openedAt;

        private OpenLot(BigDecimal qty, BigDecimal price, Long orderId, Instant openedAt) {
            this.qty = qty;
            this.price = price;
            this.orderId = orderId;
            this.openedAt = openedAt;
        }
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

        st.exchange = firstNonBlankExchange(
                hintEx,
                ss != null ? ss.getExchangeName() : null,
                defaultExchange
        );
        st.network = firstNonNullNetwork(
                network,
                ss != null ? ss.getNetworkType() : null,
                parseNetworkOrNull(defaultNetwork)
        );

        String sym = firstNonBlankSymbol(
                symbolHint,
                ss != null ? ss.getSymbol() : null,
                defaultSymbol
        );
        st.symbol = sym;

        st.timeframe = firstNonBlankTimeframe(
                ss != null ? ss.getTimeframe() : null,
                defaultTimeframe
        );

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

        st.window.clear();
        st.tickWindow.clear();
        st.lastTickAt = null;

        st.inPosition = false;
        st.isLong = true;

        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.entryQty = null;
        st.entryOrderId = null;

        st.lastTradeClosedAt = null;
        st.reentryBlockedUntil = null;
        st.reentryBlockReason = null;
        st.lastEntryAt = null;
        st.entryCandleOpenTimeMs = null;
        st.entryCandleTimeframe = null;
        st.lastRestoreProbeAt = null;
        st.lastRestoreMissAt = null;
        st.lastDustRestoreLogAt = null;
        st.lastDustRestoreLogKey = null;
        st.lastPositionSyncAt = null;

        st.lastHoldReason = null;
        st.lastHoldAt = null;

        st.lastDiagAt = null;
        st.lastAutoTuneRequestAt = null;

        st.consecutiveRangeTooSmall = 0;
        st.lastCoarseAdjustAt = null;

        st.lastMlConfidenceSaveAt = null;
        st.lastMlConfidenceSaved = null;

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

        resetMlCache(st);
        clearPendingMlSample(st);
        clearMlBelowThreshold(st);
        resetAdaptiveMlGateState(st);
        st.lastMlWarnAt = null;
        st.lastMlWarnReason = null;
        st.lastEntryBlockedAt = null;
        st.lastEntryBlockedReason = null;

        states.put(chatId, st);

        ensureRuntimeContext(st, ss);

        AdvancedControlMode mode = modeOrManual(ss);
        boolean gate = (ss != null && ss.isMlGateEnabled() && mode != AdvancedControlMode.MANUAL);
        BigDecimal thrBd = (ss != null ? ss.getGateMinProb() : null);

        log.info("[WINDOW] ▶ Старт chatId={} ex={} net={} symbol={} tf={} mode={} autoTune={} mlGate={} gateMinProb={} modelVer={} window={} minRange%={} TP%={} SL%={} mlEnabled={} failOpen={} mlMinFallback={} coarseAdjust={} autoMinRange={}",
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

        if (adaptiveMlGateEnabled && gate) {
            log.info("[WINDOW] 🤖 Adaptive ML gate enabled chatId={} sym={} baseThreshold={} floor={} quantile={} margin={} strongScore>= {}",
                    chatId,
                    st.symbol,
                    (thrBd != null ? thrBd.stripTrailingZeros().toPlainString() : fmt(mlMinProba)),
                    fmt(adaptiveMlGateFloor),
                    fmt(adaptiveMlGateQuantile),
                    fmt(adaptiveMlGateMarginPct),
                    fmt(adaptiveMlGateMinStrongScore));
        }

        if (st.symbol != null) {
            final String symFinal = st.symbol;
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, symFinal, true));
            safeLive(() -> live.pushSignal(chatId, StrategyType.WINDOW_SCALPING, symFinal, null,
                    Signal.hold("Стратегия запущена")));
        }

        if (st.symbol != null) {
            synchronized (st) {
                Instant now = Instant.now();
                maybeRestorePositionFromStore(chatId, st, st.symbol, now);
                if (st.inPosition && st.exchange != null && st.network != null && shouldSyncRuntimePosition(st, now)) {
                    syncRuntimePositionWithExchangeBalance(chatId, st, st.exchange, st.network, st.symbol, now);
                    st.lastPositionSyncAt = now;
                }
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
        st.entryCandleOpenTimeMs = null;
        st.entryCandleTimeframe = null;
        clearPendingMlSample(st);

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearTradeZone(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.clearWindowZone(chatId, StrategyType.WINDOW_SCALPING, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.WINDOW_SCALPING, sym, false));
        }

        log.info("[WINDOW] ⏹ Стоп chatId={} ex={} net={} symbol={} tf={} ticks={} warmups={} entries={} exits={} inPos={}",
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
                log.warn("[WINDOW] ⚠ Некорректная цена chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (tradeTsMs > 0) ? Instant.ofEpochMilli(tradeTsMs) : Instant.now();

        String ex = normalizeExchangeOrNull(exchange);
        if (ex != null) st.exchange = ex;
        if (network != null) st.network = network;

        String tf = normalizeTimeframeOrNull(timeframe);
        if (tf != null) st.timeframe = tf;

        String incoming = normalizeSymbolOrNull(symbol);
        String current = normalizeSymbolOrNull(st.symbol);

        if (current == null && incoming != null) {
            st.symbol = incoming;
        } else if (current != null && incoming != null && !current.equals(incoming)) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ Пропуск price event из-за несовпадения символа chatId={} currentSym={} incomingSym={} ex={} net={}",
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

            maybeRestorePositionFromStore(chatId, st, sym, time);

            if (st.inPosition && st.exchange != null && st.network != null && shouldSyncRuntimePosition(st, time)) {
                syncRuntimePositionWithExchangeBalance(chatId, st, st.exchange, st.network, sym, time);
                st.lastPositionSyncAt = time;
                if (!st.inPosition || st.entryQty == null || st.entryPrice == null) {
                    pushHoldThrottled(chatId, sym, st, "post_restore_sync", time, holdMs);
                    return;
                }
            }

            updateTickWindow(st, price, cfg);

            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                if (isSameCandleAsEntry(st, time)) {
                    pushHoldThrottled(chatId, sym, st, "same_candle_exit_blocked", time, holdMs);
                    return;
                }

                if (st.lastEntryAt != null && Duration.between(st.lastEntryAt, time).toMillis() < 500) {
                    return;
                }

                maybeMoveStopToBreakEven(chatId, st, sym, price, time);

                ensureRuntimeContext(st, ss);
                if (st.exchange == null || st.network == null) {
                    pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
                    return;
                }

                try {
                    var zoneExit = evaluateHighZoneExit(chatId, st, sym, price, time);
                    TradeExecutionServiceImpl execImpl = tradeExecImpl();

                    String executedExitMode = null;
                    var exRes = (zoneExit.shouldExit() && execImpl != null)
                            ? execImpl.executeExitNow(
                                    chatId,
                                    StrategyType.WINDOW_SCALPING,
                                    sym,
                                    price,
                                    time,
                                    st.entryQty,
                                    st.tp,
                                    st.sl,
                                    st.exchange,
                                    st.network,
                                    zoneExit.reason()
                            )
                            : tradeExecutionService.executeExitIfHit(
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

                    if (zoneExit.shouldExit() && execImpl != null) {
                        executedExitMode = zoneExit.reason();
                    }

                    String exitReasonCode = safeNullable(exRes.reason());

                    if (!exRes.executed() && (
                            "sell_not_fully_filled".equals(exitReasonCode)
                            || "partial_exit".equals(exitReasonCode)
                            || "sell_not_filled".equals(exitReasonCode)
                            || "balance".equals(exitReasonCode)
                            || "position_sync_mismatch".equals(exitReasonCode)
                    )) {
                        log.warn("[WINDOW] ⚠ Выход не завершил полное закрытие chatId={} sym={} reason={} oldQty={} -> восстанавливаю позицию из PositionStore",
                                chatId,
                                sym,
                                exitReasonCode,
                                fmtBd(st.entryQty));

                        clearLocalPosition(st);
                        maybeRestorePositionFromStore(chatId, st, sym, time);
                        resetMlCache(st);

                        String holdCode = "partial_exit".equals(exitReasonCode)
                                ? "sell_not_fully_filled"
                                : exitReasonCode;
                        pushHoldThrottled(chatId, sym, st, holdCode, time, holdMs);
                        return;
                    }

                    if (!exRes.executed() && (
                            "dust_position".equals(exitReasonCode)
                            || "min_notional".equals(exitReasonCode)
                            || "lot_step".equals(exitReasonCode)
                    )) {
                        log.warn("[WINDOW] 🧹 Очищаю локальную позицию после невосстанавливаемого EXIT chatId={} sym={} reason={} qty={} entry={} tp={} sl={}",
                                chatId,
                                sym,
                                exitReasonCode,
                                fmtBd(st.entryQty),
                                fmtBd(st.entryPrice),
                                fmtBd(st.tp),
                                fmtBd(st.sl));

                        clearLocalPosition(st);
                        clearPendingMlSample(st);
                        st.lastTradeClosedAt = time;
                        st.reentryBlockedUntil = time.plusMillis(Math.max(1_500L, postTpCooldownMs));
                        st.reentryBlockReason = "post_exit_cooldown";
                        st.lastEntryAt = null;
                        st.entryCandleOpenTimeMs = null;
                        st.entryCandleTimeframe = null;
                        resetMlCache(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        pushHoldThrottled(chatId, sym, st, "restored_dust_position", time, holdMs);
                        return;
                    }

                    if (exRes.executed()) {
                        st.exits++;

                        if (executedExitMode == null) {
                            executedExitMode = exRes.slHit() ? "SL" : "TP";
                        }

                        BigDecimal actualExitPrice = positiveOrNull(exRes.exitPrice());
                        if (actualExitPrice == null) {
                            actualExitPrice = price;
                        }

                        BigDecimal realizedPnlPct = exRes.pnlPct();

                        persistClosedTradeSample(
                                chatId,
                                st,
                                sym,
                                actualExitPrice,
                                realizedPnlPct,
                                time
                        );

                        BigDecimal tradePriceForUi = actualExitPrice;
                        safeLive(() -> live.pushTrade(
                                chatId,
                                StrategyType.WINDOW_SCALPING,
                                sym,
                                "SELL",
                                tradePriceForUi,
                                st.entryQty,
                                time
                        ));

                        st.inPosition = false;
                        st.entryQty = null;
                        st.entryOrderId = null;
                        st.entryPrice = null;
                        st.tp = null;
                        st.sl = null;

                        st.lastTradeClosedAt = time;
                        long extraCooldownMs = exRes.slHit() ? resolvePostSlCooldownMs(st) : Math.max(1_500L, postTpCooldownMs);
                        st.reentryBlockedUntil = time.plusMillis(extraCooldownMs);
                        st.reentryBlockReason = exRes.slHit()
                                ? "post_sl_cooldown"
                                : ("ZONE_HIGH_EXIT".equals(executedExitMode) ? "post_zone_exit_cooldown" : "post_exit_cooldown");
                        st.lastEntryAt = null;
                        st.entryCandleOpenTimeMs = null;
                        st.entryCandleTimeframe = null;

                        resetMlCache(st);
                        ensureRuntimeContext(st, ss);

                        if (st.exchange != null && st.network != null && isAutoTuneAllowed(ss)) {
                            AiStrategyOrchestrator o = orch();
                            if (o != null) {
                                o.onPositionClosed(chatId, StrategyType.WINDOW_SCALPING, st.exchange, st.network);
                            }
                        }

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));

                        String signalText = "ZONE_HIGH_EXIT".equals(executedExitMode)
                                ? "Выход по верхней зоне окна"
                                : (exRes.slHit() ? "Выход по SL" : "Выход по TP");

                        safeLive(() -> live.pushSignal(
                                chatId,
                                StrategyType.WINDOW_SCALPING,
                                sym,
                                null,
                                Signal.sell(1.0, signalText)
                        ));
                    }

                } catch (Exception e2) {
                    log.error("[WINDOW] ❌ Ошибка выхода chatId={} sym={} err={}", chatId, sym, e2.getMessage(), e2);
                }

                return;
            }

            evaluateEntryOnTick(chatId, st, sym, price, time, holdMs);
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

    private void evaluateEntryOnTick(Long chatId,
                                     LocalState st,
                                     String sym,
                                     BigDecimal price,
                                     Instant time,
                                     long holdMs) {

        if (chatId == null || st == null || sym == null || price == null || price.signum() <= 0) return;
        if (st.inPosition) return;

        StrategySettings ss = st.ss;
        WindowScalpingStrategySettings cfg = st.cfg;

        if (ss == null || cfg == null) {
            pushHoldThrottled(chatId, sym, st, "no_settings", time, holdMs);
            return;
        }

        int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
        if (windowSize < 5) {
            pushHoldThrottled(chatId, sym, st, "windowSize<5", time, holdMs);
            return;
        }

        if (st.tickWindow == null || st.tickWindow.size() < windowSize) {
            st.warmups++;
            pushHoldThrottled(chatId, sym, st, "warming_up", time, holdMs);
            return;
        }

        BigDecimal high = null;
        BigDecimal low = null;
        for (BigDecimal p : st.tickWindow) {
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

        cfg = st.cfg != null ? st.cfg : cfg;

        double minRangePct = (cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0);
        if (rangePct + 1e-12 < minRangePct) {
            pushHoldThrottled(chatId, sym, st, "range_too_small", time, holdMs);
            return;
        }

        st.consecutiveRangeTooSmall = 0;

        BigDecimal clampedPrice = price;
        if (clampedPrice.compareTo(low) < 0) clampedPrice = low;
        if (clampedPrice.compareTo(high) > 0) clampedPrice = high;

        double pos = clampedPrice.subtract(low)
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

        publishZonesThrottled(chatId, st, sym, low, high, range, lowZone, time);

        Deque<BigDecimal> src = (st.tickWindow != null && !st.tickWindow.isEmpty())
                ? st.tickWindow
                : st.window;

        double retWindowPct = calcWindowReturnPct(src);
        double momentum1 = calcMomentum1Pct(src, price);
        double volatilityPct = calcVolatilityPct(st);

        if (st.reentryBlockedUntil != null) {
            if (time.isBefore(st.reentryBlockedUntil)) {
                String holdReason = safeNullable(st.reentryBlockReason);
                if (holdReason == null) holdReason = "post_exit_cooldown";
                pushHoldThrottled(chatId, sym, st, holdReason, time, holdMs);
                return;
            }
            st.reentryBlockedUntil = null;
            st.reentryBlockReason = null;
        }

        if (st.lastTradeClosedAt != null) {
            long ms = Duration.between(st.lastTradeClosedAt, time).toMillis();
            if (ms >= 0 && ms < 1200) {
                pushHoldThrottled(chatId, sym, st, "post_exit_cooldown", time, holdMs);
                return;
            }
        }

        if (st.lastEntryAt != null) {
            long ms = Duration.between(st.lastEntryAt, time).toMillis();
            if (ms >= 0 && ms < 1200) {
                pushHoldThrottled(chatId, sym, st, "post_entry_cooldown", time, holdMs);
                return;
            }
        }

        if (pos <= lowZone) {

            double minBouncePos = Math.min(
                    lowZone * 0.45,
                    Math.max(0.005, entryBounceMinPosPctOfRange / 100.0)
            );

            if (noiseFilterEnabled) {
                if (pos < minBouncePos) {
                    pushHoldThrottled(chatId, sym, st, "falling_knife", time, holdMs);
                    return;
                }

                if (momentum1 < minMomentum1Pct) {
                    pushHoldThrottled(chatId, sym, st, "negative_micro_momentum", time, holdMs);
                    return;
                }

                if (retWindowPct < minWindowReturnPct) {
                    pushHoldThrottled(chatId, sym, st, "trend_down_too_strong", time, holdMs);
                    return;
                }

                if (volatilityPct < minVolatilityPct) {
                    pushHoldThrottled(chatId, sym, st, "micro_chop", time, holdMs);
                    return;
                }
            }

            final double score = clamp01(
                    (lowZone <= 0.000001) ? 1.0 : (1.0 - (pos / lowZone))
            ) * 100.0;

            BigDecimal diffPctForEntry = BigDecimal.valueOf(Math.max(0.000001, (lowZone - pos) * 100.0));

            if (noiseFilterEnabled && isWeakLowZoneRebound(src, price)) {
                double reboundPct = calcReboundFromRecentLowPct(src, price, Math.max(2, lowZoneDescendingLookback));
                double effectiveMinRebound = Math.min(
                        Math.max(0.004, minReboundAfterLowTouchPct),
                        0.012
                );
                if (reboundPct + 1e-12 < effectiveMinRebound) {
                    logEntryBlockedThrottled(
                            chatId,
                            sym,
                            st,
                            "weak_low_zone_rebound",
                            price,
                            diffPctForEntry,
                            time
                    );
                    pushHoldThrottled(chatId, sym, st, "weak_low_zone_rebound", time, holdMs);
                    return;
                }
            }

            double effectiveMinEntryScore = minEntryScoreToEnter;
            if (Double.isFinite(effectiveMinEntryScore) && effectiveMinEntryScore > 0.0) {
                double relaxFactor = (volatilityPct >= Math.max(minVolatilityPct, 0.010) ? 0.75 : 0.85);
                if (pos <= Math.max(0.02, lowZone * 0.55)) {
                    relaxFactor = Math.min(relaxFactor, 0.65);
                }
                effectiveMinEntryScore = Math.max(5.0, effectiveMinEntryScore * relaxFactor);
            }

            if (Double.isFinite(effectiveMinEntryScore) && score + 1e-12 < effectiveMinEntryScore) {
                logEntryBlockedThrottled(
                        chatId,
                        sym,
                        st,
                        "weak_low_zone_touch",
                        price,
                        diffPctForEntry,
                        time
                );
                pushHoldThrottled(chatId, sym, st, "weak_low_zone_touch", time, holdMs);
                return;
            }

            BigDecimal tpPct;
            BigDecimal slPct;

            TradeExecutionServiceImpl execImpl = tradeExecImpl();
            if (execImpl != null) {
                TradeExecutionServiceImpl.EntryPrecheckResult precheck = execImpl.precheckEntryFast(
                        chatId,
                        StrategyType.WINDOW_SCALPING,
                        sym,
                        price,
                        ss,
                        time
                );

                if (!precheck.allowed()) {
                    logEntryBlockedThrottled(
                            chatId,
                            sym,
                            st,
                            precheck.code(),
                            price,
                            diffPctForEntry,
                            time
                    );
                    pushHoldThrottled(chatId, sym, st, precheck.code(), time, holdMs);
                    return;
                }
            }

            if (isMlGateAllowed(ss) && shouldSkipMlRecheckAfterBelowThreshold(st, price, time)) {
                pushHoldThrottled(chatId, sym, st, "ml_below_threshold", time, holdMs);
                return;
            }

            Map<String, Object> entryFeatures = buildMlFeatures(
                    chatId, st, sym, price, time,
                    low, high, range, rangePct, pos,
                    lowZone, highZone, windowSize, diffPctForEntry
            );

            Prediction pred = null;
            boolean mlBypassForThisEntry = false;
            BigDecimal previousMlConfidence = ss.getMlConfidence();

            if (isMlGateAllowed(ss)) {
                Map<String, Object> feats = entryFeatures;

                pred = getPredictionThrottled(chatId, sym, st, feats, price, time);

                if (!pred.ok) {
                    String reason = normalizeMlFailureReason(pred.reason);

                    if (!isMlFailOpenAllowed(ss, reason)) {
                        logMlBlockedThrottled(chatId, sym, st, ss, reason, time);
                        pushHoldThrottled(chatId, sym, st, mapMlFailureToHoldReason(ss, reason), time, holdMs);
                        return;
                    }

                    mlBypassForThisEntry = true;
                    logMlFailureThrottled(chatId, sym, st, ss, reason, time);

                    double effectiveFailOpenScore = Double.isFinite(minEntryScoreWithoutMl)
                            ? Math.max(8.0, minEntryScoreWithoutMl * 0.80)
                            : minEntryScoreWithoutMl;

                    if (Double.isFinite(effectiveFailOpenScore) && score + 1e-12 < effectiveFailOpenScore) {
                        logEntryBlockedThrottled(
                                chatId,
                                sym,
                                st,
                                "weak_low_zone_touch_fail_open",
                                price,
                                diffPctForEntry,
                                time
                        );
                        pushHoldThrottled(chatId, sym, st, "weak_low_zone_touch_fail_open", time, holdMs);
                        return;
                    }
                } else {
                    maybePersistMlConfidence(st, ss, pred.proba, time);
                    recordMlProbaSample(st, pred.proba);

                    double threshold = resolveEffectiveMlThreshold(ss, st, score);

                    if (pred.proba + 1e-12 < threshold) {
                        rememberMlBelowThreshold(st, price, pred.proba, threshold, time);
                        st.consecutiveMlBelowThreshold++;
                        maybeRequestAutoTuneOnLowConfidence(chatId, sym, st, ss, pred.proba, threshold, time);
                        logMlBelowThresholdThrottled(chatId, sym, st, pred, threshold, time);
                        pushHoldThrottled(chatId, sym, st, "ml_below_threshold", time, holdMs);
                        return;
                    }

                    clearMlBelowThreshold(st);
                    st.consecutiveMlBelowThreshold = 0;
                }
            }

            EntryRisk entryRisk = resolveEntryRisk(st, rangePct, score, pred);
            if (entryRisk == null || entryRisk.tpPct() == null || entryRisk.slPct() == null) {
                pushHoldThrottled(chatId, sym, st, "tp_sl_pct_invalid", time, holdMs);
                return;
            }

            entryRisk = alignRiskToWindowRoom(chatId, st, price, high, entryRisk);
            if (entryRisk == null || entryRisk.tpPct() == null || entryRisk.slPct() == null) {
                logEntryBlockedThrottled(
                        chatId,
                        sym,
                        st,
                        "no_room_to_high",
                        price,
                        diffPctForEntry,
                        time
                );
                pushHoldThrottled(chatId, sym, st, "no_room_to_high", time, holdMs);
                return;
            }

            tpPct = entryRisk.tpPct();
            slPct = entryRisk.slPct();

            try {
                if (isMlGateAllowed(ss)) {
                    if (pred != null && pred.ok) {
                        ss.setMlConfidence(BigDecimal.valueOf(pred.proba));
                    } else {
                        ss.setMlConfidence(null);
                    }
                }

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
                    logEntryBlockedThrottled(
                            chatId,
                            sym,
                            st,
                            safeNullable(res.reason()),
                            price,
                            diffPctForEntry,
                            time
                    );
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
                rememberEntryCandle(st, time);
                st.lastPositionSyncAt = time;
                resetMlCache(st);

                ensureRuntimeContext(st, ss);

                rememberPendingMlSample(
                        st,
                        entryFeatures,
                        time,
                        sym,
                        st.exchange,
                        st.network,
                        st.timeframe,
                        st.entryPrice != null ? st.entryPrice : price,
                        (pred != null && pred.ok) ? pred.proba : null,
                        (pred != null && pred.ok) ? pred.modelKey : null,
                        mlBypassForThisEntry
                );

                publishPositionLines(chatId, sym, st);

                safeLive(() -> live.pushTrade(
                        chatId,
                        StrategyType.WINDOW_SCALPING,
                        sym,
                        "BUY",
                        st.entryPrice != null ? st.entryPrice : price,
                        st.entryQty,
                        time
                ));

                safeLive(() -> live.pushSignal(
                        chatId,
                        StrategyType.WINDOW_SCALPING,
                        sym,
                        null,
                        Signal.buy(score, "Вход у нижней границы окна (по тику)")
                ));

                log.info("[WINDOW] ✅ Вход chatId={} sym={} price={} qty={} tp={} sl={} tpPct={} slPct={} riskSource={} score={} mlProba={} modelKey={} modelVer={} schemaHash={}{}",
                        chatId,
                        sym,
                        fmtBd(st.entryPrice != null ? st.entryPrice : price),
                        fmtBd(st.entryQty),
                        fmtBd(st.tp),
                        fmtBd(st.sl),
                        fmtBd(tpPct),
                        fmtBd(slPct),
                        entryRisk.source(),
                        fmt(score),
                        (pred != null && pred.ok) ? fmt(pred.proba) : "null",
                        (pred != null && pred.ok) ? safeNullable(pred.modelKey) : null,
                        (pred != null && pred.ok) ? safeNullable(pred.modelVersion) : null,
                        (pred != null && pred.ok) ? safeNullable(pred.schemaHash) : null,
                        (mlBypassForThisEntry ? " mlBypass=true" : "")
                );

                st.lastHoldReason = null;
                st.consecutiveRangeTooSmall = 0;
                return;

            } catch (Exception e3) {
                log.error("[WINDOW] ❌ Ошибка входа chatId={} sym={} err={}", chatId, sym, e3.getMessage(), e3);
                pushHoldThrottled(chatId, sym, st, "entry_failed", time, holdMs);
                return;
            } finally {
                if (isMlGateAllowed(ss)) {
                    ss.setMlConfidence(previousMlConfidence);
                }
            }
        }

        if (pos >= highZone) {
            pushHoldThrottled(chatId, sym, st, "in_high_zone_wait_tp", time, holdMs);
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

        String ex = normalizeExchangeOrNull(exchange);
        if (ex != null) st.exchange = ex;
        if (network != null) st.network = network;

        String tf = normalizeTimeframeOrNull(timeframe);
        if (tf != null) st.timeframe = tf;

        String incoming = normalizeSymbolOrNull(symbol);
        String current = normalizeSymbolOrNull(st.symbol);

        if (current == null && incoming != null) {
            st.symbol = incoming;
        } else if (current != null && incoming != null && !current.equals(incoming)) {
            if (st.ticks % logEvery == 0) {
                log.warn("[WINDOW] ⚠ Пропуск candle event из-за несовпадения символа chatId={} currentSym={} incomingSym={} ex={} net={}",
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
                return;
            }

            ensureRuntimeContext(st, ss);
            maybeRestorePositionFromStore(chatId, st, sym, time);

            int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
            if (windowSize < 5) {
                pushHoldThrottled(chatId, sym, st, "windowSize<5", time, holdMs);
                return;
            }

            st.window.addLast(close);
            while (st.window.size() > windowSize) st.window.removeFirst();

            if (st.tickWindow == null) {
                st.tickWindow = new ArrayDeque<>();
            }
            if (st.tickWindow.size() < windowSize) {
                st.tickWindow.addLast(close);
                while (st.tickWindow.size() > windowSize) st.tickWindow.removeFirst();
            }

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

            cfg = st.cfg != null ? st.cfg : cfg;

            double entryLowPct = (cfg.getEntryFromLowPct() != null ? cfg.getEntryFromLowPct() : 0.0);
            double lowZone = clamp01(entryLowPct / 100.0);

            publishZonesThrottled(chatId, st, sym, low, high, range, lowZone, time);

            if (st.ticks % logEvery == 0) {
                log.info("[WINDOW] 🕯 Свеча chatId={} sym={} tf={} close={} window={} tickWindow={} rangePct={} minRangePct={}",
                        chatId,
                        sym,
                        st.timeframe,
                        fmtBd(close),
                        st.window.size(),
                        st.tickWindow != null ? st.tickWindow.size() : 0,
                        fmt(rangePct),
                        fmt(cfg.getMinRangePct() != null ? cfg.getMinRangePct() : 0.0));
            }
        }
    }

    private void updateTickWindow(LocalState st,
                                  BigDecimal price,
                                  WindowScalpingStrategySettings cfg) {
        if (st == null || price == null || price.signum() <= 0 || cfg == null) return;

        int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
        if (windowSize < 5) return;

        if (st.tickWindow == null) {
            st.tickWindow = new ArrayDeque<>();
        }

        st.tickWindow.addLast(price);
        while (st.tickWindow.size() > windowSize) {
            st.tickWindow.removeFirst();
        }

        st.lastTickAt = Instant.now();
    }

    private record ZoneExitDecision(boolean shouldExit,
                                    String reason,
                                    BigDecimal netPnlPct,
                                    double pos,
                                    double highZone) {
        private static ZoneExitDecision no() {
            return new ZoneExitDecision(false, null, null, Double.NaN, Double.NaN);
        }

        private static ZoneExitDecision yes(String reason,
                                            BigDecimal netPnlPct,
                                            double pos,
                                            double highZone) {
            return new ZoneExitDecision(true, reason, netPnlPct, pos, highZone);
        }
    }

    private ZoneExitDecision evaluateHighZoneExit(Long chatId,
                                                  LocalState st,
                                                  String sym,
                                                  BigDecimal price,
                                                  Instant time) {
        if (!zoneExitEnabled) return ZoneExitDecision.no();
        if (chatId == null || st == null || sym == null || price == null || time == null) return ZoneExitDecision.no();
        if (!st.inPosition) return ZoneExitDecision.no();
        if (st.entryPrice == null || st.entryPrice.signum() <= 0) return ZoneExitDecision.no();
        if (st.exchange == null || st.network == null) return ZoneExitDecision.no();
        if (st.tp != null && price.compareTo(st.tp) >= 0) return ZoneExitDecision.no();

        long minHoldMs = Math.max(500L, zoneExitMinHoldMs);
        if (st.lastEntryAt != null) {
            long heldMs = Duration.between(st.lastEntryAt, time).toMillis();
            if (heldMs >= 0 && heldMs < minHoldMs) {
                return ZoneExitDecision.no();
            }
        }

        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null) return ZoneExitDecision.no();

        int windowSize = (cfg.getWindowSize() != null ? cfg.getWindowSize() : 0);
        if (windowSize < 5) return ZoneExitDecision.no();
        if (st.tickWindow == null || st.tickWindow.size() < windowSize) return ZoneExitDecision.no();

        BigDecimal high = null;
        BigDecimal low = null;
        for (BigDecimal p : st.tickWindow) {
            if (p == null) continue;
            high = (high == null) ? p : high.max(p);
            low = (low == null) ? p : low.min(p);
        }

        if (high == null || low == null || low.signum() <= 0) return ZoneExitDecision.no();

        BigDecimal range = high.subtract(low);
        if (range.signum() <= 0) return ZoneExitDecision.no();

        BigDecimal clampedPrice = price;
        if (clampedPrice.compareTo(low) < 0) clampedPrice = low;
        if (clampedPrice.compareTo(high) > 0) clampedPrice = high;

        double pos = clampedPrice.subtract(low)
                .divide(range, 10, RoundingMode.HALF_UP)
                .doubleValue();
        if (!Double.isFinite(pos)) return ZoneExitDecision.no();

        double entryHighPct = (cfg.getEntryFromHighPct() != null ? cfg.getEntryFromHighPct() : 0.0);
        double highZone = clamp01(1.0 - (entryHighPct / 100.0));
        if (pos + 1e-12 < highZone) return ZoneExitDecision.no();

        TradeExecutionServiceImpl exec = tradeExecImpl();
        if (exec == null) return ZoneExitDecision.no();

        BigDecimal netPnlPct = exec.estimateNetPnlPct(chatId, st.exchange, st.network, st.entryPrice, price);
        if (netPnlPct == null || netPnlPct.signum() <= 0) return ZoneExitDecision.no();

        BigDecimal minNetProfitPct = pctBd(Math.max(0.0, zoneExitMinNetProfitPct));
        if (netPnlPct.compareTo(minNetProfitPct) < 0) return ZoneExitDecision.no();

        if (zoneExitRequireMomentumFade) {
            Deque<BigDecimal> src = (st.tickWindow != null && !st.tickWindow.isEmpty()) ? st.tickWindow : st.window;
            double momentum1 = calcMomentum1Pct(src, price);
            if (momentum1 > zoneExitMomentumCeilingPct) {
                return ZoneExitDecision.no();
            }
        }

        return ZoneExitDecision.yes("ZONE_HIGH_EXIT", netPnlPct, pos, highZone);
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

    // =====================================================
    // SETTINGS REFRESH
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
                if (!st.inPosition && st.symbol == null && loadedSymbol != null) st.symbol = loadedSymbol;

                String loadedTf = normalizeTimeframeOrNull(loaded.getTimeframe());
                if (!st.inPosition && st.timeframe == null && loadedTf != null) st.timeframe = loadedTf;

                if (st.exchange == null && loaded.getExchangeName() != null) {
                    st.exchange = normalizeExchangeOrNull(loaded.getExchangeName());
                }
                if (st.network == null && loaded.getNetworkType() != null) {
                    st.network = loaded.getNetworkType();
                }
            }

            ensureRuntimeContext(st, loaded);

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                String newSymbol = normalizeSymbolOrNull(st.symbol);
                String newTf = normalizeTimeframeOrNull(st.timeframe);

                if (!st.inPosition) {
                    boolean symChanged = oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol);
                    boolean tfChanged = oldTf != null && newTf != null && !oldTf.equals(newTf);

                    if (symChanged || tfChanged) {
                        st.window.clear();
                        st.tickWindow.clear();
                        st.lastTickAt = null;

                        st.lastHoldReason = null;
                        st.consecutiveRangeTooSmall = 0;
                        resetRangeSamples(st);
                        resetMlCache(st);
                        resetAdaptiveMlGateState(st);

                        st.lastWindowHigh = null;
                        st.lastWindowLow = null;
                        st.lastBuyZoneTop = null;
                        st.lastZonePublishedAt = null;
                        resetRestoreThrottleState(st);

                        log.info("[WINDOW] 🔄 Контекст изменился {}->{} tf {}->{} (вне позиции) => очищаю окна",
                                oldSymbol, newSymbol, oldTf, newTf);
                    }
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[WINDOW] ⚠ Не удалось обновить настройки chatId={} msg={}", chatId, e.toString());
        }
    }

    // =====================================================
    // FINGERPRINT
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
        String symbol = ss != null ? normalizeSymbolOrNull(ss.getSymbol()) : null;
        String ex = ss != null ? fmtEnumOrString(ss.getExchangeName()) : "null";
        String net = ss != null ? fmtEnumOrString(ss.getNetworkType()) : "null";
        String tf = ss != null ? safeNullable(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String w = (cfg != null) ? fmtNum(cfg.getWindowSize()) : "null";
        String lowPct = (cfg != null) ? fmtNum(cfg.getEntryFromLowPct()) : "null";
        String highPct = (cfg != null) ? fmtNum(cfg.getEntryFromHighPct()) : "null";
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
    // SETTINGS LOAD
    // =====================================================

    private StrategySettings loadStrategySettingsAuto(Long chatId, String exchange, NetworkType network) {
        if (chatId == null) return null;

        try {
            return strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING);
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ Не удалось загрузить StrategySettings chatId={} err={}", chatId, e.toString());
            return null;
        }
    }

    // =====================================================
    // CONTEXT HELPERS
    // =====================================================

    private void ensureRuntimeContext(LocalState st, StrategySettings ss) {
        if (st == null) return;

        st.exchange = firstNonBlankExchange(
                st.exchange,
                ss != null ? ss.getExchangeName() : null,
                defaultExchange
        );

        st.network = firstNonNullNetwork(
                st.network,
                ss != null ? ss.getNetworkType() : null,
                parseNetworkOrNull(defaultNetwork)
        );

        st.symbol = firstNonBlankSymbol(
                st.symbol,
                ss != null ? ss.getSymbol() : null,
                defaultSymbol
        );

        st.timeframe = firstNonBlankTimeframe(
                st.timeframe,
                ss != null ? ss.getTimeframe() : null,
                defaultTimeframe
        );
    }

    private static String firstNonBlankExchange(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeExchangeOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static String firstNonBlankSymbol(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeSymbolOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static String firstNonBlankTimeframe(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeTimeframeOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static NetworkType firstNonNullNetwork(NetworkType... values) {
        if (values == null) return null;
        for (NetworkType v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static NetworkType parseNetworkOrNull(String value) {
        String s = normalizeExchangeOrNull(value);
        if (s == null) return null;
        try {
            return NetworkType.valueOf(s);
        } catch (Exception ignored) {
            return null;
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

    private static boolean isHybridMode(StrategySettings ss) {
        return modeOrManual(ss) == AdvancedControlMode.HYBRID;
    }

    private static boolean isAiMode(StrategySettings ss) {
        return modeOrManual(ss) == AdvancedControlMode.AI;
    }

    private boolean isAutoTuneAllowed(StrategySettings ss) {
        return ss != null && ss.isAutoTuneEnabled() && !isManualMode(ss);
    }

    private boolean isMlGateAllowed(StrategySettings ss) {
        // PROD: локальный ML gate в стратегии отключён.
        // Единственная точка принятия ML-решения по входу теперь находится в TradeExecutionServiceImpl,
        // чтобы не было дубля логики между стратегией и торговым слоем.
        return false;
    }

    private boolean isCoarseAdjustAllowed(StrategySettings ss) {
        return coarseAdjustEnabled && ss != null && !isManualMode(ss);
    }

    private boolean isAutoMinRangeAllowed(StrategySettings ss) {
        return autoMinRangeEnabled && ss != null && !isManualMode(ss);
    }

    // =====================================================
    // AUTO MIN-RANGE
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

        if (target > old && shouldSkipAutoRaiseAfterEntryReject(st, now)) {
            return;
        }

        double next = old * 0.70 + target * 0.30;
        if (target > old) {
            double stepRatio = autoMinRangeMaxRaiseStepRatio;
            if (!Double.isFinite(stepRatio) || stepRatio <= 0.0) {
                stepRatio = 0.08;
            }
            double cappedMax = old * (1.0 + stepRatio);
            if (Double.isFinite(cappedMax) && cappedMax > 0.0) {
                next = Math.min(next, cappedMax);
            }
        }

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

            if (next > old + eps) {
                st.lastAutoMinRangeRaisedAt = now;
                st.lastAutoMinRangeRaisedFrom = old;
                st.lastAutoMinRangeRaisedTo = next;
            }

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
            log.warn("[WINDOW] ⚠ AUTO-MIN-RANGE не выполнен chatId={} sym={} err={}", chatId, symbol, e.toString());
        }
    }

    // =====================================================
    // COARSE-ADJUST
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

        if (isProtectedAfterRecentAutoRaise(st, now)) {
            return;
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
            st.tickWindow.clear();
            st.lastTickAt = null;

            st.consecutiveRangeTooSmall = 0;
            st.lastCoarseAdjustAt = now;
            resetMlCache(st);

            log.warn("[WINDOW] 🛠 COARSE-ADJUST chatId={} sym={} tf={} minRangePct {} -> {} (after={} cooldown={}s floor={})",
                    chatId, symbol, st.timeframe, fmt(oldMin), fmt(newMin), afterN, cd, fmt(floor));

        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ COARSE-ADJUST не выполнен chatId={} sym={} err={}", chatId, symbol, e.toString());
        }
    }

    private long resolvePostSlCooldownMs(LocalState st) {
        long baseMs = Math.max(5_000L, postSlCooldownMs);
        int candles = Math.max(1, postSlCooldownCandles);

        String tf = normalizeTimeframeOrNull(st != null ? st.timeframe : null);
        long tfMs = timeframeToMillis(tf);
        if (tfMs <= 0) {
            return baseMs;
        }

        long candleBlockMs = tfMs * candles;
        if (candleBlockMs <= 0) {
            return baseMs;
        }

        return Math.max(baseMs, candleBlockMs);
    }

    private boolean isProtectedAfterRecentAutoRaise(LocalState st, Instant now) {
        if (st == null || now == null) return false;
        if (st.lastAutoMinRangeRaisedAt == null) return false;

        long protectSec = Math.max(60L, autoMinRangeProtectRaiseSeconds);
        long ageSec = Duration.between(st.lastAutoMinRangeRaisedAt, now).getSeconds();
        if (ageSec < 0) return true;
        if (ageSec > protectSec) return false;

        double from = st.lastAutoMinRangeRaisedFrom != null ? st.lastAutoMinRangeRaisedFrom : 0.0;
        double to = st.lastAutoMinRangeRaisedTo != null ? st.lastAutoMinRangeRaisedTo : from;

        return to > from + Math.max(0.0003, autoMinRangeMinDelta);
    }

    private boolean shouldSkipAutoRaiseAfterEntryReject(LocalState st, Instant now) {
        if (st == null || now == null) return false;
        if (st.lastEntryBlockedAt == null || st.lastEntryBlockedReason == null) return false;

        long protectSec = Math.max(60L, autoMinRangeBlockRaiseOnEntryRejectSeconds);
        long ageSec = Duration.between(st.lastEntryBlockedAt, now).getSeconds();
        if (ageSec < 0) return true;
        if (ageSec > protectSec) return false;

        return switch (st.lastEntryBlockedReason) {
            case "weak_low_zone_touch",
                 "weak_low_zone_rebound",
                 "weak_low_zone_touch_fail_open",
                 "no_room_to_high" -> true;
            default -> false;
        };
    }

    private boolean isWeakLowZoneRebound(Deque<BigDecimal> src, BigDecimal currentPrice) {
        if (src == null || src.size() < 3 || currentPrice == null || currentPrice.signum() <= 0) return false;

        int lookback = Math.max(2, Math.min(6, lowZoneDescendingLookback));
        if (!areLastNClosesDescending(src, lookback)) return false;

        double reboundPct = calcReboundFromRecentLowPct(src, currentPrice, lookback);
        double minBouncePct = minReboundAfterLowTouchPct;
        if (!Double.isFinite(minBouncePct) || minBouncePct < 0.0) {
            minBouncePct = 0.03;
        }

        return reboundPct + 1e-12 < minBouncePct;
    }

    private boolean areLastNClosesDescending(Deque<BigDecimal> src, int lookback) {
        if (src == null || src.size() < 3) return false;

        int need = Math.max(2, lookback);
        List<BigDecimal> tail = lastNValues(src, need);
        if (tail.size() < need) return false;

        for (int i = 1; i < tail.size(); i++) {
            BigDecimal prev = tail.get(i - 1);
            BigDecimal cur = tail.get(i);
            if (prev == null || cur == null) return false;
            if (cur.compareTo(prev) >= 0) return false;
        }
        return true;
    }

    private double calcReboundFromRecentLowPct(Deque<BigDecimal> src, BigDecimal currentPrice, int lookback) {
        if (src == null || currentPrice == null || currentPrice.signum() <= 0) return 0.0;

        int need = Math.max(2, lookback);
        List<BigDecimal> tail = lastNValues(src, need);
        if (tail.isEmpty()) return 0.0;

        BigDecimal recentLow = null;
        for (BigDecimal v : tail) {
            if (v == null || v.signum() <= 0) continue;
            recentLow = recentLow == null ? v : recentLow.min(v);
        }

        if (recentLow == null || recentLow.signum() <= 0) return 0.0;

        return currentPrice.subtract(recentLow)
                .divide(recentLow, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private List<BigDecimal> lastNValues(Deque<BigDecimal> src, int n) {
        List<BigDecimal> out = new ArrayList<>(Math.max(0, n));
        if (src == null || n <= 0) return out;

        Iterator<BigDecimal> it = src.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        Collections.reverse(out);
        return out;
    }

    // =====================================================
    // ML
    // =====================================================

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

    private void resetAdaptiveMlGateState(LocalState st) {
        if (st == null) return;

        int cap = Math.max(16, adaptiveMlGateSampleSize);
        st.mlProbaSamples = new double[cap];
        st.mlProbaPtr = 0;
        st.mlProbaCount = 0;
        st.lastAdaptiveMlThreshold = null;
        st.lastAdaptiveMlLogAt = null;
        st.consecutiveMlBelowThreshold = 0;
    }

    private void recordMlProbaSample(LocalState st, double proba) {
        if (st == null) return;
        if (!Double.isFinite(proba)) return;
        if (proba < 0.0 || proba > 1.0) return;

        if (st.mlProbaSamples == null || st.mlProbaSamples.length != Math.max(16, adaptiveMlGateSampleSize)) {
            resetAdaptiveMlGateState(st);
        }

        int i = st.mlProbaPtr;
        st.mlProbaSamples[i] = proba;
        st.mlProbaPtr = (i + 1) % st.mlProbaSamples.length;
        if (st.mlProbaCount < st.mlProbaSamples.length) {
            st.mlProbaCount++;
        }
    }

    private double quantileFromMlProbaSamples(LocalState st, double q) {
        if (st == null || st.mlProbaSamples == null || st.mlProbaCount <= 0) return Double.NaN;

        double qq = q;
        if (!Double.isFinite(qq)) qq = 0.85;
        if (qq < 0.0) qq = 0.0;
        if (qq > 1.0) qq = 1.0;

        int n = st.mlProbaCount;
        double[] tmp = new double[n];

        int cap = st.mlProbaSamples.length;
        int start = st.mlProbaPtr - n;
        while (start < 0) start += cap;

        for (int k = 0; k < n; k++) {
            int idx = (start + k) % cap;
            tmp[k] = st.mlProbaSamples[idx];
        }

        Arrays.sort(tmp);
        int pos = (int) Math.floor(qq * (n - 1));
        if (pos < 0) pos = 0;
        if (pos >= n) pos = n - 1;
        return tmp[pos];
    }

    private double resolveEffectiveMlThreshold(StrategySettings ss, LocalState st, double score) {
        double baseThreshold = resolveMlThreshold(ss);

        if (!adaptiveMlGateEnabled || st == null) {
            return baseThreshold;
        }

        if (adaptiveMlGateOnlyHybrid && !isHybridMode(ss)) {
            return baseThreshold;
        }

        double strongScore = adaptiveMlGateMinStrongScore;
        if (!Double.isFinite(strongScore)) strongScore = 55.0;
        if (score + 1e-12 < strongScore) {
            return baseThreshold;
        }

        int minSamples = Math.max(8, Math.min(16, Math.max(16, adaptiveMlGateSampleSize) / 4));
        if (st.mlProbaCount < minSamples) {
            return baseThreshold;
        }

        double q = adaptiveMlGateQuantile;
        if (!Double.isFinite(q)) q = 0.85;
        if (q < 0.50) q = 0.50;
        if (q > 0.98) q = 0.98;

        double qValue = quantileFromMlProbaSamples(st, q);
        if (!Double.isFinite(qValue)) {
            return baseThreshold;
        }

        double margin = adaptiveMlGateMarginPct;
        if (!Double.isFinite(margin) || margin < 0.0) margin = 0.03;

        double maxDrop = adaptiveMlGateMaxThresholdDrop;
        if (!Double.isFinite(maxDrop) || maxDrop <= 0.0) maxDrop = 0.35;

        double floor = adaptiveMlGateFloor;
        if (!Double.isFinite(floor) || floor <= 0.0) floor = 0.18;

        floor = Math.max(floor, baseThreshold - maxDrop);
        floor = Math.min(floor, baseThreshold);

        double adaptive = qValue + margin;
        adaptive = Math.max(floor, adaptive);
        adaptive = Math.min(baseThreshold, adaptive);

        if (!Double.isFinite(adaptive) || adaptive <= 0.0) {
            return baseThreshold;
        }

        st.lastAdaptiveMlThreshold = adaptive;
        return adaptive;
    }

    private static final class ParsedMlPredictResponse {
        final boolean ok;
        final Double proba;
        final String error;
        final String modelKey;
        final String modelVersion;
        final String schemaHash;

        private ParsedMlPredictResponse(boolean ok,
                                        Double proba,
                                        String error,
                                        String modelKey,
                                        String modelVersion,
                                        String schemaHash) {
            this.ok = ok;
            this.proba = proba;
            this.error = error;
            this.modelKey = modelKey;
            this.modelVersion = modelVersion;
            this.schemaHash = schemaHash;
        }
    }

    private Prediction tryPredict(Long chatId,
                                  String symbol,
                                  Instant now,
                                  Map<String, Object> features) {
        try {
            MlGateway gw = ml();
            if (gw == null) return Prediction.fail("ml_gateway_missing");
            if (!mlEnabled) return Prediction.fail("ml_disabled_by_strategy");

            MlPredictResponse r = gw.predict(
                    StrategyType.WINDOW_SCALPING,
                    chatId,
                    symbol,
                    features,
                    now
            );

            if (r == null) return Prediction.fail("predict_null");

            ParsedMlPredictResponse parsed = parsePredictResponse(r);

            if (!parsed.ok) {
                return Prediction.fail(parsed.error != null ? parsed.error : "predict_not_ok");
            }

            Double p = parsed.proba;
            if (p == null || !Double.isFinite(p)) {
                return Prediction.fail("no_proba");
            }

            double proba = Math.max(0.0, Math.min(1.0, p));
            String mk = firstNonBlank(parsed.modelKey, parsed.modelVersion, "unknown");
            String mv = blankToNull(parsed.modelVersion);
            String sh = blankToNull(parsed.schemaHash);

            return Prediction.ok(mk, mv, sh, proba);

        } catch (Exception e) {
            return Prediction.fail("predict_exception:" + e.getClass().getSimpleName());
        }
    }

    private ParsedMlPredictResponse parsePredictResponse(Object raw) {
        JsonNode node;
        try {
            node = objectMapper.valueToTree(raw);
        } catch (Exception e) {
            return new ParsedMlPredictResponse(false, null, "predict_response_unreadable", null, null, null);
        }

        Boolean ok = findBoolean(node, "ok", "success");
        Double proba = findDouble(node, "proba", "pWin", "pwin", "probability", "confidence", "score", "prob", "p");
        String error = findString(node, "error", "reason", "message", "detail");
        String modelKey = findString(node, "modelKey", "model_key", "key", "modelId", "model");
        String modelVersion = findString(node, "modelVersion", "model_version", "version");
        String schemaHash = findString(node, "schemaHash", "schema_hash", "featureOrderHash", "feature_order_hash");

        if (ok == null) {
            ok = (proba != null && (error == null || error.isBlank()));
        }

        return new ParsedMlPredictResponse(Boolean.TRUE.equals(ok), proba, blankToNull(error), blankToNull(modelKey), blankToNull(modelVersion), blankToNull(schemaHash));
    }

    private JsonNode findNode(JsonNode root, String... names) {
        if (root == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            JsonNode node = root.findValue(name);
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                return node;
            }
        }
        return null;
    }

    private Boolean findBoolean(JsonNode root, String... names) {
        JsonNode node = findNode(root, names);
        if (node == null) return null;

        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        if (node.isTextual()) {
            String s = node.asText();
            if (s == null) return null;
            s = s.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return null;
            if ("true".equals(s) || "ok".equals(s) || "success".equals(s) || "1".equals(s)) return true;
            if ("false".equals(s) || "fail".equals(s) || "failed".equals(s) || "0".equals(s)) return false;
        }
        return null;
    }

    private Double findDouble(JsonNode root, String... names) {
        JsonNode node = findNode(root, names);
        if (node == null) return null;

        if (node.isNumber()) {
            double v = node.doubleValue();
            return Double.isFinite(v) ? v : null;
        }
        if (node.isTextual()) {
            try {
                double v = Double.parseDouble(node.asText().trim());
                return Double.isFinite(v) ? v : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String findString(JsonNode root, String... names) {
        JsonNode node = findNode(root, names);
        if (node == null) return null;
        if (node.isContainerNode()) return null;
        return blankToNull(node.asText());
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String s = blankToNull(v);
            if (s != null) return s;
        }
        return null;
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Prediction getPredictionThrottled(Long chatId,
                                              String symbol,
                                              LocalState st,
                                              Map<String, Object> features,
                                              BigDecimal currentPrice,
                                              Instant now) {
        if (st == null || now == null) {
            return Prediction.fail("state_invalid");
        }

        long throttle = Math.max(200L, mlPredictThrottleMs);

        if (st.lastMlPrediction != null && st.lastMlPredictAt != null) {
            long ageMs = Duration.between(st.lastMlPredictAt, now).toMillis();
            if (ageMs >= 0 && ageMs < throttle && isAlmostSamePrice(st.lastMlPredictPrice, currentPrice)) {
                return st.lastMlPrediction;
            }
        }

        Prediction pred = tryPredict(chatId, symbol, now, features);

        st.lastMlPredictAt = now;
        st.lastMlPredictPrice = currentPrice;
        st.lastMlPrediction = pred;

        return pred;
    }

    private boolean isAlmostSamePrice(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        if (a.signum() <= 0 || b.signum() <= 0) return false;

        BigDecimal base = a.abs().max(BigDecimal.ONE);
        BigDecimal diffPct = a.subtract(b).abs()
                .divide(base, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return diffPct.compareTo(BigDecimal.valueOf(0.02)) <= 0;
    }

    private String normalizeMlFailureReason(String reason) {
        if (reason == null) return "predict_failed";
        String s = reason.trim();
        return s.isEmpty() ? "predict_failed" : s;
    }

    private boolean isHardMlFailureReason(String reason) {
        String s = normalizeMlFailureReason(reason).toLowerCase(Locale.ROOT);

        return s.contains("featureorder_hash_mismatch")
               || s.contains("feature_order_hash_mismatch")
               || s.contains("feature_hash_mismatch")
               || s.contains("schema_mismatch")
               || s.contains("feature_schema")
               || s.contains("feature_spec")
               || s.contains("missing_feature")
               || s.contains("unknown_feature")
               || s.contains("model_schema")
               || s.contains("column_mismatch");
    }

    private boolean isMlFailOpenAllowed(StrategySettings ss, String reason) {
        String normalized = normalizeMlFailureReason(reason);

        if ("no_model".equalsIgnoreCase(normalized)) {
            return true;
        }

        if (!mlFailOpen) return false;
        if (!isHybridMode(ss)) return false;
        return !isHardMlFailureReason(normalized);
    }

    private String mapMlFailureToHoldReason(StrategySettings ss, String reason) {
        if (isHardMlFailureReason(reason)) {
            return "ml_schema_mismatch";
        }
        if (isAiMode(ss)) {
            return "ml_required_ai_mode";
        }
        return "predict_failed";
    }

    private void logMlFailureThrottled(Long chatId,
                                       String sym,
                                       LocalState st,
                                       StrategySettings ss,
                                       String reason,
                                       Instant now) {
        if (st == null || now == null) return;

        long throttle = Math.max(3000L, mlLogThrottleMs);
        String key = "FAILOPEN:" + normalizeMlFailureReason(reason);

        if (Objects.equals(st.lastMlWarnReason, key) && st.lastMlWarnAt != null) {
            long age = Duration.between(st.lastMlWarnAt, now).toMillis();
            if (age >= 0 && age < throttle) return;
        }

        st.lastMlWarnReason = key;
        st.lastMlWarnAt = now;

        log.warn("[WINDOW] 🤖 ML fail-open chatId={} sym={} mode={} reason={}",
                chatId, sym, modeOrManual(ss), reason);
    }

    private void logMlBlockedThrottled(Long chatId,
                                       String sym,
                                       LocalState st,
                                       StrategySettings ss,
                                       String reason,
                                       Instant now) {
        if (st == null || now == null) return;

        long throttle = Math.max(3000L, mlLogThrottleMs);
        String key = "BLOCK:" + normalizeMlFailureReason(reason);

        if (Objects.equals(st.lastMlWarnReason, key) && st.lastMlWarnAt != null) {
            long age = Duration.between(st.lastMlWarnAt, now).toMillis();
            if (age >= 0 && age < throttle) return;
        }

        st.lastMlWarnReason = key;
        st.lastMlWarnAt = now;

        log.warn("[WINDOW] 🤖 ML BLOCKED entry chatId={} sym={} mode={} reason={}",
                chatId, sym, modeOrManual(ss), reason);
    }


    private void logEntryBlockedThrottled(Long chatId,
                                          String sym,
                                          LocalState st,
                                          String reason,
                                          BigDecimal price,
                                          BigDecimal diffPct,
                                          Instant now) {
        if (st == null || now == null) return;

        long throttle = Math.max(5_000L, entryBlockLogThrottleMs);
        long summaryThrottle = Math.max(throttle, entryBlockSummaryLogThrottleMs);

        if (Objects.equals(st.lastEntryBlockedReason, reason) && st.lastEntryBlockedAt != null) {
            long age = Duration.between(st.lastEntryBlockedAt, now).toMillis();
            st.lastEntryBlockedRepeatCount++;

            if (age >= 0 && age < throttle) {
                return;
            }

            if (age >= 0 && age < summaryThrottle) {
                st.lastEntryBlockedAt = now;
                return;
            }

            long repeatCount = Math.max(1L, st.lastEntryBlockedRepeatCount);
            st.lastEntryBlockedAt = now;
            st.lastEntryBlockedSummaryAt = now;
            st.lastEntryBlockedSummaryReason = reason;
            st.lastEntryBlockedRepeatCount = 0L;

            log.info("[WINDOW] ⛔ Вход отклонён chatId={} sym={} reason={} repeats={} lastPrice={} lastDiffPct={}",
                    chatId,
                    sym,
                    safeNullable(reason),
                    repeatCount,
                    fmtBd(price),
                    fmtBd(diffPct));
            return;
        }

        st.lastEntryBlockedReason = reason;
        st.lastEntryBlockedAt = now;
        st.lastEntryBlockedSummaryAt = now;
        st.lastEntryBlockedSummaryReason = reason;
        st.lastEntryBlockedRepeatCount = 0L;

        log.info("[WINDOW] ⛔ Вход отклонён chatId={} sym={} reason={} price={} diffPct={}",
                chatId,
                sym,
                safeNullable(reason),
                fmtBd(price),
                fmtBd(diffPct));
    }

    private void resetMlCache(LocalState st) {
        if (st == null) return;
        st.lastMlPredictAt = null;
        st.lastMlPredictPrice = null;
        st.lastMlPrediction = null;
        clearMlBelowThreshold(st);
    }

    private void clearMlBelowThreshold(LocalState st) {
        if (st == null) return;
        st.lastMlBelowThresholdAt = null;
        st.lastMlBelowThresholdPrice = null;
        st.lastMlBelowThresholdProba = null;
        st.lastMlBelowThresholdThreshold = null;
    }

    private void rememberMlBelowThreshold(LocalState st,
                                          BigDecimal price,
                                          double proba,
                                          double threshold,
                                          Instant now) {
        if (st == null) return;
        st.lastMlBelowThresholdAt = now;
        st.lastMlBelowThresholdPrice = price;
        st.lastMlBelowThresholdProba = proba;
        st.lastMlBelowThresholdThreshold = threshold;
    }

    private boolean shouldSkipMlRecheckAfterBelowThreshold(LocalState st,
                                                           BigDecimal currentPrice,
                                                           Instant now) {
        if (st == null || now == null) return false;
        if (st.lastMlBelowThresholdAt == null) return false;

        long cooldownMs = Math.max(300L, mlBelowThresholdCooldownMs);
        long ageMs = Duration.between(st.lastMlBelowThresholdAt, now).toMillis();
        if (ageMs < 0) return false;
        if (ageMs >= cooldownMs) return false;

        return !hasEnoughPriceMoveForMlRecheck(st.lastMlBelowThresholdPrice, currentPrice);
    }

    private boolean hasEnoughPriceMoveForMlRecheck(BigDecimal prevPrice, BigDecimal currentPrice) {
        if (prevPrice == null || currentPrice == null) return true;
        if (prevPrice.signum() <= 0 || currentPrice.signum() <= 0) return true;

        double movePct = priceMovePct(prevPrice, currentPrice);
        double minMovePct = mlRecheckMinPriceMovePct;
        if (!Double.isFinite(minMovePct) || minMovePct <= 0.0) {
            minMovePct = 0.03d;
        }

        return movePct >= minMovePct;
    }

    private double priceMovePct(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return Double.POSITIVE_INFINITY;
        if (a.signum() <= 0 || b.signum() <= 0) return Double.POSITIVE_INFINITY;

        return a.subtract(b).abs()
                .divide(a.abs().max(BigDecimal.ONE), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private void logMlBelowThresholdThrottled(Long chatId,
                                              String sym,
                                              LocalState st,
                                              Prediction pred,
                                              double threshold,
                                              Instant now) {
        if (st == null || now == null) return;

        long throttle = Math.max(5000L, holdThrottleMs);
        double proba = (pred != null ? pred.proba : 0.0);
        String probaBucket = fmt(Math.floor(proba * 100.0) / 100.0);
        String thresholdBucket = fmt(Math.floor(threshold * 100.0) / 100.0);
        String key = "LOWPROBA:" + probaBucket + ":" + thresholdBucket;

        if (Objects.equals(st.lastMlWarnReason, key) && st.lastMlWarnAt != null) {
            long age = Duration.between(st.lastMlWarnAt, now).toMillis();
            if (age >= 0 && age < throttle) {
                return;
            }
        }

        st.lastMlWarnReason = key;
        st.lastMlWarnAt = now;

        log.info("[WINDOW] 🤖 HOLD: ML ниже порога chatId={} sym={} proba={} threshold={} adaptiveThreshold={} streak={} cooldownMs={} recheckMovePct={} modelKey={} modelVer={} schemaHash={}",
                chatId,
                sym,
                fmt(proba),
                fmt(threshold),
                fmt(st.lastAdaptiveMlThreshold != null ? st.lastAdaptiveMlThreshold : resolveMlThreshold(st.ss)),
                st.consecutiveMlBelowThreshold,
                Math.max(300L, mlBelowThresholdCooldownMs),
                fmt(mlRecheckMinPriceMovePct),
                pred != null ? safeNullable(pred.modelKey) : null,
                pred != null ? safeNullable(pred.modelVersion) : null,
                pred != null ? safeNullable(pred.schemaHash) : null);
    }

    private void rememberPendingMlSample(LocalState st,
                                         Map<String, Object> features,
                                         Instant ts,
                                         String symbol,
                                         String exchange,
                                         NetworkType network,
                                         String timeframe,
                                         BigDecimal entryPrice,
                                         Double proba,
                                         String modelKey,
                                         boolean failOpen) {
        if (st == null) return;
        if (features == null || features.isEmpty()) return;

        st.pendingMlFeatures = new LinkedHashMap<>(features);
        st.pendingMlFeatureTs = ts;
        st.pendingMlSymbol = normalizeSymbolOrNull(symbol);
        st.pendingMlExchange = normalizeExchangeOrNull(exchange);
        st.pendingMlNetwork = (network != null ? network.name() : null);
        st.pendingMlTimeframe = normalizeTimeframeOrNull(timeframe);
        st.pendingMlEntryPrice = entryPrice;
        st.pendingMlProba = proba;
        st.pendingMlModelKey = (modelKey != null && !modelKey.isBlank()) ? modelKey.trim() : null;
        st.pendingMlFailOpen = failOpen;
    }

    private void clearPendingMlSample(LocalState st) {
        if (st == null) return;
        st.pendingMlFeatures = null;
        st.pendingMlFeatureTs = null;
        st.pendingMlSymbol = null;
        st.pendingMlExchange = null;
        st.pendingMlNetwork = null;
        st.pendingMlTimeframe = null;
        st.pendingMlEntryPrice = null;
        st.pendingMlProba = null;
        st.pendingMlModelKey = null;
        st.pendingMlFailOpen = false;
    }

    private void persistClosedTradeSample(Long chatId,
                                          LocalState st,
                                          String symbol,
                                          BigDecimal exitPrice,
                                          BigDecimal realizedPnlPct,
                                          Instant exitTime) {
        if (chatId == null || st == null) return;
        if (mlSampleIngestService == null || objectMapper == null) return;
        if (st.pendingMlFeatures == null || st.pendingMlFeatures.isEmpty()) return;

        try {
            String sym = normalizeSymbolOrNull(symbol);
            if (sym == null) sym = normalizeSymbolOrNull(st.pendingMlSymbol);
            if (sym == null) {
                clearPendingMlSample(st);
                return;
            }

            BigDecimal entryPrice = positiveOrNull(st.pendingMlEntryPrice);
            BigDecimal actualExit = positiveOrNull(exitPrice);

            if (entryPrice == null || actualExit == null) {
                clearPendingMlSample(st);
                return;
            }

            String label;
            if (realizedPnlPct != null) {
                label = realizedPnlPct.signum() >= 0 ? "WIN" : "LOSS";
            } else {
                label = actualExit.compareTo(entryPrice) >= 0 ? "WIN" : "LOSS";
            }

            Map<String, Object> features = new LinkedHashMap<>(st.pendingMlFeatures);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("featureSpec", new ArrayList<>(features.keySet()));
            meta.put("entryPrice", entryPrice);
            meta.put("exitPrice", actualExit);
            meta.put("label", label);
            meta.put("realizedPnlPct", realizedPnlPct);
            meta.put("closedAtMs", exitTime != null ? exitTime.toEpochMilli() : Instant.now().toEpochMilli());
            meta.put("mlProba", st.pendingMlProba);
            meta.put("mlModelKey", st.pendingMlModelKey);
            meta.put("mlFailOpen", st.pendingMlFailOpen);
            meta.put("strategyType", StrategyType.WINDOW_SCALPING.name());
            meta.put("symbol", sym);
            meta.put("exchange", st.pendingMlExchange);
            meta.put("network", st.pendingMlNetwork);
            meta.put("timeframe", st.pendingMlTimeframe);

            JsonNode featuresJson = objectMapper.valueToTree(features);
            JsonNode metaJson = objectMapper.valueToTree(meta);

            MlSampleEntity sample = MlSampleEntity.builder()
                    .chatId(chatId)
                    .strategyType(StrategyType.WINDOW_SCALPING)
                    .exchange(st.pendingMlExchange)
                    .network(st.pendingMlNetwork)
                    .symbol(sym)
                    .timeframe(st.pendingMlTimeframe)
                    .ts(st.pendingMlFeatureTs != null ? st.pendingMlFeatureTs : exitTime)
                    .label(label)
                    .target("TP_SL")
                    .proba(st.pendingMlProba)
                    .featuresJson(featuresJson)
                    .metaJson(metaJson)
                    .createdAt(Instant.now())
                    .build();

            mlSampleIngestService.saveAndMaybeTrain(sample);

            log.info("[WINDOW] 🧠 ML sample saved chatId={} sym={} tf={} label={} entry={} exit={} modelKey={} failOpen={}",
                    chatId,
                    sym,
                    st.pendingMlTimeframe,
                    label,
                    fmtBd(entryPrice),
                    fmtBd(actualExit),
                    safeNullable(st.pendingMlModelKey),
                    st.pendingMlFailOpen);

        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ Не удалось сохранить ML sample chatId={} sym={} err={}",
                    chatId, symbol, e.toString(), e);
        } finally {
            clearPendingMlSample(st);
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
        Map<String, Object> f = new LinkedHashMap<>();

        f.put("chatId", chatId);
        f.put("strategyType", StrategyType.WINDOW_SCALPING.name());
        f.put("symbol", symbol);
        f.put("exchange", st.exchange);
        f.put("network", st.network != null ? st.network.name() : null);
        f.put("timeframe", st.timeframe);
        f.put("ts", (ts != null ? ts : Instant.now()).toEpochMilli());

        double lastPrice = price.doubleValue();

        double volatilityPct = calcVolatilityPct(st);
        if (!Double.isFinite(volatilityPct) || volatilityPct <= 0.0) {
            volatilityPct = rangePct;
        }

        Deque<BigDecimal> src = (st.tickWindow != null && !st.tickWindow.isEmpty())
                ? st.tickWindow
                : st.window;

        double retWindowPct = calcWindowReturnPct(src);
        double momentum1 = calcMomentum1Pct(src, price);

        double smaFast = calcSma(src, Math.max(3, Math.min(5, windowSize)));
        double smaSlow = calcSma(src, Math.max(5, Math.min(10, windowSize)));

        double smaFastRel = relPct(lastPrice, smaFast);
        double smaSlowRel = relPct(lastPrice, smaSlow);

        // ВАЖНО: порядок должен совпадать с MlGateway.WINDOW_SCALPING_SPEC
        f.put("windowSize", windowSize);
        f.put("lastPrice", lastPrice);
        f.put("price", lastPrice);
        f.put("low", low.doubleValue());
        f.put("high", high.doubleValue());
        f.put("range", range.doubleValue());
        f.put("rangePct", rangePct);
        f.put("volatilityPct", volatilityPct);
        f.put("pos01", pos);
        f.put("posPct", pos * 100.0);
        f.put("lowZone01", lowZone);
        f.put("highZone01", highZone);
        f.put("diffPctForEntry", diffPctForEntry.doubleValue());
        f.put("retWindowPct", retWindowPct);
        f.put("momentum1", momentum1);
        f.put("smaFastRel", smaFastRel);
        f.put("smaSlowRel", smaSlowRel);

        return f;
    }

    private double calcWindowReturnPct(Deque<BigDecimal> src) {
        if (src == null || src.isEmpty()) return 0.0;

        BigDecimal first = src.peekFirst();
        BigDecimal last = src.peekLast();

        if (first == null || last == null || first.signum() <= 0) return 0.0;

        return last.subtract(first)
                .divide(first, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private double calcMomentum1Pct(Deque<BigDecimal> src, BigDecimal fallbackLast) {
        if (src == null || src.isEmpty()) return 0.0;

        Iterator<BigDecimal> it = src.descendingIterator();
        BigDecimal last = it.hasNext() ? it.next() : fallbackLast;
        BigDecimal prev = it.hasNext() ? it.next() : null;

        if (last == null) last = fallbackLast;
        if (last == null || prev == null || prev.signum() <= 0) return 0.0;

        return last.subtract(prev)
                .divide(prev, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private double calcSma(Deque<BigDecimal> src, int period) {
        if (src == null || src.isEmpty() || period <= 0) return 0.0;

        Iterator<BigDecimal> it = src.descendingIterator();
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;

        while (it.hasNext() && n < period) {
            BigDecimal v = it.next();
            if (v == null || v.signum() <= 0) continue;
            sum = sum.add(v);
            n++;
        }

        if (n == 0) return 0.0;

        return sum.divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP).doubleValue();
    }

    private double calcVolatilityPct(LocalState st) {
        Deque<BigDecimal> src = (st != null && st.tickWindow != null && !st.tickWindow.isEmpty())
                ? st.tickWindow
                : (st != null ? st.window : null);
        if (src == null || src.size() < 3) return 0.0;

        List<Double> values = new ArrayList<>(src.size());
        for (BigDecimal v : src) {
            if (v != null && v.signum() > 0) {
                values.add(v.doubleValue());
            }
        }

        if (values.size() < 3) return 0.0;

        double mean = 0.0;
        for (double v : values) mean += v;
        mean /= values.size();

        if (!Double.isFinite(mean) || mean <= 0.0) return 0.0;

        double var = 0.0;
        for (double v : values) {
            double d = v - mean;
            var += d * d;
        }
        var /= values.size();

        double std = Math.sqrt(var);
        if (!Double.isFinite(std) || std <= 0.0) return 0.0;

        return (std / mean) * 100.0;
    }

    private double relPct(double price, double ma) {
        if (!Double.isFinite(price) || !Double.isFinite(ma) || ma <= 0.0) return 0.0;
        return ((price - ma) / ma) * 100.0;
    }

    // =====================================================
    // HOLD / AUTOTUNE
    // =====================================================

    private void safeLive(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
        }
    }

    private Set<String> parsedAutoTuneHoldReasons() {
        String raw = (autoTuneHoldReasons == null ? "" : autoTuneHoldReasons.trim());
        String cacheKey = raw + "|lowConf=" + mlAutoTuneOnLowConfidence;
        if (cacheKey.equals(cachedHoldReasonsRaw) && cachedHoldReasonsSet != null) {
            return cachedHoldReasonsSet;
        }

        try {
            Set<String> out = new HashSet<>();

            if (!raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (String p : parts) {
                    String v = p.trim();
                    if (!v.isEmpty()) out.add(v);
                }
            }

            if (mlAutoTuneOnLowConfidence) {
                out.add("ml_below_threshold");
                out.add("predict_failed");
            }

            cachedHoldReasonsRaw = cacheKey;
            cachedHoldReasonsSet = Set.copyOf(out);
            return cachedHoldReasonsSet;

        } catch (Exception ignored) {
            cachedHoldReasonsRaw = cacheKey;
            cachedHoldReasonsSet = Set.of();
            return cachedHoldReasonsSet;
        }
    }

    private void maybeRequestAutoTuneOnLowConfidence(Long chatId,
                                                     String symbol,
                                                     LocalState st,
                                                     StrategySettings ss,
                                                     double proba,
                                                     double threshold,
                                                     Instant now) {
        if (!mlAutoTuneOnLowConfidence) return;
        if (st == null || ss == null || now == null) return;
        if (st.inPosition) return;
        if (!isAutoTuneAllowed(ss)) return;
        if (st.exchange == null || st.network == null) return;

        int after = Math.max(3, mlAutoTuneLowConfidenceAfter);
        if (st.consecutiveMlBelowThreshold < after) return;

        AiStrategyOrchestrator o = orch();
        if (o == null) return;

        long cdSec = Math.max(10, autoTuneHoldCooldownSeconds);
        if (st.lastAutoTuneRequestAt != null) {
            long passed = Duration.between(st.lastAutoTuneRequestAt, now).getSeconds();
            if (passed < cdSec) return;
        }

        st.lastAutoTuneRequestAt = now;

        log.warn("[WINDOW] 🧠 AUTO-TUNE(LOW-CONFIDENCE) chatId={} sym={} proba={} threshold={} streak={} cooldown={}s",
                chatId, symbol, fmt(proba), fmt(threshold), st.consecutiveMlBelowThreshold, cdSec);

        o.triggerTuneDebounced(
                chatId,
                StrategyType.WINDOW_SCALPING,
                st.exchange,
                st.network,
                "low_confidence:proba=" + fmt(proba) + ":thr=" + fmt(threshold),
                Duration.ofSeconds(cdSec)
        );
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
            case "warming_up" -> "Прогрев окна (недостаточно данных)";
            case "window_invalid" -> "Не удалось корректно построить окно";
            case "range_zero" -> "Диапазон окна нулевой";
            case "range_too_small" -> "Диапазон слишком мал для входа";
            case "pos_invalid" -> "Не удалось вычислить позицию цены в окне";
            case "tp_sl_pct_invalid" -> "Некорректный TP/SL";
            case "falling_knife" -> "Цена слишком близко к экстремуму окна — ловим нож";
            case "negative_micro_momentum" -> "Нет микро-разворота вверх";
            case "trend_down_too_strong" -> "Окно ещё слишком медвежье для входа";
            case "micro_chop" -> "Слишком мелкий шум внутри окна";
            case "weak_low_zone_rebound" -> "Нижняя зона есть, но отскок слишком слабый";
            case "weak_low_zone_touch" -> "Касание нижней зоны слишком слабое";
            case "weak_low_zone_touch_fail_open" -> "Без ML вход по нижней зоне слишком слабый";
            case "no_room_to_high" -> "До верха окна недостаточно хода для безопасного TP";
            case "same_candle_exit_blocked" -> "Выход в той же свече после входа запрещён";
            case "predict_failed" -> "ML-прогноз недоступен";
            case "ml_below_threshold" -> "ML-прогноз ниже порога";
            case "ml_required_ai_mode" -> "В AI-режиме вход заблокирован: ML недоступен";
            case "ml_schema_mismatch" -> "ML схема/порядок фичей не совпадает";
            case "entry_failed" -> "Ошибка при входе в сделку";
            case "open_order_exists" -> "Есть открытый ордер по этому символу — новый вход запрещён";
            case "wallet_base_untracked_position" -> "На бирже уже есть base-актив по символу — новый вход запрещён";
            case "in_high_zone_wait_tp" -> "Цена у верхней границы — ждём";
            case "pos_snapshot_missing" -> "Позиция есть, но нет данных в PositionStore";
            case "restored_dust_position" -> "Позиция слишком мала для выхода (dust, меньше minNotional)";
            case "partial_exit", "sell_not_fully_filled" -> "SELL исполнился не полностью, позиция оставлена открытой";
            case "sell_not_filled" -> "SELL не исполнился, позиция сохранена";
            case "balance" -> "Недостаточно доступного base для полного закрытия, позиция сохранена";
            case "position_sync_mismatch" -> "Размер позиции не совпадает с доступным base на бирже, полная продажа запрещена";
            case "post_exit_cooldown" -> "Короткий cooldown после выхода";
            case "post_sl_cooldown" -> "Пауза после стоп-лосса, чтобы не перезаходить сразу";
            case "post_entry_cooldown" -> "Короткий cooldown после входа";
            default -> null;
        };
    }

    // =====================================================
    // POSITION RESTORE
    // =====================================================

    private boolean isDustPosition(BigDecimal qty, BigDecimal entryPrice) {
        if (qty == null || entryPrice == null) return false;
        if (qty.signum() <= 0 || entryPrice.signum() <= 0) return false;

        BigDecimal notional = qty.multiply(entryPrice);
        return notional.compareTo(MIN_RESTORABLE_NOTIONAL) < 0;
    }

    private void clearLocalPosition(LocalState st) {
        if (st == null) return;

        st.inPosition = false;
        st.isLong = true;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.entryQty = null;
        st.entryOrderId = null;
        st.entryCandleOpenTimeMs = null;
        st.entryCandleTimeframe = null;
        st.lastPositionSyncAt = null;
    }

    private void resetRestoreThrottleState(LocalState st) {
        if (st == null) return;
        st.lastRestoreProbeAt = null;
        st.lastRestoreMissAt = null;
        st.lastDustRestoreLogAt = null;
        st.lastDustRestoreLogKey = null;
        st.lastPositionSyncAt = null;
    }

    private boolean shouldProbeRestore(LocalState st, Instant now) {
        if (st == null || now == null) return true;
        if (st.inPosition) return false;

        if (st.lastRestoreMissAt == null) {
            return true;
        }

        long cooldownMs = Math.max(500L, restoreRetryCooldownMs);
        long ageMs = Duration.between(st.lastRestoreMissAt, now).toMillis();
        return ageMs < 0 || ageMs >= cooldownMs;
    }

    private void markRestoreMiss(LocalState st, Instant now) {
        if (st == null || now == null) return;
        st.lastRestoreProbeAt = now;
        st.lastRestoreMissAt = now;
    }

    private void markRestoreSuccess(LocalState st, Instant now) {
        if (st == null || now == null) return;
        st.lastRestoreProbeAt = now;
        st.lastRestoreMissAt = null;
    }

    private boolean shouldLogDustRestore(LocalState st, String key, Instant now) {
        if (st == null || now == null) return true;

        long throttleMs = Math.max(1_000L, dustRestoreLogThrottleMs);
        if (Objects.equals(st.lastDustRestoreLogKey, key) && st.lastDustRestoreLogAt != null) {
            long ageMs = Duration.between(st.lastDustRestoreLogAt, now).toMillis();
            if (ageMs >= 0 && ageMs < throttleMs) {
                return false;
            }
        }

        st.lastDustRestoreLogKey = key;
        st.lastDustRestoreLogAt = now;
        return true;
    }

    private String buildDustRestoreLogKey(Long chatId,
                                          String symbol,
                                          String exchange,
                                          NetworkType network,
                                          String source) {
        return chatId + ":" +
                safeNullable(symbol) + ":" +
                safeNullable(exchange) + ":" +
                safeNullable(network != null ? network.name() : null) + ":" +
                safeNullable(source);
    }

    private void logDustRestoreSkipThrottled(Long chatId,
                                             LocalState st,
                                             String symbol,
                                             String exchange,
                                             NetworkType network,
                                             BigDecimal qty,
                                             BigDecimal entryPrice,
                                             BigDecimal notional,
                                             String source,
                                             Instant now) {
        String key = buildDustRestoreLogKey(chatId, symbol, exchange, network, source);
        if (!shouldLogDustRestore(st, key, now)) {
            return;
        }

        if ("history".equalsIgnoreCase(source)) {
            log.warn("[WINDOW] 🧹 Не восстанавливаю dust-позицию из истории chatId={} sym={} ex={} net={} qty={} notional={} minRestorable={}",
                    chatId,
                    symbol,
                    exchange,
                    network,
                    fmtBd(qty),
                    fmtBd(notional),
                    fmtBd(MIN_RESTORABLE_NOTIONAL));
            return;
        }

        log.warn("[WINDOW] 🧹 Пропускаю восстановление dust-позиции chatId={} sym={} ex={} net={} qty={} entry={} notional={} minRestorable={}",
                chatId,
                symbol,
                exchange,
                network,
                fmtBd(qty),
                fmtBd(entryPrice),
                fmtBd(notional),
                fmtBd(MIN_RESTORABLE_NOTIONAL));
    }

    private void maybeRestorePositionFromStore(Long chatId, LocalState st, String symbol, Instant now) {
        if (chatId == null || st == null || now == null) return;

        // PROD: стратегия больше не занимается восстановлением позиции из истории ордеров
        // и не синхронизирует qty по балансу сама.
        // Единственный источник истины здесь — PositionStore.
        if (!shouldProbeRestore(st, now)) {
            return;
        }

        String ex = normalizeExchangeOrNull(st.exchange);
        NetworkType net = st.network;
        String resolvedSym = normalizeSymbolOrNull(symbol);
        if (resolvedSym == null) resolvedSym = normalizeSymbolOrNull(st.symbol);
        if (ex == null || net == null || resolvedSym == null) return;
        final String sym = resolvedSym;

        st.lastRestoreProbeAt = now;

        Optional<PositionStore.PositionSnapshot> opt =
                positionStore.getPosition(chatId, StrategyType.WINDOW_SCALPING, ex, net, sym);

        if (opt.isEmpty()) {
            if (st.inPosition) {
                clearLocalPosition(st);
                safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, sym));
                safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, sym));
            }
            markRestoreMiss(st, now);
            return;
        }

        PositionStore.PositionSnapshot snap = opt.get();

        BigDecimal restoredEntry = positiveOrNull(snap.entryPrice());
        BigDecimal restoredQty = positiveOrNull(snap.qty());
        BigDecimal restoredTp = positiveOrNull(snap.tp());
        BigDecimal restoredSl = positiveOrNull(snap.sl());

        if (restoredEntry == null || restoredQty == null) {
            clearLocalPosition(st);
            markRestoreMiss(st, now);
            return;
        }

        if (!isValidRestoredLongTp(restoredEntry, restoredTp) || !isValidRestoredLongSl(restoredEntry, restoredSl)) {
            BigDecimal fixedTp = resolveTpForRestore(st, restoredEntry);
            BigDecimal fixedSl = resolveSlForRestore(st, restoredEntry);

            if (isValidRestoredLongTp(restoredEntry, fixedTp) && isValidRestoredLongSl(restoredEntry, fixedSl)) {
                restoredTp = fixedTp;
                restoredSl = fixedSl;
                try {
                    positionStore.markOpened(
                            chatId,
                            StrategyType.WINDOW_SCALPING,
                            ex,
                            net,
                            sym,
                            restoredEntry,
                            restoredQty,
                            restoredTp,
                            restoredSl,
                            positiveOrNull(snap.quoteSpent()) != null
                                    ? snap.quoteSpent()
                                    : restoredEntry.multiply(restoredQty),
                            snap.entryOrderId(),
                            snap.openedAt()
                    );
                } catch (Exception ignored) {
                }
            } else {
                clearLocalPosition(st);
                markRestoreMiss(st, now);
                return;
            }
        }

        st.inPosition = true;
        st.isLong = true;
        st.entryPrice = restoredEntry;
        st.entryQty = restoredQty;
        st.tp = restoredTp;
        st.sl = restoredSl;
        st.entryOrderId = snap.entryOrderId();
        st.lastEntryAt = (snap.openedAt() != null ? snap.openedAt() : now);
        rememberEntryCandle(st, st.lastEntryAt);
        markRestoreSuccess(st, now);
        publishPositionLines(chatId, sym, st);
    }

    private BigDecimal alignQtyToExchangeBalance(Long chatId,
                                               LocalState st,
                                               String exchange,
                                               NetworkType network,
                                               String symbol,
                                               BigDecimal qty) {
        BigDecimal safeQty = positiveOrNull(qty);
        if (safeQty == null) {
            return qty;
        }

        if (tradeExecutionService instanceof com.chicu.aitradebot.trade.TradeExecutionServiceImpl execImpl) {
            try {
                BigDecimal aligned = execImpl.alignPositionQtyToExchangeBalance(
                        chatId,
                        StrategyType.WINDOW_SCALPING,
                        exchange,
                        network,
                        symbol,
                        safeQty
                );

                BigDecimal normalized = positiveOrNull(aligned);
                if (normalized != null) {
                    return normalized;
                }
            } catch (Exception e) {
                log.debug("[WINDOW] alignQtyToExchangeBalance skipped chatId={} sym={} ex={} net={} err={}",
                        chatId, symbol, exchange, network, e.toString());
            }
        }

        return safeQty;
    }

    private boolean shouldSyncRuntimePosition(LocalState st, Instant now) {
        if (st == null || now == null) return false;

        long minIntervalMs = Math.max(1_000L, positionSyncMinIntervalMs);

        if (st.lastEntryAt != null) {
            long sinceEntryMs = Duration.between(st.lastEntryAt, now).toMillis();
            if (sinceEntryMs >= 0 && sinceEntryMs < minIntervalMs) {
                return false;
            }
        }

        if (st.lastPositionSyncAt == null) {
            return true;
        }

        long ageMs = Duration.between(st.lastPositionSyncAt, now).toMillis();
        return ageMs < 0 || ageMs >= minIntervalMs;
    }

    private void syncRuntimePositionWithExchangeBalance(Long chatId,
                                                        LocalState st,
                                                        String exchange,
                                                        NetworkType network,
                                                        String symbol,
                                                        Instant now) {
        if (chatId == null || st == null || exchange == null || network == null || symbol == null) {
            return;
        }

        BigDecimal entryPrice = positiveOrNull(st.entryPrice);
        BigDecimal currentQty = positiveOrNull(st.entryQty);
        if (entryPrice == null || currentQty == null) {
            return;
        }

        BigDecimal alignedQty = alignQtyToExchangeBalance(chatId, st, exchange, network, symbol, currentQty);
        BigDecimal safeAlignedQty = positiveOrNull(alignedQty);
        if (safeAlignedQty == null) {
            return;
        }

        if (safeAlignedQty.compareTo(currentQty) >= 0) {
            return;
        }

        BigDecimal notional = entryPrice.multiply(safeAlignedQty).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        if (notional.compareTo(MIN_RESTORABLE_NOTIONAL) < 0) {
            logDustRestoreSkipThrottled(chatId, st, symbol, exchange, network, safeAlignedQty, entryPrice, notional, "store", now);
            clearLocalPosition(st);
            try {
                positionStore.clearPosition(chatId, StrategyType.WINDOW_SCALPING, exchange, network, symbol);
            } catch (Exception ignored) {
            }
            safeLive(() -> live.clearTpSl(chatId, StrategyType.WINDOW_SCALPING, symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.WINDOW_SCALPING, symbol));
            return;
        }

        log.warn("[WINDOW] ♻ Синхронизирую локальную позицию по фактическому free base chatId={} sym={} ex={} net={} oldQty={} newQty={} entry={}",
                chatId,
                symbol,
                exchange,
                network,
                fmtBd(currentQty),
                fmtBd(safeAlignedQty),
                fmtBd(entryPrice));

        st.entryQty = safeAlignedQty;
        try {
            positionStore.markOpened(
                    chatId,
                    StrategyType.WINDOW_SCALPING,
                    exchange,
                    network,
                    symbol,
                    entryPrice,
                    safeAlignedQty,
                    st.tp,
                    st.sl,
                    notional,
                    st.entryOrderId,
                    st.lastEntryAt != null ? st.lastEntryAt : now
            );
        } catch (Exception ignored) {
        }
    }

    private boolean isRestoreSuppressedForContext(Long chatId,
                                                  String exchange,
                                                  NetworkType network,
                                                  String symbol) {
        if (chatId == null || exchange == null || network == null || symbol == null) {
            return false;
        }

        if (positionStore instanceof InMemoryPositionStoreImpl store) {
            return store.isRestoreSuppressed(
                    chatId,
                    StrategyType.WINDOW_SCALPING,
                    exchange,
                    network,
                    symbol
            );
        }

        return false;
    }

    private void suppressRestoreForContext(Long chatId,
                                           String exchange,
                                           NetworkType network,
                                           String symbol,
                                           String reason) {
        if (chatId == null || exchange == null || network == null || symbol == null) {
            return;
        }

        if (positionStore instanceof InMemoryPositionStoreImpl store) {
            store.suppressRestore(
                    chatId,
                    StrategyType.WINDOW_SCALPING,
                    exchange,
                    network,
                    symbol,
                    0L,
                    reason != null ? reason : "window_restore_suppressed"
            );
        }
    }

    private boolean restorePositionFromOrderHistory(Long chatId,
                                                    LocalState st,
                                                    String exchange,
                                                    NetworkType network,
                                                    String symbol,
                                                    Instant now) {
        if (chatId == null || st == null || exchange == null || network == null || symbol == null) {
            return false;
        }

        if (isRestoreSuppressedForContext(chatId, exchange, network, symbol)) {
            return false;
        }

        if (orderRepository == null) {
            return false;
        }

        List<OrderEntity> orders;
        try {
            orders = orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
        } catch (Exception e) {
            log.warn("[WINDOW] ⚠ Не удалось прочитать историю ордеров для восстановления chatId={} sym={} err={}",
                    chatId, symbol, e.toString());
            return false;
        }

        if (orders == null || orders.isEmpty()) {
            return false;
        }

        boolean hasExactContextOrders = false;
        for (OrderEntity o : orders) {
            if (matchesRuntimeContext(o, exchange, network, false)) {
                hasExactContextOrders = true;
                break;
            }
        }

        List<OpenLot> openLots = new ArrayList<>();

        for (OrderEntity o : orders) {
            if (o == null) continue;
            if (!StrategyType.WINDOW_SCALPING.name().equalsIgnoreCase(safeNullable(o.getStrategyType()))) continue;
            if (!Boolean.TRUE.equals(o.getFilled())) continue;

            boolean allowLegacy = !hasExactContextOrders;
            if (!matchesRuntimeContext(o, exchange, network, allowLegacy)) continue;

            String side = safeNullable(o.getSide());
            BigDecimal qty = positiveOrNull(o.getQuantity());
            BigDecimal price = positiveOrNull(o.getPrice());

            if (qty == null || price == null) continue;

            if ("BUY".equalsIgnoreCase(side)) {
                openLots.add(new OpenLot(qty, price, o.getId(), resolveOrderTime(o, now)));
                continue;
            }

            if ("SELL".equalsIgnoreCase(side)) {
                BigDecimal leftToClose = qty;

                while (leftToClose.signum() > 0 && !openLots.isEmpty()) {
                    OpenLot first = openLots.get(0);

                    if (first.qty.compareTo(leftToClose) <= 0) {
                        leftToClose = leftToClose.subtract(first.qty);
                        openLots.remove(0);
                    } else {
                        first.qty = first.qty.subtract(leftToClose);
                        leftToClose = BigDecimal.ZERO;
                    }
                }
            }
        }

        if (openLots.isEmpty()) {
            return false;
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        Instant firstOpenedAt = null;
        Long lastOrderId = null;

        for (OpenLot lot : openLots) {
            if (lot == null || lot.qty == null || lot.price == null) continue;
            if (lot.qty.signum() <= 0 || lot.price.signum() <= 0) continue;

            totalQty = totalQty.add(lot.qty);
            totalCost = totalCost.add(lot.qty.multiply(lot.price));

            if (firstOpenedAt == null || (lot.openedAt != null && lot.openedAt.isBefore(firstOpenedAt))) {
                firstOpenedAt = lot.openedAt;
            }
            lastOrderId = lot.orderId;
        }

        if (totalQty.signum() <= 0 || totalCost.signum() <= 0) {
            return false;
        }

        if (totalCost.compareTo(MIN_RESTORABLE_NOTIONAL) < 0) {
            logDustRestoreSkipThrottled(
                    chatId,
                    st,
                    symbol,
                    exchange,
                    network,
                    totalQty,
                    null,
                    totalCost,
                    "history",
                    now
            );
            suppressRestoreForContext(chatId, exchange, network, symbol, "window_history_dust_skip_total_cost");
            return false;
        }

        BigDecimal entryPrice = totalCost.divide(totalQty, 12, RoundingMode.HALF_UP);
        totalQty = alignQtyToExchangeBalance(chatId, st, exchange, network, symbol, totalQty);
        totalCost = entryPrice.multiply(totalQty).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();

        if (isDustPosition(totalQty, entryPrice)) {
            logDustRestoreSkipThrottled(
                    chatId,
                    st,
                    symbol,
                    exchange,
                    network,
                    totalQty,
                    entryPrice,
                    totalCost,
                    "history",
                    now
            );
            suppressRestoreForContext(chatId, exchange, network, symbol, "window_history_dust_skip_aligned_qty");
            return false;
        }

        BigDecimal tp = resolveTpForRestore(st, entryPrice);
        BigDecimal sl = resolveSlForRestore(st, entryPrice);

        if (tp == null || sl == null) {
            log.warn("[WINDOW] ⚠ Не удалось восстановить TP/SL из истории chatId={} sym={} entry={} qty={}",
                    chatId, symbol, fmtBd(entryPrice), fmtBd(totalQty));
            return false;
        }

        positionStore.markOpened(
                chatId,
                StrategyType.WINDOW_SCALPING,
                exchange,
                network,
                symbol,
                entryPrice,
                totalQty,
                tp,
                sl,
                totalCost,
                lastOrderId,
                (firstOpenedAt != null ? firstOpenedAt : now)
        );

        st.inPosition = true;
        st.isLong = true;
        st.entryPrice = entryPrice;
        st.entryQty = totalQty;
        st.tp = tp;
        st.sl = sl;
        st.entryOrderId = lastOrderId;
        st.lastEntryAt = (firstOpenedAt != null ? firstOpenedAt : now);
        rememberEntryCandle(st, st.lastEntryAt);

        publishPositionLines(chatId, symbol, st);
        resetMlCache(st);
        markRestoreSuccess(st, now);

        log.warn("[WINDOW] ♻ Восстановлена позиция из истории ордеров chatId={} sym={} ex={} net={} lots={} qty={} entry={} tp={} sl={} orderId={}",
                chatId,
                symbol,
                exchange,
                network,
                openLots.size(),
                fmtBd(totalQty),
                fmtBd(entryPrice),
                fmtBd(tp),
                fmtBd(sl),
                lastOrderId);

        safeLive(() -> live.pushSignal(
                chatId,
                StrategyType.WINDOW_SCALPING,
                symbol,
                null,
                Signal.hold("Позиция восстановлена после перезапуска")
        ));

        return true;
    }

    private boolean matchesRuntimeContext(OrderEntity order,
                                          String exchange,
                                          NetworkType network,
                                          boolean allowLegacyWithoutContext) {
        if (order == null) return false;

        String orderEx = normalizeExchangeOrNull(order.getExchangeName());
        String orderNet = normalizeExchangeOrNull(order.getNetworkType());

        String runtimeEx = normalizeExchangeOrNull(exchange);
        String runtimeNet = normalizeExchangeOrNull(network != null ? network.name() : null);

        boolean orderHasNoContext = (orderEx == null && orderNet == null);
        if (orderHasNoContext) {
            return allowLegacyWithoutContext;
        }

        return Objects.equals(orderEx, runtimeEx) && Objects.equals(orderNet, runtimeNet);
    }

    private boolean isValidRestoredLongTp(BigDecimal entryPrice, BigDecimal tp) {
        return positiveOrNull(entryPrice) != null
                && positiveOrNull(tp) != null
                && tp.compareTo(entryPrice) > 0;
    }

    private boolean isValidRestoredLongSl(BigDecimal entryPrice, BigDecimal sl) {
        return positiveOrNull(entryPrice) != null
                && positiveOrNull(sl) != null
                && sl.compareTo(entryPrice) < 0;
    }

    private BigDecimal resolveTpForRestore(LocalState st, BigDecimal entryPrice) {
        if (st == null || entryPrice == null || entryPrice.signum() <= 0) return null;
        EntryRisk risk = resolveEntryRisk(st, 0.20, 60.0, null);
        if (risk != null && risk.tpPct() != null) {
            return calcTpFromPct(entryPrice, risk.tpPct());
        }
        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null || cfg.getTakeProfitPct() == null || cfg.getTakeProfitPct().signum() <= 0) return null;
        return calcTpFromPct(entryPrice, cfg.getTakeProfitPct());
    }

    private BigDecimal resolveSlForRestore(LocalState st, BigDecimal entryPrice) {
        if (st == null || entryPrice == null || entryPrice.signum() <= 0) return null;
        EntryRisk risk = resolveEntryRisk(st, 0.20, 60.0, null);
        if (risk != null && risk.slPct() != null) {
            return calcSlFromPct(entryPrice, risk.slPct());
        }
        WindowScalpingStrategySettings cfg = st.cfg;
        if (cfg == null || cfg.getStopLossPct() == null || cfg.getStopLossPct().signum() <= 0) return null;
        return calcSlFromPct(entryPrice, cfg.getStopLossPct());
    }

    private Instant resolveOrderTime(OrderEntity order, Instant fallback) {
        if (order == null) return fallback;
        try {
            if (order.getTimestamp() != null && order.getTimestamp() > 0) {
                return Instant.ofEpochMilli(order.getTimestamp());
            }
        } catch (Exception ignored) {
        }
        try {
            if (order.getCreatedAt() != null) {
                return order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private BigDecimal calcTpFromPct(BigDecimal entryPrice, BigDecimal tpPct) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (tpPct == null || tpPct.signum() <= 0) return null;

        BigDecimal k = tpPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        return entryPrice.multiply(BigDecimal.ONE.add(k))
                .setScale(Math.max(8, safeScale(entryPrice)), RoundingMode.HALF_UP);
    }

    private BigDecimal calcSlFromPct(BigDecimal entryPrice, BigDecimal slPct) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (slPct == null || slPct.signum() <= 0) return null;

        BigDecimal k = slPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        BigDecimal sl = entryPrice.multiply(BigDecimal.ONE.subtract(k))
                .setScale(Math.max(8, safeScale(entryPrice)), RoundingMode.HALF_UP);

        return sl.signum() > 0 ? sl : null;
    }

    private EntryRisk resolveEntryRisk(LocalState st,
                                       double rangePct,
                                       double score,
                                       Prediction pred) {
        if (st == null || st.cfg == null) return null;

        WindowScalpingStrategySettings cfg = st.cfg;

        BigDecimal staticTp = positiveOrNull(cfg.getTakeProfitPct());
        BigDecimal staticSl = positiveOrNull(cfg.getStopLossPct());

        if (!dynamicTpEnabled) {
            if (staticTp == null || staticSl == null) return null;
            return new EntryRisk(staticTp, staticSl, "static");
        }

        double tpCap = clampPct(
                staticTp != null ? staticTp.doubleValue() : dynamicTpMaxPct,
                dynamicTpMinPct,
                dynamicTpMaxPct
        );

        double slCap = clampPct(
                staticSl != null ? staticSl.doubleValue() : dynamicSlMaxPct,
                dynamicSlMinPct,
                dynamicSlMaxPct
        );

        double safeRangePct = Double.isFinite(rangePct) ? rangePct : 0.0;
        double dynSl = clampPct(
                safeRangePct * dynamicSlFromRangeFactor,
                dynamicSlMinPct,
                slCap
        );

        double dynTp = safeRangePct * dynamicTpFromRangeFactor;

        double feeFloorTp = minProfitAfterFeesPct;
        TradeExecutionServiceImpl exec = tradeExecImpl();
        BigDecimal roundTripFeePctBd = null;
        if (exec != null) {
            BigDecimal feeFloorTpBd = exec.resolveMinHealthyTpPct(
                    st.ss != null ? st.ss.getChatId() : null,
                    st.exchange,
                    st.network,
                    pctBd(dynSl)
            );
            if (feeFloorTpBd != null && feeFloorTpBd.signum() > 0) {
                feeFloorTp = Math.max(feeFloorTp, feeFloorTpBd.doubleValue());
            }
            roundTripFeePctBd = exec.estimateRoundTripFeePct(
                    st.ss != null ? st.ss.getChatId() : null,
                    st.exchange,
                    st.network
            );
        }

        double minTp = Math.max(dynamicTpMinPct, feeFloorTp);
        minTp = Math.max(minTp, dynSl * dynamicMinRiskReward);
        minTp = Math.max(minTp, safeRangePct * 0.35);

        double baseThreshold = resolveMlThreshold(st.ss);
        double effectiveThreshold = (st.lastAdaptiveMlThreshold != null ? st.lastAdaptiveMlThreshold : baseThreshold);

        if (pred != null && pred.ok) {
            if (pred.proba >= effectiveThreshold + 0.08) {
                dynTp *= 1.08;
            } else if (pred.proba <= effectiveThreshold + 0.02) {
                dynTp *= 0.92;
            }
        }

        if (Double.isFinite(score) && score >= 85.0) {
            dynTp *= 1.04;
        }

        dynTp = clampPct(dynTp, minTp, tpCap);

        if (!Double.isFinite(dynTp) || dynTp <= 0.0) return null;
        if (!Double.isFinite(dynSl) || dynSl <= 0.0) return null;

        double roundTripFeePct = 0.0;
        if (roundTripFeePctBd != null && roundTripFeePctBd.signum() > 0) {
            roundTripFeePct = roundTripFeePctBd.doubleValue();
        }

        double netRewardPct = dynTp - roundTripFeePct;
        double netRiskPct = dynSl + roundTripFeePct;
        double minNetRr = Double.isFinite(minNetRewardRiskForMarket) ? minNetRewardRiskForMarket : 1.35;
        if (minNetRr < 1.0) minNetRr = 1.0;

        if (netRewardPct <= 0.0) return null;
        if (netRiskPct > 0.0 && netRewardPct + 1e-12 < netRiskPct * minNetRr) {
            return null;
        }

        return new EntryRisk(pctBd(dynTp), pctBd(dynSl), "dynamic");
    }


    private EntryRisk alignRiskToWindowRoom(Long chatId,
                                            LocalState st,
                                            BigDecimal entryPrice,
                                            BigDecimal windowHigh,
                                            EntryRisk risk) {
        if (risk == null || risk.tpPct() == null || risk.slPct() == null) return null;
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        if (windowHigh == null || windowHigh.compareTo(entryPrice) <= 0) return null;

        double rawRoomPct = calcPctMove(entryPrice, windowHigh);
        if (!Double.isFinite(rawRoomPct) || rawRoomPct <= 0.0) return null;

        double projectedRoomPct = rawRoomPct;
        if (st != null && st.lastWindowHigh != null && st.lastWindowLow != null && st.lastWindowHigh.compareTo(st.lastWindowLow) > 0) {
            BigDecimal windowRange = st.lastWindowHigh.subtract(st.lastWindowLow);
            if (windowRange.signum() > 0) {
                double projectionPct = windowHighProjectionPctOfRange;
                if (!Double.isFinite(projectionPct) || projectionPct < 0.0) projectionPct = 0.35;
                BigDecimal projectedHigh = st.lastWindowHigh.add(
                        windowRange.multiply(BigDecimal.valueOf(Math.min(1.0, projectionPct)))
                );
                if (projectedHigh.compareTo(windowHigh) > 0) {
                    double projected = calcPctMove(entryPrice, projectedHigh);
                    if (Double.isFinite(projected) && projected > projectedRoomPct) {
                        projectedRoomPct = projected;
                    }
                }
            }
        }

        TradeExecutionServiceImpl exec = tradeExecImpl();
        double roundTripFeePct = 0.0;
        if (exec != null) {
            BigDecimal feePctBd = exec.estimateRoundTripFeePct(
                    (st != null && st.ss != null) ? st.ss.getChatId() : chatId,
                    st != null ? st.exchange : null,
                    st != null ? st.network : null
            );
            if (feePctBd != null && feePctBd.signum() > 0) {
                roundTripFeePct = feePctBd.doubleValue();
            }
        }

        double roomSafetyPct = Math.max(0.0, minRoomToWindowHighPct);
        roomSafetyPct = Math.min(roomSafetyPct, 0.03);
        double minRoomPct = Math.max(
                roomSafetyPct,
                roundTripFeePct + Math.max(0.0, minProfitAfterFeesPct * 0.70)
        );
        if (projectedRoomPct + 1e-12 < minRoomPct) {
            return null;
        }

        double roomUsage = clampUnit(tpRoomUsagePct);
        if (roomUsage <= 0.0) roomUsage = 1.0;
        roomUsage = Math.max(0.82, roomUsage);

        double effectiveRoomPct = projectedRoomPct * roomUsage;
        double cappedTpPct = Math.min(risk.tpPct().doubleValue(), effectiveRoomPct);
        if (!Double.isFinite(cappedTpPct) || cappedTpPct <= 0.0) {
            return null;
        }
        if (cappedTpPct + 1e-12 < minRoomPct) {
            return null;
        }

        double netRewardPct = cappedTpPct - roundTripFeePct;
        double requestedSlPct = risk.slPct().doubleValue();
        double netRiskPct = requestedSlPct + roundTripFeePct;

        double minRr = Double.isFinite(minRoomNetRewardRisk) ? minRoomNetRewardRisk : 0.70;
        if (minRr < 0.40) minRr = 0.40;
        minRr = Math.min(minRr, 0.55);

        if (netRewardPct <= 0.0) {
            return null;
        }

        BigDecimal adjustedTp = pctBd(cappedTpPct);
        BigDecimal adjustedSl = risk.slPct();

        if (netRiskPct > 0.0 && netRewardPct + 1e-12 < netRiskPct * minRr) {
            double maxAllowedSlPct = (netRewardPct / Math.max(0.35, minRr)) - roundTripFeePct;
            double slFloor = Math.max(0.05, dynamicSlMinPct);
            maxAllowedSlPct = Math.max(slFloor, maxAllowedSlPct);

            if (Double.isFinite(maxAllowedSlPct) && maxAllowedSlPct > 0.0 && maxAllowedSlPct + 1e-12 < requestedSlPct) {
                adjustedSl = pctBd(maxAllowedSlPct);
                netRiskPct = maxAllowedSlPct + roundTripFeePct;
            } else {
                return null;
            }
        }

        if (netRiskPct > 0.0 && netRewardPct + 1e-12 < netRiskPct * 0.35) {
            return null;
        }

        if (adjustedTp.compareTo(risk.tpPct()) == 0 && adjustedSl.compareTo(risk.slPct()) == 0) {
            return risk;
        }

        String suffix = projectedRoomPct > rawRoomPct + 1e-12 ? "_window_projected" : "_window";
        return new EntryRisk(adjustedTp, adjustedSl, risk.source() + suffix);
    }

    private void maybeMoveStopToBreakEven(Long chatId,
                                          LocalState st,
                                          String sym,
                                          BigDecimal price,
                                          Instant now) {
        if (!breakEvenEnabled) return;
        if (chatId == null || st == null || sym == null || price == null || now == null) return;
        if (!st.inPosition) return;
        if (st.entryPrice == null || st.tp == null || st.sl == null || st.entryQty == null) return;

        BigDecimal profitSpan = st.tp.subtract(st.entryPrice);
        if (profitSpan.signum() <= 0) return;

        BigDecimal triggerPrice = st.entryPrice.add(
                profitSpan.multiply(BigDecimal.valueOf(clampUnit(breakEvenTriggerToTpRatio)))
        );

        TradeExecutionServiceImpl exec = tradeExecImpl();
        BigDecimal roundTripFeePct = BigDecimal.ZERO;
        if (exec != null) {
            BigDecimal feePct = exec.estimateRoundTripFeePct(chatId, st.exchange, st.network);
            if (feePct != null && feePct.signum() > 0) {
                roundTripFeePct = feePct;
            }
        }

        BigDecimal feeAwareTriggerPrice = st.entryPrice;
        if (roundTripFeePct.signum() > 0) {
            BigDecimal feeCoverTriggerPct = roundTripFeePct.add(pctBd(breakEvenFeeCoverBufferPct));
            feeAwareTriggerPrice = st.entryPrice.multiply(
                    BigDecimal.ONE.add(feeCoverTriggerPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP))
            ).setScale(Math.max(8, safeScale(st.entryPrice)), RoundingMode.HALF_UP);
            if (feeAwareTriggerPrice.compareTo(triggerPrice) > 0) {
                triggerPrice = feeAwareTriggerPrice;
            }
        }

        if (price.compareTo(triggerPrice) < 0) return;

        BigDecimal protectedStopPct = pctBd(breakEvenOffsetPct);
        if (roundTripFeePct.signum() > 0) {
            BigDecimal feeAwareProtectedPct = roundTripFeePct.add(pctBd(breakEvenProtectedNetBufferPct));
            if (feeAwareProtectedPct.compareTo(protectedStopPct) > 0) {
                protectedStopPct = feeAwareProtectedPct;
            }
        }

        BigDecimal newSl = st.entryPrice.multiply(
                BigDecimal.ONE.add(protectedStopPct.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP))
        ).setScale(Math.max(8, safeScale(st.entryPrice)), RoundingMode.HALF_UP);

        if (newSl.compareTo(st.sl) <= 0) return;
        if (newSl.compareTo(price) >= 0) return;

        st.sl = newSl;

        try {
            String ex = normalizeExchangeOrNull(st.exchange);
            NetworkType net = st.network;
            String symbol = normalizeSymbolOrNull(sym);
            if (ex != null && net != null && symbol != null) {
                BigDecimal notional = st.entryPrice.multiply(st.entryQty).setScale(8, RoundingMode.HALF_UP);
                positionStore.markOpened(
                        chatId,
                        StrategyType.WINDOW_SCALPING,
                        ex,
                        net,
                        symbol,
                        st.entryPrice,
                        st.entryQty,
                        st.tp,
                        st.sl,
                        notional,
                        st.entryOrderId,
                        st.lastEntryAt != null ? st.lastEntryAt : now
                );
            }
        } catch (Exception ignored) {
        }

        publishPositionLines(chatId, sym, st);

        log.info("[WINDOW] 🔒 BE chatId={} sym={} entry={} tp={} newSl={} trigger={}",
                chatId,
                sym,
                fmtBd(st.entryPrice),
                fmtBd(st.tp),
                fmtBd(st.sl),
                fmtBd(triggerPrice));
    }

    private void rememberEntryCandle(LocalState st, Instant time) {
        if (st == null || time == null) return;
        String tf = normalizeTimeframeOrNull(st.entryCandleTimeframe != null ? st.entryCandleTimeframe : st.timeframe);
        if (tf == null) return;

        long openMs = candleOpenTimeMs(time.toEpochMilli(), tf);
        if (openMs <= 0) return;

        st.entryCandleOpenTimeMs = openMs;
        st.entryCandleTimeframe = tf;
    }

    private boolean isSameCandleAsEntry(LocalState st, Instant now) {
        if (st == null || now == null) return false;
        if (st.entryCandleOpenTimeMs == null) return false;

        String tf = normalizeTimeframeOrNull(st.entryCandleTimeframe != null ? st.entryCandleTimeframe : st.timeframe);
        if (tf == null) return false;

        long openMs = candleOpenTimeMs(now.toEpochMilli(), tf);
        return openMs > 0 && Objects.equals(openMs, st.entryCandleOpenTimeMs);
    }

    private long candleOpenTimeMs(long epochMs, String tf) {
        long tfMs = timeframeToMillis(tf);
        if (epochMs <= 0 || tfMs <= 0) return -1L;
        return (epochMs / tfMs) * tfMs;
    }

    private long timeframeToMillis(String tf) {
        if (tf == null) return -1L;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1L;

        long mul;
        char unit = s.charAt(s.length() - 1);
        String numPart = s.substring(0, s.length() - 1);

        try {
            long n = Long.parseLong(numPart);
            if (n <= 0) return -1L;

            switch (unit) {
                case 's' -> mul = 1_000L;
                case 'm' -> mul = 60_000L;
                case 'h' -> mul = 3_600_000L;
                case 'd' -> mul = 86_400_000L;
                case 'w' -> mul = 604_800_000L;
                default -> {
                    return -1L;
                }
            }

            return n * mul;
        } catch (Exception e) {
            return -1L;
        }
    }

    private double calcPctMove(BigDecimal from,
                               BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0) return Double.NaN;
        return to.subtract(from)
                .divide(from, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private BigDecimal pctBd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    private double clampPct(double value, double min, double max) {
        double v = Double.isFinite(value) ? value : min;
        double lo = Double.isFinite(min) ? min : v;
        double hi = Double.isFinite(max) ? max : v;
        if (hi < lo) hi = lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private double clampUnit(double v) {
        if (!Double.isFinite(v)) return 0.45;
        if (v < 0.05) return 0.05;
        if (v > 0.95) return 0.95;
        return v;
    }

    @EventListener
    public void onWindowScalpingSettingsUpdated(WindowScalpingSettingsUpdatedEvent e) {
        LocalState st = states.get(e.chatId());
        if (st == null) return;
        synchronized (st) {
            st.lastSettingsLoadAt = Instant.EPOCH;
            st.lastFingerprint = null;
            resetMlCache(st);
            resetRestoreThrottleState(st);
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
            resetMlCache(st);
            resetRestoreThrottleState(st);
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

        /**
         * ВАЖНО:
         * mlConfidence — runtime/live значение.
         * Его нельзя постоянно писать в StrategySettings в БД,
         * иначе UI save + strategy runtime начинают конфликтовать по optimistic lock.
         *
         * Поэтому:
         * 1. держим значение только в памяти стратегии
         * 2. в entity кладём только в runtime-объект, без save(...)
         */
        st.lastMlConfidenceSaveAt = now;
        st.lastMlConfidenceSaved = proba;
        ss.setMlConfidence(BigDecimal.valueOf(proba));
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

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
    }
}












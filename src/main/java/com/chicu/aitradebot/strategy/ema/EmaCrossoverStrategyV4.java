package com.chicu.aitradebot.strategy.ema;

import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
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
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import com.chicu.aitradebot.trade.TradeExecutionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.EMA_CROSSOVER)
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaCrossoverStrategyV4 implements
        TradingStrategy,
        AiStrategyOrchestrator.PriceUpdateAware,
        AiStrategyOrchestrator.CandleCloseAware,
        AiStrategyOrchestrator.PrepareStartAware {

    private static final long SETTINGS_REFRESH_MS = 10_000L;
    private static final long HOLD_THROTTLE_MS = 3_000L;
    private static final long PREDICT_THROTTLE_MS = 1_200L;
    private static final long RETRAIN_THROTTLE_MS = 30_000L;
    private static final long ML_ADAPT_COOLDOWN_MS = 180_000L;
    private static final long ML_LOG_THROTTLE_MS = 30_000L;

    private static final int ML_WINDOW = 12;
    private static final int ML_MIN_WINDOW = 6;

    private static final double ML_LOW_RATE_TRIGGER = 0.75d;
    private static final double ML_FAILOPEN_RATE_TRIGGER = 0.35d;
    private static final double ML_STRONG_LOW_PROBA = 0.15d;

    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("0.80");
    private static final BigDecimal DEFAULT_MIN_DIFF_PCT = new BigDecimal("0.01");
    private static final BigDecimal GATE_RELAX_STEP = new BigDecimal("0.05");
    private static final BigDecimal GATE_RELAX_MIN = new BigDecimal("0.25");

    private final StrategyLivePublisher live;
    private final StrategySettingsService strategySettingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;
    private final ObjectProvider<MlGateway> mlGatewayProvider;
    private final ObjectProvider<EmaMlPreparationService> preparationServiceProvider;
    private final ObjectProvider<AutoTunerOrchestrator> autoTunerProvider;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static final class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        EmaCrossoverStrategySettings cfg;

        Long ssVersion;
        Long cfgVersion;
        Instant lastSettingsSyncAt;

        String symbol;
        String exchange;
        NetworkType network;
        String timeframe;

        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tpPrice;
        BigDecimal slPrice;

        Double fastEma;
        Double slowEma;
        Double prevFastEma;
        Double prevSlowEma;
        BigDecimal prevClose;

        int barsSeen;
        int bullishConfirmBars;
        int bearishConfirmBars;

        Long lastProcessedBarOpenTime;
        Long currentSyntheticBarOpenTime;
        BigDecimal currentSyntheticBarClose;

        Instant lastHoldAt;
        String lastHoldReason;

        Instant lastPredictAt;
        BigDecimal lastPredictPrice;

        Instant lastRetrainAt;
        Instant lastMlAdaptiveAt;
        Instant lastMlLowLogAt;
        Instant lastMlFailLogAt;

        final Deque<BigDecimal> recentCloses = new ArrayDeque<>();
        final Deque<Double> recentMlProbas = new ArrayDeque<>();
        final Deque<Integer> recentMlOutcomes = new ArrayDeque<>();
    }

    private record MlWindowStats(int total,
                                 int passCount,
                                 int belowCount,
                                 int failOpenCount,
                                 double avgProba,
                                 double belowRate,
                                 double failOpenRate,
                                 double threshold) {
    }

    private static final class Prediction {
        final boolean ok;
        final boolean failOpen;
        final double proba;
        final double threshold;
        final String modelKey;
        final String modelVersion;
        final String error;

        private Prediction(boolean ok, boolean failOpen, double proba, double threshold, String modelKey, String modelVersion, String error) {
            this.ok = ok;
            this.failOpen = failOpen;
            this.proba = proba;
            this.threshold = threshold;
            this.modelKey = modelKey;
            this.modelVersion = modelVersion;
            this.error = error;
        }

        static Prediction ok(double proba, double threshold, String modelKey, String modelVersion) {
            return new Prediction(true, false, proba, threshold, modelKey, modelVersion, null);
        }

        static Prediction failOpen(String error, double threshold) {
            return new Prediction(false, true, Double.NaN, threshold, null, null, error);
        }

        static Prediction reject(String error, double threshold) {
            return new Prediction(false, false, Double.NaN, threshold, null, null, error);
        }
    }

    @Override
    public void start(Long chatId, String ignored) {
        if (chatId == null || chatId <= 0) {
            log.warn("[EMA] start skipped: bad chatId={}", chatId);
            return;
        }

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
        EmaCrossoverStrategySettings cfg = emaSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.ss = ss;
        st.cfg = cfg;
        st.ssVersion = safeLong(strategySettingsService.getVersion(chatId, StrategyType.EMA_CROSSOVER), 0L);
        st.cfgVersion = safeLong(emaSettingsService.getVersion(chatId), 0L);
        st.lastSettingsSyncAt = Instant.now();

        syncRuntimeContext(st, ss);
        warmupFromHistory(chatId, st);
        states.put(chatId, st);

        maybeRestoreOpenPosition(chatId, st);
        PrepareStatus prepareStatus = resolveStartStatus(st);

        log.info("[EMA] ▶ START chatId={} ex={} net={} symbol={} tf={} mode={} prepare={} emaFast={} emaSlow={} confirmBars={} tpPct={} slPct={} mlGate={} modelVer={}",
                chatId,
                safe(st.exchange),
                st.network,
                safe(st.symbol),
                safe(st.timeframe),
                modeName(st.ss),
                prepareStatus.message,
                st.cfg != null ? st.cfg.getEmaFast() : null,
                st.cfg != null ? st.cfg.getEmaSlow() : null,
                st.cfg != null ? st.cfg.getConfirmBars() : null,
                st.cfg != null ? st.cfg.getTakeProfitPct() : null,
                st.cfg != null ? st.cfg.getStopLossPct() : null,
                (st.ss != null && st.ss.isMlGateEnabled()),
                st.ss != null ? safe(st.ss.getMlModelVersion()) : "null");

        safeLive(() -> live.pushState(chatId, StrategyType.EMA_CROSSOVER, st.symbol, true));
        if (st.inPosition) {
            pushPositionVisuals(chatId, st, st.entryPrice, st.entryQty, st.tpPrice, st.slPrice, null, false);
            pushHold(chatId, st, "position_restored");
        } else if (!isWarmRuntime(st)) {
            pushHold(chatId, st, "warming_up");
        } else {
            pushHold(chatId, st, prepareStatus.holdReason);
        }
    }

    @Override
    public void stop(Long chatId, String ignored) {
        LocalState st = states.remove(chatId);
        if (st == null) {
            return;
        }

        clearPositionVisuals(chatId, st);
        safeLive(() -> live.pushState(chatId, StrategyType.EMA_CROSSOVER, st.symbol, false));
        log.info("[EMA] ⏹ STOP chatId={} ex={} net={} symbol={}",
                chatId, safe(st.exchange), st.network, safe(st.symbol));
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
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {
        if (chatId == null || price == null || price.signum() <= 0) return;

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, ts != null ? ts : Instant.now());
            maybeRestoreOpenPosition(chatId, st);

            if (isBlank(st.timeframe)) {
                pushHold(chatId, st, "no_timeframe");
                return;
            }

            long tfMs = parseTimeframeMs(st.timeframe);
            if (tfMs <= 0L) {
                pushHold(chatId, st, "bad_timeframe");
                return;
            }

            long nowMs = ts != null ? ts.toEpochMilli() : System.currentTimeMillis();
            long openTime = (nowMs / tfMs) * tfMs;

            if (st.currentSyntheticBarOpenTime == null) {
                st.currentSyntheticBarOpenTime = openTime;
                st.currentSyntheticBarClose = price;
                return;
            }

            if (Objects.equals(st.currentSyntheticBarOpenTime, openTime)) {
                st.currentSyntheticBarClose = price;
                return;
            }

            Long closedBarOpenTime = st.currentSyntheticBarOpenTime;
            BigDecimal closedBarClose = st.currentSyntheticBarClose;

            st.currentSyntheticBarOpenTime = openTime;
            st.currentSyntheticBarClose = price;

            if (closedBarOpenTime != null && closedBarClose != null && closedBarClose.signum() > 0) {
                processClosedBar(chatId, st, normalizeSymbol(symbolFromTick, st.symbol), closedBarClose,
                        closedBarOpenTime, Instant.ofEpochMilli(closedBarOpenTime + tfMs - 1L));
            }
        }
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
        if (type != StrategyType.EMA_CROSSOVER) return;
        onPriceUpdate(chatId, symbol, price, tradeTsMs > 0 ? Instant.ofEpochMilli(tradeTsMs) : Instant.now());

        LocalState st = states.get(chatId);
        if (st == null) return;

        synchronized (st) {
            if (!isBlank(exchange)) st.exchange = normalizeExchange(exchange, st.exchange);
            if (network != null) st.network = network;
            if (!isBlank(timeframe)) st.timeframe = normalizeTimeframe(timeframe, st.timeframe);
            if (!isBlank(symbol)) st.symbol = normalizeSymbol(symbol, st.symbol);
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
        if (type != StrategyType.EMA_CROSSOVER) return;
        if (kline == null) return;

        BigDecimal close = kline.getClose();
        if (close == null || close.signum() <= 0) return;

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, Instant.now());
            maybeRestoreOpenPosition(chatId, st);

            if (!isBlank(exchange)) st.exchange = normalizeExchange(exchange, st.exchange);
            if (network != null) st.network = network;
            if (!isBlank(timeframe)) st.timeframe = normalizeTimeframe(timeframe, st.timeframe);
            if (!isBlank(symbol)) st.symbol = normalizeSymbol(symbol, st.symbol);

            long openTime = kline.getOpenTime();
            if (openTime <= 0L) {
                long closeTime = kline.getCloseTime();
                long tfMs = parseTimeframeMs(st.timeframe);
                if (closeTime > 0L && tfMs > 0L) {
                    openTime = closeTime - tfMs + 1L;
                } else {
                    openTime = System.currentTimeMillis();
                }
            }

            long closeTime = kline.getCloseTime() > 0L ? kline.getCloseTime() : openTime;
            processClosedBar(chatId, st, normalizeSymbol(symbol, st.symbol), close,
                    openTime, Instant.ofEpochMilli(closeTime));
        }
    }

    private void processClosedBar(Long chatId,
                                  LocalState st,
                                  String symbol,
                                  BigDecimal close,
                                  long barOpenTime,
                                  Instant eventTime) {
        if (st == null || !st.active) return;
        if (close == null || close.signum() <= 0) return;

        refreshSettingsIfNeeded(chatId, st, eventTime != null ? eventTime : Instant.now());
        syncRuntimeContext(st, st.ss);
        maybeRestoreOpenPosition(chatId, st);

        if (st.lastProcessedBarOpenTime != null && st.lastProcessedBarOpenTime == barOpenTime) {
            return;
        }
        st.lastProcessedBarOpenTime = barOpenTime;

        String sym = normalizeSymbol(symbol, st.symbol);
        if (sym != null) st.symbol = sym;

        if (st.ss == null || st.cfg == null) {
            pushHold(chatId, st, "no_settings");
            return;
        }

        int fastPeriod = clampInt(nvl(st.cfg.getEmaFast(), 9), 1, 300);
        int slowPeriod = clampInt(nvl(st.cfg.getEmaSlow(), 21), 2, 600);
        if (slowPeriod <= fastPeriod) slowPeriod = fastPeriod + 1;

        int confirmBars = clampInt(nvl(st.cfg.getConfirmBars(), 1), 1, 10);
        double maxSpreadPct = st.cfg.getMaxSpreadPct() != null ? Math.max(0.0d, st.cfg.getMaxSpreadPct()) : 0.08d;

        double closeValue = close.doubleValue();
        if (!Double.isFinite(closeValue) || closeValue <= 0.0d) {
            pushHold(chatId, st, "bad_close");
            return;
        }

        st.barsSeen++;
        st.prevFastEma = st.fastEma;
        st.prevSlowEma = st.slowEma;
        st.prevClose = rememberClose(st.recentCloses, close, 6);

        st.fastEma = nextEma(st.fastEma, closeValue, fastPeriod);
        st.slowEma = nextEma(st.slowEma, closeValue, slowPeriod);

        if (st.barsSeen < slowPeriod || st.prevFastEma == null || st.prevSlowEma == null) {
            pushHold(chatId, st, "warming_up");
            return;
        }

        boolean bullNow = st.fastEma > st.slowEma;
        boolean bearNow = st.fastEma < st.slowEma;

        if (bullNow) {
            st.bullishConfirmBars++;
            st.bearishConfirmBars = 0;
        } else if (bearNow) {
            st.bearishConfirmBars++;
            st.bullishConfirmBars = 0;
        }

        BigDecimal tpPct = resolveTakeProfitPct(st);
        BigDecimal slPct = resolveStopLossPct(st);
        BigDecimal diffPct = diffPct(st.fastEma, st.slowEma);

        if (maxSpreadPct > 0.0d && diffPct.doubleValue() > maxSpreadPct) {
            pushHold(chatId, st, "spread_too_wide");
            return;
        }

        if (st.inPosition) {
            tryAutoExitIfHit(chatId, st, close, eventTime);
            if (st.inPosition && st.bearishConfirmBars >= confirmBars) {
                forceExitOnBearish(chatId, st, close, eventTime);
            }
            if (st.inPosition) {
                pushHold(chatId, st, "in_position");
            }
            return;
        }

        if (!bullNow) {
            pushHold(chatId, st, "bearish_regime");
            return;
        }

        if (st.bullishConfirmBars < confirmBars) {
            pushHold(chatId, st, "waiting_confirm");
            return;
        }

        if (st.symbol == null || st.exchange == null || st.network == null) {
            pushHold(chatId, st, "runtime_context_missing");
            return;
        }

        if (shouldUseMlGate(st.ss)) {
            Map<String, Object> features = buildMlFeatures(st, close, diffPct);
            Prediction prediction = tryPredict(chatId, st, close, eventTime, features);
            rememberMlOutcome(st, prediction);
            syncMlConfidence(st, prediction);

            if (!prediction.ok) {
                maybeHandleMlDegradation(chatId, st, prediction, eventTime);
                if (!prediction.failOpen) {
                    log.warn("[EMA] 🤖 ML reject chatId={} sym={} reason={} thr={}",
                            chatId, safe(st.symbol), safe(prediction.error), fmt(prediction.threshold));
                    pushHold(chatId, st, "ml_reject");
                    return;
                }

                logMlFailOpen(chatId, st, prediction);
                pushHold(chatId, st, "ml_fail_open");
                return;
            }

            if (prediction.proba < prediction.threshold) {
                maybeHandleMlDegradation(chatId, st, prediction, eventTime);
                logMlBelow(chatId, st, prediction);
                pushHold(chatId, st, "ml_below_threshold");
                return;
            }
        }

        tryEnter(chatId, st, close, diffPct, tpPct, slPct, eventTime);
    }

    private void tryEnter(Long chatId,
                          LocalState st,
                          BigDecimal price,
                          BigDecimal diffPct,
                          BigDecimal tpPct,
                          BigDecimal slPct,
                          Instant eventTime) {
        Object res = tradeExecutionService.executeEntry(
                chatId,
                StrategyType.EMA_CROSSOVER,
                st.symbol,
                price,
                positiveOrDefault(diffPct, DEFAULT_MIN_DIFF_PCT),
                eventTime != null ? eventTime : Instant.now(),
                st.ss,
                tpPct,
                slPct
        );

        if (!resultOk(res)) {
            String err = resultError(res);
            log.warn("[EMA] ⛔ ENTRY rejected chatId={} ex={} net={} symbol={} err={} price={} diffPct={} tpPct={} slPct={}",
                    chatId,
                    safe(st.exchange),
                    st.network,
                    safe(st.symbol),
                    safe(err),
                    fmtBd(price),
                    fmtBd(diffPct),
                    fmtBd(tpPct),
                    fmtBd(slPct));
            pushHold(chatId, st, "entry_rejected");
            return;
        }

        BigDecimal executedPrice = firstNonNull(
                resultBigDecimal(res, "executedPrice", "getExecutedPrice", "price", "getPrice"),
                price
        );

        BigDecimal executedQty = resultBigDecimal(res, "executedQty", "getExecutedQty", "qty", "getQty", "quantity", "getQuantity");
        BigDecimal tp = resultBigDecimal(res, "tp", "getTp", "takeProfit", "getTakeProfit");
        BigDecimal sl = resultBigDecimal(res, "sl", "getSl", "stopLoss", "getStopLoss");

        if (tp == null && executedPrice != null) {
            tp = calcTp(executedPrice, tpPct);
        }
        if (sl == null && executedPrice != null) {
            sl = calcSl(executedPrice, slPct);
        }

        st.inPosition = true;
        st.entryQty = executedQty;
        st.entryPrice = executedPrice;
        st.tpPrice = tp;
        st.slPrice = sl;

        log.info("[EMA] ✅ BUY chatId={} ex={} net={} symbol={} entryPrice={} qty={} tp={} sl={} emaFast={} emaSlow={} diffPct={}",
                chatId,
                safe(st.exchange),
                st.network,
                safe(st.symbol),
                fmtBd(st.entryPrice),
                fmtBd(st.entryQty),
                fmtBd(st.tpPrice),
                fmtBd(st.slPrice),
                fmtDouble(st.fastEma),
                fmtDouble(st.slowEma),
                fmtBd(diffPct));

        pushPositionVisuals(chatId, st, st.entryPrice, st.entryQty, st.tpPrice, st.slPrice, eventTime, true);
        safeLive(() -> live.pushSignal(chatId, StrategyType.EMA_CROSSOVER, st.symbol, null,
                Signal.hold("EMA BUY confirmed")));
    }

    private void tryAutoExitIfHit(Long chatId, LocalState st, BigDecimal price, Instant eventTime) {
        Object res = tradeExecutionService.executeExitIfHit(
                chatId,
                StrategyType.EMA_CROSSOVER,
                st.symbol,
                price,
                eventTime != null ? eventTime : Instant.now(),
                true,
                st.entryQty,
                st.tpPrice,
                st.slPrice,
                st.exchange,
                st.network
        );

        if (!resultOk(res)) {
            return;
        }

        log.info("[EMA] ✅ EXIT by TP/SL chatId={} ex={} net={} symbol={} price={} tp={} sl={}",
                chatId,
                safe(st.exchange),
                st.network,
                safe(st.symbol),
                fmtBd(price),
                fmtBd(st.tpPrice),
                fmtBd(st.slPrice));

        publishExitVisuals(chatId, st, price, eventTime, st.tpPrice != null && price.compareTo(st.tpPrice) >= 0 ? "TP" : "SL");
        clearPosition(st);
        maybeRetrainAfterClose(chatId, st, "after_close_train");
        safeLive(() -> live.pushSignal(chatId, StrategyType.EMA_CROSSOVER, st.symbol, null,
                Signal.hold("EMA EXIT by TP/SL")));
    }

    private void forceExitOnBearish(Long chatId, LocalState st, BigDecimal price, Instant eventTime) {
        boolean closed = false;

        if (tradeExecutionService instanceof TradeExecutionServiceImpl impl) {
            Object res = impl.executeExitNow(
                    chatId,
                    StrategyType.EMA_CROSSOVER,
                    st.symbol,
                    price,
                    eventTime != null ? eventTime : Instant.now(),
                    st.entryQty,
                    st.tpPrice != null ? st.tpPrice : price,
                    st.slPrice != null ? st.slPrice : BigDecimal.ZERO,
                    st.exchange,
                    st.network,
                    "EMA_BEARISH_CROSS"
            );
            closed = resultOk(res);
        }

        if (!closed) {
            Object res = tradeExecutionService.executeExitIfHit(
                    chatId,
                    StrategyType.EMA_CROSSOVER,
                    st.symbol,
                    price,
                    eventTime != null ? eventTime : Instant.now(),
                    true,
                    st.entryQty,
                    price,
                    BigDecimal.ZERO,
                    st.exchange,
                    st.network
            );
            closed = resultOk(res);
        }

        if (!closed) {
            log.warn("[EMA] ⛔ FORCE EXIT failed chatId={} ex={} net={} symbol={} price={}",
                    chatId, safe(st.exchange), st.network, safe(st.symbol), fmtBd(price));
            pushHold(chatId, st, "exit_rejected");
            return;
        }

        log.info("[EMA] ✅ FORCE EXIT on bearish confirm chatId={} ex={} net={} symbol={} price={} confirmBars={}",
                chatId, safe(st.exchange), st.network, safe(st.symbol), fmtBd(price), st.bearishConfirmBars);

        publishExitVisuals(chatId, st, price, eventTime, "EMA_BEARISH_CROSS");
        clearPosition(st);
        maybeRetrainAfterClose(chatId, st, "after_close_train");
        safeLive(() -> live.pushSignal(chatId, StrategyType.EMA_CROSSOVER, st.symbol, null,
                Signal.hold("EMA bearish exit")));
    }

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st == null) return;

        Instant effectiveNow = now != null ? now : Instant.now();
        boolean byTime = st.lastSettingsSyncAt == null
                || Duration.between(st.lastSettingsSyncAt, effectiveNow).toMillis() >= SETTINGS_REFRESH_MS;

        Long currentCfgVersion = safeLong(emaSettingsService.getVersion(chatId), 0L);
        Long currentSsVersion = safeLong(strategySettingsService.getVersion(chatId, StrategyType.EMA_CROSSOVER), 0L);

        boolean byVersion = !Objects.equals(st.cfgVersion, currentCfgVersion)
                || !Objects.equals(st.ssVersion, currentSsVersion);

        if (!byTime && !byVersion) {
            return;
        }

        st.cfg = emaSettingsService.getOrCreate(chatId);
        st.ss = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
        st.cfgVersion = currentCfgVersion;
        st.ssVersion = currentSsVersion;
        st.lastSettingsSyncAt = effectiveNow;

        syncRuntimeContext(st, st.ss);
    }

    private void syncRuntimeContext(LocalState st, StrategySettings ss) {
        if (st == null || ss == null) return;
        st.symbol = normalizeSymbol(ss.getSymbol(), st.symbol);
        st.exchange = normalizeExchange(ss.getExchangeName(), st.exchange);
        st.network = ss.getNetworkType() != null ? ss.getNetworkType() : st.network;
        st.timeframe = normalizeTimeframe(ss.getTimeframe(), st.timeframe);
    }

    private void maybeRestoreOpenPosition(Long chatId, LocalState st) {
        if (st == null || st.inPosition) return;
        if (chatId == null || chatId <= 0) return;
        if (isBlank(st.exchange) || st.network == null || isBlank(st.symbol)) return;

        try {
            Optional<PositionStore.PositionSnapshot> opt = positionStore.getPosition(
                    chatId,
                    StrategyType.EMA_CROSSOVER,
                    st.exchange,
                    st.network,
                    st.symbol
            );
            if (opt.isEmpty()) return;

            PositionStore.PositionSnapshot snap = opt.get();
            if (snap.qty() == null || snap.qty().signum() <= 0) return;

            st.inPosition = true;
            st.entryQty = snap.qty();
            st.entryPrice = snap.entryPrice();
            st.tpPrice = snap.tp();
            st.slPrice = snap.sl();

            log.info("[EMA] ♻ POSITION RESTORED chatId={} ex={} net={} symbol={} qty={} entry={} tp={} sl={}",
                    chatId,
                    safe(st.exchange),
                    st.network,
                    safe(st.symbol),
                    fmtBd(st.entryQty),
                    fmtBd(st.entryPrice),
                    fmtBd(st.tpPrice),
                    fmtBd(st.slPrice));
        } catch (Exception e) {
            log.debug("[EMA] position restore skipped chatId={} err={}", chatId, e.toString());
        }
    }

    private void warmupFromHistory(Long chatId, LocalState st) {
        if (chatId == null || chatId <= 0 || st == null || st.ss == null || st.cfg == null) {
            return;
        }

        EmaMlPreparationService prep = preparationServiceProvider != null ? preparationServiceProvider.getIfAvailable() : null;
        if (prep == null) {
            return;
        }

        int requestedLimit = clampInt(nvl(st.ss.getCachedCandlesLimit(), 1000), 50, 5000);

        List<EmaMlPreparationService.CandlePoint> points;
        try {
            points = prep.loadCandlePoints(chatId, st.ss, requestedLimit);
        } catch (Exception e) {
            log.debug("[EMA] warmup skipped chatId={} ex={} net={} symbol={} tf={} err={}",
                    chatId,
                    safe(st.exchange),
                    st.network,
                    safe(st.symbol),
                    safe(st.timeframe),
                    e.toString());
            return;
        }

        if (points == null || points.isEmpty()) {
            return;
        }

        List<EmaMlPreparationService.CandlePoint> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> Long.compare(a.openTimeMs(), b.openTimeMs()));

        int fastPeriod = clampInt(nvl(st.cfg.getEmaFast(), 9), 1, 300);
        int slowPeriod = clampInt(nvl(st.cfg.getEmaSlow(), 21), 2, 600);
        if (slowPeriod <= fastPeriod) {
            slowPeriod = fastPeriod + 1;
        }

        st.fastEma = null;
        st.slowEma = null;
        st.prevFastEma = null;
        st.prevSlowEma = null;
        st.prevClose = null;
        st.barsSeen = 0;
        st.bullishConfirmBars = 0;
        st.bearishConfirmBars = 0;
        st.lastProcessedBarOpenTime = null;
        st.currentSyntheticBarOpenTime = null;
        st.currentSyntheticBarClose = null;
        st.recentCloses.clear();

        for (EmaMlPreparationService.CandlePoint point : sorted) {
            double closeValue = point.close();
            if (!Double.isFinite(closeValue) || closeValue <= 0.0d) {
                continue;
            }

            BigDecimal close = BigDecimal.valueOf(closeValue);

            st.barsSeen++;
            st.prevFastEma = st.fastEma;
            st.prevSlowEma = st.slowEma;
            st.prevClose = rememberClose(st.recentCloses, close, 6);

            st.fastEma = nextEma(st.fastEma, closeValue, fastPeriod);
            st.slowEma = nextEma(st.slowEma, closeValue, slowPeriod);

            if (st.barsSeen >= slowPeriod && st.prevFastEma != null && st.prevSlowEma != null) {
                boolean bullNow = st.fastEma != null && st.slowEma != null && st.fastEma > st.slowEma;
                boolean bearNow = st.fastEma != null && st.slowEma != null && st.fastEma < st.slowEma;

                if (bullNow) {
                    st.bullishConfirmBars++;
                    st.bearishConfirmBars = 0;
                } else if (bearNow) {
                    st.bearishConfirmBars++;
                    st.bullishConfirmBars = 0;
                }
            }

            st.lastProcessedBarOpenTime = point.openTimeMs();
        }

        log.info("[EMA] 🔥 WARMUP DONE chatId={} ex={} net={} symbol={} tf={} source=history candles={} barsSeen={} lastBarOpen={} fastEma={} slowEma={} prevFast={} prevSlow={} bullConfirm={} bearConfirm={}",
                chatId,
                safe(st.exchange),
                st.network,
                safe(st.symbol),
                safe(st.timeframe),
                sorted.size(),
                st.barsSeen,
                st.lastProcessedBarOpenTime,
                fmtDouble(st.fastEma),
                fmtDouble(st.slowEma),
                fmtDouble(st.prevFastEma),
                fmtDouble(st.prevSlowEma),
                st.bullishConfirmBars,
                st.bearishConfirmBars);
    }

    private boolean isWarmRuntime(LocalState st) {
        if (st == null || st.cfg == null) {
            return false;
        }

        int fastPeriod = clampInt(nvl(st.cfg.getEmaFast(), 9), 1, 300);
        int slowPeriod = clampInt(nvl(st.cfg.getEmaSlow(), 21), 2, 600);
        if (slowPeriod <= fastPeriod) {
            slowPeriod = fastPeriod + 1;
        }

        return st.barsSeen >= slowPeriod
                && st.fastEma != null
                && st.slowEma != null
                && st.prevFastEma != null
                && st.prevSlowEma != null;
    }

    @Override
    public AiStrategyOrchestrator.PreparationResult prepareStart(long chatId,
                                                                 StrategyType type,
                                                                 String symbol,
                                                                 String timeframe,
                                                                 String exchange,
                                                                 NetworkType network) {
        if (type != StrategyType.EMA_CROSSOVER) {
            return AiStrategyOrchestrator.PreparationResult.ok("skip:type_mismatch");
        }

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
        EmaCrossoverStrategySettings cfg = emaSettingsService.getOrCreate(chatId);
        if (ss == null) {
            return AiStrategyOrchestrator.PreparationResult.fail("settings_null");
        }

        EmaMlPreparationService prep = preparationServiceProvider != null ? preparationServiceProvider.getIfAvailable() : null;
        if (prep == null) {
            return AiStrategyOrchestrator.PreparationResult.fail("prep_service_missing");
        }

        try {
            EmaMlPreparationService.PrepareResult train = prep.prepare(chatId, ss, cfg, "prepare_start_train");
            if (!train.ok()) {
                return AiStrategyOrchestrator.PreparationResult.fail("train_failed:" + safe(train.reason()));
            }
        } catch (Exception e) {
            log.warn("[EMA] prepareStart train failed chatId={} err={}", chatId, e.toString());
            return AiStrategyOrchestrator.PreparationResult.fail("train_exception");
        }

        try {
            AutoTunerOrchestrator tuner = autoTunerProvider != null ? autoTunerProvider.getIfAvailable() : null;
            if (tuner != null) {
                TuningResult tune = tuner.tune(TuningRequest.builder()
                        .chatId(chatId)
                        .strategyType(StrategyType.EMA_CROSSOVER)
                        .exchange(exchange)
                        .network(network)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .candlesLimit(ss.getCachedCandlesLimit())
                        .reason("prepare_start_validate")
                        .build());
                log.info("[EMA] 🧠 prepare tune chatId={} sym={} applied={} reason={} scoreBefore={} scoreAfter={}",
                        chatId,
                        safe(symbol),
                        (tune != null && tune.applied()),
                        tune != null ? safe(tune.reason()) : "null",
                        tune != null ? safe(String.valueOf(tune.scoreBefore())) : "null",
                        tune != null ? safe(String.valueOf(tune.scoreAfter())) : "null");
            }
        } catch (Exception e) {
            log.warn("[EMA] prepareStart tune failed chatId={} err={}", chatId, e.toString());
        }

        return AiStrategyOrchestrator.PreparationResult.ok("ok");
    }

    private PrepareStatus resolveStartStatus(LocalState st) {
        if (st == null || st.ss == null) {
            return new PrepareStatus("skipped:no_settings", "started");
        }
        if (!st.ss.isAiMode()) {
            return new PrepareStatus("skipped:not_ai_mode", "started");
        }
        if (isBlank(st.ss.getMlModelVersion())) {
            return new PrepareStatus("awaiting_prepared_model", "awaiting_prepared_model");
        }
        return new PrepareStatus("ok", "prepared_and_started");
    }

    private void rememberMlOutcome(LocalState st, Prediction prediction) {
        if (st == null || prediction == null) return;

        int outcome;
        double proba;
        if (!prediction.ok) {
            outcome = -1;
            proba = Double.NaN;
        } else if (prediction.proba < prediction.threshold) {
            outcome = 0;
            proba = prediction.proba;
        } else {
            outcome = 1;
            proba = prediction.proba;
        }

        st.recentMlOutcomes.addLast(outcome);
        while (st.recentMlOutcomes.size() > ML_WINDOW) {
            st.recentMlOutcomes.removeFirst();
        }

        st.recentMlProbas.addLast(proba);
        while (st.recentMlProbas.size() > ML_WINDOW) {
            st.recentMlProbas.removeFirst();
        }
    }

    private MlWindowStats summarizeMlWindow(LocalState st, double threshold) {
        if (st == null || st.recentMlOutcomes.isEmpty()) {
            return new MlWindowStats(0, 0, 0, 0, 0.0d, 0.0d, 0.0d, threshold);
        }

        int total = 0;
        int pass = 0;
        int below = 0;
        int failOpen = 0;
        int probaCount = 0;
        double probaSum = 0.0d;

        var outcomeIt = st.recentMlOutcomes.iterator();
        var probaIt = st.recentMlProbas.iterator();
        while (outcomeIt.hasNext()) {
            int outcome = outcomeIt.next();
            double proba = probaIt.hasNext() ? probaIt.next() : Double.NaN;
            total++;
            if (outcome > 0) pass++;
            else if (outcome == 0) below++;
            else failOpen++;
            if (Double.isFinite(proba)) {
                probaSum += proba;
                probaCount++;
            }
        }

        double avgProba = probaCount > 0 ? probaSum / probaCount : 0.0d;
        double belowRate = total > 0 ? ((double) below / (double) total) : 0.0d;
        double failOpenRate = total > 0 ? ((double) failOpen / (double) total) : 0.0d;
        return new MlWindowStats(total, pass, below, failOpen, avgProba, belowRate, failOpenRate, threshold);
    }

    private void maybeHandleMlDegradation(Long chatId, LocalState st, Prediction prediction, Instant eventTime) {
        if (st == null || st.ss == null || !st.ss.isAiMode()) {
            return;
        }

        Instant now = eventTime != null ? eventTime : Instant.now();
        if (st.lastMlAdaptiveAt != null
                && Duration.between(st.lastMlAdaptiveAt, now).toMillis() < ML_ADAPT_COOLDOWN_MS) {
            return;
        }

        MlWindowStats stats = summarizeMlWindow(st, prediction != null ? prediction.threshold : resolveThreshold(st.ss));
        if (stats.total() < ML_MIN_WINDOW) {
            return;
        }

        if (prediction != null && !prediction.ok) {
            st.lastMlAdaptiveAt = now;
            maybeRetrainAfterClose(chatId, st, "adaptive_ml_degraded");
            refreshSettingsIfNeeded(chatId, st, now);
            log.warn("[EMA] 🧠 ML деградация: запускаю переобучение chatId={} sym={} причина={} окно={} failRate={} avgProba={}",
                    chatId,
                    safe(st.symbol),
                    safe(prediction.error),
                    stats.total(),
                    fmt(stats.failOpenRate()),
                    fmt(stats.avgProba()));
            return;
        }

        if (stats.failOpenRate() >= ML_FAILOPEN_RATE_TRIGGER) {
            st.lastMlAdaptiveAt = now;
            maybeRetrainAfterClose(chatId, st, "adaptive_ml_fail_open_rate");
            refreshSettingsIfNeeded(chatId, st, now);
            log.warn("[EMA] 🧠 ML часто деградирует: запускаю переобучение chatId={} sym={} окно={} failRate={} avgProba={}",
                    chatId,
                    safe(st.symbol),
                    stats.total(),
                    fmt(stats.failOpenRate()),
                    fmt(stats.avgProba()));
            return;
        }

        if (stats.belowRate() < ML_LOW_RATE_TRIGGER) {
            return;
        }

        BigDecimal currentGate = currentGateMinProb(st.ss);
        double relaxBorder = Math.max(GATE_RELAX_MIN.doubleValue(), stats.threshold() - 0.20d);
        boolean canRelax = currentGate != null && currentGate.compareTo(GATE_RELAX_MIN) > 0;

        if (canRelax && stats.avgProba() >= relaxBorder) {
            BigDecimal newGate = currentGate.subtract(GATE_RELAX_STEP);
            if (newGate.compareTo(GATE_RELAX_MIN) < 0) {
                newGate = GATE_RELAX_MIN;
            }

            if (newGate.compareTo(currentGate) < 0) {
                try {
                    StrategySettings fresh = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
                    fresh.setMlGateEnabled(true);
                    fresh.setGateMinProb(newGate.setScale(6, RoundingMode.HALF_UP));
                    strategySettingsService.save(fresh);
                    st.lastMlAdaptiveAt = now;
                    refreshSettingsIfNeeded(chatId, st, now);
                    log.info("[EMA] 🧠 ML-порог смягчён chatId={} sym={} причина=часто низкая вероятность gate:{}→{} окно={} belowRate={} avgProba={}",
                            chatId,
                            safe(st.symbol),
                            fmtBd(currentGate),
                            fmtBd(newGate),
                            stats.total(),
                            fmt(stats.belowRate()),
                            fmt(stats.avgProba()));
                    return;
                } catch (Exception e) {
                    log.warn("[EMA] ML threshold relax failed chatId={} sym={} err={}",
                            chatId, safe(st.symbol), e.toString());
                }
            }
        }

        if (stats.avgProba() <= ML_STRONG_LOW_PROBA) {
            st.lastMlAdaptiveAt = now;
            maybeRetrainAfterClose(chatId, st, "adaptive_ml_below_threshold");
            refreshSettingsIfNeeded(chatId, st, now);
            log.info("[EMA] 🧠 ML слишком часто режет вход: запускаю переобучение chatId={} sym={} окно={} belowRate={} avgProba={} thr={}",
                    chatId,
                    safe(st.symbol),
                    stats.total(),
                    fmt(stats.belowRate()),
                    fmt(stats.avgProba()),
                    fmt(stats.threshold()));
        }
    }

    private void maybeRetrainAfterClose(Long chatId, LocalState st, String reason) {
        if (st == null || st.ss == null || !st.ss.isAiMode()) {
            return;
        }
        Instant now = Instant.now();
        if (st.lastRetrainAt != null) {
            long dt = Duration.between(st.lastRetrainAt, now).toMillis();
            if (dt < RETRAIN_THROTTLE_MS) {
                return;
            }
        }
        st.lastRetrainAt = now;

        EmaMlPreparationService prep = preparationServiceProvider != null ? preparationServiceProvider.getIfAvailable() : null;
        if (prep == null) return;

        try {
            EmaMlPreparationService.PrepareResult res = prep.prepare(chatId, st.ss, st.cfg, reason);
            log.info("[EMA] 🧠 retrain after close chatId={} sym={} ok={} applied={} rows={} reason={}",
                    chatId,
                    safe(st.symbol),
                    res.ok(),
                    res.applied(),
                    res.rows(),
                    safe(res.reason()));
            refreshSettingsIfNeeded(chatId, st, Instant.now());
        } catch (Exception e) {
            log.warn("[EMA] retrain after close failed chatId={} sym={} err={}", chatId, safe(st.symbol), e.toString());
        }
    }

    private Prediction tryPredict(Long chatId,
                                  LocalState st,
                                  BigDecimal price,
                                  Instant now,
                                  Map<String, Object> features) {
        double threshold = resolveThreshold(st.ss);
        if (st == null || st.ss == null) {
            return Prediction.reject("no_settings", threshold);
        }
        if (!shouldUseMlGate(st.ss)) {
            return Prediction.ok(1.0d, threshold, null, st.ss.getMlModelVersion());
        }

        if (st.lastPredictAt != null && st.lastPredictPrice != null && now != null) {
            long dt = Duration.between(st.lastPredictAt, now).toMillis();
            if (dt < PREDICT_THROTTLE_MS && price.compareTo(st.lastPredictPrice) == 0) {
                return Prediction.failOpen("predict_throttled", threshold);
            }
        }

        MlGateway gateway = mlGatewayProvider != null ? mlGatewayProvider.getIfAvailable() : null;
        if (gateway == null || !gateway.isEnabled()) {
            return Prediction.failOpen("ml_gateway_missing", threshold);
        }

        try {
            MlPredictResponse response = gateway.predict(
                    StrategyType.EMA_CROSSOVER,
                    chatId,
                    st.symbol,
                    features,
                    now != null ? now : Instant.now()
            );

            st.lastPredictAt = now != null ? now : Instant.now();
            st.lastPredictPrice = price;

            if (response == null) {
                return Prediction.failOpen("predict_null", threshold);
            }
            if (!response.isOk()) {
                return Prediction.failOpen(blankToDefault(response.getError(), response.getReason(), "predict_not_ok"), threshold);
            }
            Double probaObj = response.getProba();
            if (probaObj == null || !Double.isFinite(probaObj)) {
                return Prediction.failOpen("predict_no_proba", threshold);
            }

            double proba = clamp01(probaObj);
            String modelKey = firstNonBlank(response.getModelKey(), st.ss.getMlModelKey());
            String modelVersion = firstNonBlank(response.getModelVersion(), response.getVersion(), st.ss.getMlModelVersion());
            return Prediction.ok(proba, threshold, modelKey, modelVersion);
        } catch (Exception e) {
            return Prediction.failOpen("predict_exception:" + e.getClass().getSimpleName(), threshold);
        }
    }

    private Map<String, Object> buildMlFeatures(LocalState st, BigDecimal close, BigDecimal diffPct) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        int emaFast = clampInt(nvl(st.cfg.getEmaFast(), 9), 1, 300);
        int emaSlow = clampInt(nvl(st.cfg.getEmaSlow(), 21), 2, 600);
        int confirmBars = clampInt(nvl(st.cfg.getConfirmBars(), 1), 1, 10);
        double maxSpreadPct = st.cfg.getMaxSpreadPct() != null ? Math.max(0.0d, st.cfg.getMaxSpreadPct()) : 0.08d;

        double price = close.doubleValue();
        double fast = st.fastEma != null ? st.fastEma : price;
        double slow = st.slowEma != null ? st.slowEma : price;
        double spreadPct = diffPct != null ? diffPct.doubleValue() : safePct(Math.abs(fast - slow), slow);
        double priceVsFastPct = safePct(price - fast, fast);
        double priceVsSlowPct = safePct(price - slow, slow);
        double fastSlopePct = safePct(fast - (st.prevFastEma != null ? st.prevFastEma : fast), st.prevFastEma != null ? st.prevFastEma : fast);
        double slowSlopePct = safePct(slow - (st.prevSlowEma != null ? st.prevSlowEma : slow), st.prevSlowEma != null ? st.prevSlowEma : slow);
        double ret1Pct = returnPct(st.recentCloses, 1);
        double ret3Pct = returnPct(st.recentCloses, 3);
        double ret5Pct = returnPct(st.recentCloses, 5);
        double volatilityPct = volatilityPct(st.recentCloses);
        boolean bullRegime = fast > slow;
        int crossUp = (st.prevFastEma != null && st.prevSlowEma != null && st.prevFastEma <= st.prevSlowEma && fast > slow) ? 1 : 0;
        int crossDown = (st.prevFastEma != null && st.prevSlowEma != null && st.prevFastEma >= st.prevSlowEma && fast < slow) ? 1 : 0;

        row.put("emaFast", emaFast);
        row.put("emaSlow", emaSlow);
        row.put("confirmBars", confirmBars);
        row.put("maxSpreadPct", maxSpreadPct);
        row.put("price", price);
        row.put("fast", fast);
        row.put("slow", slow);
        row.put("spreadPct", spreadPct);
        row.put("priceVsFastPct", priceVsFastPct);
        row.put("priceVsSlowPct", priceVsSlowPct);
        row.put("fastSlopePct", fastSlopePct);
        row.put("slowSlopePct", slowSlopePct);
        row.put("ret1Pct", ret1Pct);
        row.put("ret3Pct", ret3Pct);
        row.put("ret5Pct", ret5Pct);
        row.put("volatilityPct", volatilityPct);
        row.put("bullRegime", bullRegime ? 1 : 0);
        row.put("crossUp", crossUp);
        row.put("crossDown", crossDown);
        row.put("bullishConfirmBars", st.bullishConfirmBars);
        row.put("chatId", st.ss.getChatId());
        row.put("strategyType", StrategyType.EMA_CROSSOVER.name());
        row.put("symbol", st.symbol);
        row.put("exchange", st.exchange);
        row.put("network", st.network != null ? st.network.name() : null);
        row.put("timeframe", st.timeframe);
        row.put("modelKey", st.ss.getMlModelKey());
        row.put("schemaHash", st.ss.getMlSchemaHash());
        return row;
    }

    private void syncMlConfidence(LocalState st, Prediction prediction) {
        if (st == null || st.ss == null || prediction == null) {
            return;
        }

        if (prediction.ok && Double.isFinite(prediction.proba)) {
            reflectSet(st.ss, "setMlConfidence", BigDecimal.valueOf(clamp01(prediction.proba)).setScale(6, RoundingMode.HALF_UP));
            if (!isBlank(prediction.modelKey)) {
                reflectSet(st.ss, "setMlModelKey", prediction.modelKey);
            }
            if (!isBlank(prediction.modelVersion)) {
                reflectSet(st.ss, "setMlModelVersion", prediction.modelVersion);
            }
            return;
        }

        if (!prediction.failOpen) {
            reflectSet(st.ss, "setMlConfidence", BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
        }
    }

    private void logMlBelow(Long chatId, LocalState st, Prediction prediction) {
        Instant now = Instant.now();
        if (!shouldLog(now, st != null ? st.lastMlLowLogAt : null, ML_LOG_THROTTLE_MS)) {
            return;
        }
        if (st != null) st.lastMlLowLogAt = now;

        MlWindowStats stats = summarizeMlWindow(st, prediction.threshold);
        log.info("[EMA] 🤖 ML ниже порога chatId={} sym={} proba={} thr={} окно={} belowRate={} avgProba={} modelKey={} modelVer={}",
                chatId,
                safe(st != null ? st.symbol : null),
                fmt(prediction.proba),
                fmt(prediction.threshold),
                stats.total(),
                fmt(stats.belowRate()),
                fmt(stats.avgProba()),
                safe(prediction.modelKey),
                safe(prediction.modelVersion));
    }

    private void logMlFailOpen(Long chatId, LocalState st, Prediction prediction) {
        Instant now = Instant.now();
        if (!shouldLog(now, st != null ? st.lastMlFailLogAt : null, ML_LOG_THROTTLE_MS)) {
            return;
        }
        if (st != null) st.lastMlFailLogAt = now;

        MlWindowStats stats = summarizeMlWindow(st, prediction.threshold);
        log.warn("[EMA] 🤖 ML недоступен, вход блокирован chatId={} sym={} reason={} окно={} failRate={} avgProba={}",
                chatId,
                safe(st != null ? st.symbol : null),
                safe(prediction.error),
                stats.total(),
                fmt(stats.failOpenRate()),
                fmt(stats.avgProba()));
    }

    private BigDecimal currentGateMinProb(StrategySettings ss) {
        if (ss == null) return new BigDecimal("0.550000");
        BigDecimal thr = ss.getEffectiveGateMinProbOrNull();
        if (thr == null) return new BigDecimal("0.550000");
        return thr.setScale(6, RoundingMode.HALF_UP);
    }

    private static boolean shouldLog(Instant now, Instant last, long throttleMs) {
        if (now == null) now = Instant.now();
        if (last == null) return true;
        return Duration.between(last, now).toMillis() >= throttleMs;
    }

    private boolean shouldUseMlGate(StrategySettings ss) {
        return ss != null && ss.isAiMode() && ss.isMlGateEnabled();
    }

    private double resolveThreshold(StrategySettings ss) {
        if (ss == null) return 0.55d;
        BigDecimal thr = ss.getEffectiveGateMinProbOrNull();
        if (thr == null) return 0.55d;
        return clamp01(thr.doubleValue());
    }

    private void pushPositionVisuals(Long chatId,
                                     LocalState st,
                                     BigDecimal entryPrice,
                                     BigDecimal qty,
                                     BigDecimal tp,
                                     BigDecimal sl,
                                     Instant ts,
                                     boolean pushTrade) {
        safeLive(() -> {
            if (pushTrade && entryPrice != null) {
                live.pushTrade(chatId, StrategyType.EMA_CROSSOVER, st.symbol, "BUY", entryPrice, qty, ts);
            }
            live.pushTpSl(chatId, StrategyType.EMA_CROSSOVER, st.symbol, tp, sl);
            if (entryPrice != null) live.pushPriceLine(chatId, StrategyType.EMA_CROSSOVER, st.symbol, "ENTRY", entryPrice);
            if (tp != null) live.pushPriceLine(chatId, StrategyType.EMA_CROSSOVER, st.symbol, "TP", tp);
            if (sl != null) live.pushPriceLine(chatId, StrategyType.EMA_CROSSOVER, st.symbol, "SL", sl);
        });
    }

    private void publishExitVisuals(Long chatId, LocalState st, BigDecimal exitPrice, Instant ts, String reason) {
        safeLive(() -> {
            live.pushTrade(chatId, StrategyType.EMA_CROSSOVER, st.symbol, "SELL", exitPrice, st.entryQty, ts);
            live.clearTpSl(chatId, StrategyType.EMA_CROSSOVER, st.symbol);
            live.clearPriceLines(chatId, StrategyType.EMA_CROSSOVER, st.symbol);
            if (!isBlank(reason)) {
                live.pushSignal(chatId, StrategyType.EMA_CROSSOVER, st.symbol, null, Signal.hold(reason));
            }
        });
    }

    private void clearPositionVisuals(Long chatId, LocalState st) {
        safeLive(() -> {
            live.clearTpSl(chatId, StrategyType.EMA_CROSSOVER, st.symbol);
            live.clearPriceLines(chatId, StrategyType.EMA_CROSSOVER, st.symbol);
        });
    }

    private static BigDecimal calcTp(BigDecimal entryPrice, BigDecimal pct) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        BigDecimal safePct = positiveOrDefault(pct, DEFAULT_TP_PCT);
        return entryPrice.multiply(
                        BigDecimal.ONE.add(safePct.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP))
                )
                .setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static BigDecimal calcSl(BigDecimal entryPrice, BigDecimal pct) {
        if (entryPrice == null || entryPrice.signum() <= 0) return null;
        BigDecimal safePct = positiveOrDefault(pct, DEFAULT_SL_PCT);
        return entryPrice.multiply(
                        BigDecimal.ONE.subtract(safePct.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP))
                )
                .setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private BigDecimal resolveTakeProfitPct(LocalState st) {
        if (st == null || st.cfg == null) return DEFAULT_TP_PCT;
        return positiveOrDefault(st.cfg.getTakeProfitPct(), DEFAULT_TP_PCT);
    }

    private BigDecimal resolveStopLossPct(LocalState st) {
        if (st == null || st.cfg == null) return DEFAULT_SL_PCT;
        return positiveOrDefault(st.cfg.getStopLossPct(), DEFAULT_SL_PCT);
    }

    private void pushHold(Long chatId, LocalState st, String reason) {
        Instant now = Instant.now();
        if (st != null && Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long dt = Duration.between(st.lastHoldAt, now).toMillis();
            if (dt < HOLD_THROTTLE_MS) {
                return;
            }
        }

        if (st != null) {
            st.lastHoldReason = reason;
            st.lastHoldAt = now;
        }

        safeLive(() -> live.pushSignal(chatId, StrategyType.EMA_CROSSOVER,
                st != null ? st.symbol : null,
                null,
                Signal.hold(reason)));
    }

    private void safeLive(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
        }
    }

    private static boolean resultOk(Object res) {
        if (res == null) return false;

        Boolean v = reflectBool(res, "ok");
        if (v != null) return v;

        v = reflectBool(res, "isOk");
        if (v != null) return v;

        v = reflectBool(res, "getOk");
        if (v != null) return v;

        return false;
    }

    private static String resultError(Object res) {
        if (res == null) return "null_result";
        Object v = reflectAny(res, "error");
        if (v == null) v = reflectAny(res, "getError");
        if (v == null) v = reflectAny(res, "reason");
        if (v == null) v = reflectAny(res, "getReason");
        return v != null ? String.valueOf(v) : null;
    }

    private static BigDecimal resultBigDecimal(Object res, String... methods) {
        if (res == null || methods == null) return null;
        for (String method : methods) {
            BigDecimal v = reflectBigDecimal(res, method);
            if (v != null) return v;
        }
        return null;
    }

    private static void reflectSet(Object obj, String method, Object value) {
        if (obj == null || isBlank(method)) return;
        try {
            for (var m : obj.getClass().getMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != 1) continue;
                Class<?> paramType = m.getParameterTypes()[0];
                if (value == null || paramType.isInstance(value)) {
                    m.invoke(obj, value);
                    return;
                }
                if (paramType == BigDecimal.class && value instanceof BigDecimal) {
                    m.invoke(obj, value);
                    return;
                }
                if (paramType == String.class && value instanceof String) {
                    m.invoke(obj, value);
                    return;
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static Object reflectAny(Object obj, String method) {
        if (obj == null || isBlank(method)) return null;
        try {
            var m = obj.getClass().getMethod(method);
            if (m.getParameterCount() != 0) return null;
            return m.invoke(obj);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Boolean reflectBool(Object obj, String method) {
        Object v = reflectAny(obj, method);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) return false;
        return null;
    }

    private static BigDecimal reflectBigDecimal(Object obj, String method) {
        Object v = reflectAny(obj, method);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static BigDecimal rememberClose(Deque<BigDecimal> closes, BigDecimal close, int max) {
        BigDecimal prev = closes.peekLast();
        closes.addLast(close);
        while (closes.size() > max) closes.removeFirst();
        return prev;
    }

    private static double returnPct(Deque<BigDecimal> closes, int barsBack) {
        if (closes == null || closes.isEmpty()) return 0.0d;
        List<BigDecimal> list = new ArrayList<>(closes);
        if (list.size() <= barsBack) return 0.0d;
        double last = list.get(list.size() - 1).doubleValue();
        double prev = list.get(list.size() - 1 - barsBack).doubleValue();
        return safePct(last - prev, prev);
    }

    private static double volatilityPct(Deque<BigDecimal> closes) {
        if (closes == null || closes.size() < 3) return 0.0d;
        double mean = 0.0d;
        int count = 0;
        for (BigDecimal bd : closes) {
            if (bd == null) continue;
            double v = bd.doubleValue();
            if (!Double.isFinite(v) || v <= 0.0d) continue;
            mean += v;
            count++;
        }
        if (count < 3) return 0.0d;
        mean /= count;
        if (!Double.isFinite(mean) || mean <= 0.0d) return 0.0d;
        double var = 0.0d;
        for (BigDecimal bd : closes) {
            if (bd == null) continue;
            double v = bd.doubleValue();
            if (!Double.isFinite(v) || v <= 0.0d) continue;
            double d = v - mean;
            var += d * d;
        }
        var /= count;
        double std = Math.sqrt(var);
        return (std / mean) * 100.0d;
    }

    private static double nextEma(Double prev, double price, int period) {
        if (period <= 1 || prev == null) return price;
        double alpha = 2.0d / (period + 1.0d);
        return price * alpha + prev * (1.0d - alpha);
    }

    private static BigDecimal diffPct(Double a, Double b) {
        if (a == null || b == null) return BigDecimal.ZERO;
        if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(b) < 1e-12d) return BigDecimal.ZERO;
        double pct = Math.abs((a - b) / b) * 100.0d;
        if (!Double.isFinite(pct) || pct < 0.0d) pct = 0.0d;
        return BigDecimal.valueOf(pct).setScale(8, RoundingMode.HALF_UP);
    }

    private static double safePct(double numerator, double base) {
        if (!Double.isFinite(numerator) || !Double.isFinite(base) || Math.abs(base) < 1e-12d) return 0.0d;
        double v = (numerator / base) * 100.0d;
        return Double.isFinite(v) ? v : 0.0d;
    }

    private static void clearPosition(LocalState st) {
        if (st == null) return;
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tpPrice = null;
        st.slPrice = null;
    }

    private static int nvl(Integer value, int def) {
        return value != null ? value : def;
    }

    private static long safeLong(Long value, long def) {
        return value != null ? value : def;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal def) {
        return value != null && value.signum() > 0 ? value : def;
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    private static String normalizeSymbol(String incoming, String fallback) {
        String v = isBlank(incoming) ? fallback : incoming;
        if (isBlank(v)) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchange(String incoming, String fallback) {
        String v = isBlank(incoming) ? fallback : incoming;
        if (isBlank(v)) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeTimeframe(String incoming, String fallback) {
        String v = isBlank(incoming) ? fallback : incoming;
        if (isBlank(v)) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static long parseTimeframeMs(String tf) {
        if (isBlank(tf)) return -1L;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) return -1L;
        char unit = s.charAt(s.length() - 1);
        String numStr = s.substring(0, s.length() - 1).trim();
        long n;
        try {
            n = Long.parseLong(numStr);
        } catch (Exception e) {
            return -1L;
        }
        if (n <= 0L) return -1L;
        return switch (unit) {
            case 's' -> n * 1_000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            case 'w' -> n * 604_800_000L;
            default -> -1L;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "null" : s;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        try {
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String fmtDouble(Double v) {
        if (v == null) return "null";
        try {
            return String.format(Locale.ROOT, "%.8f", v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String fmt(double v) {
        if (!Double.isFinite(v)) return "nan";
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0d;
        if (v < 0.0d) return 0.0d;
        if (v > 1.0d) return 1.0d;
        return v;
    }

    private static String blankToDefault(String a, String b, String def) {
        String x = firstNonBlank(a, b);
        return x == null ? def : x;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String modeName(StrategySettings ss) {
        if (ss == null || ss.getAdvancedControlMode() == null) return "MANUAL";
        return ss.getAdvancedControlMode().name();
    }

    private static final class PrepareStatus {
        final String message;
        final String holdReason;

        private PrepareStatus(String message, String holdReason) {
            this.message = message;
            this.holdReason = holdReason;
        }
    }
}



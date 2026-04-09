package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingServiceImpl;
import com.chicu.aitradebot.ai.runtime.AdaptiveRuntimeController;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLiveEvent;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.SCALPING)
@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingStrategyV4 implements TradingStrategy, AiStrategyOrchestrator.CandleCloseAware, AiStrategyOrchestrator.PrepareStartAware {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final Duration POST_EXIT_COOLDOWN = Duration.ofSeconds(8);
    private static final Duration INTRABAR_ENTRY_REEVAL = Duration.ofSeconds(2);
    private static final long LOG_EVERY_TICKS = 100;
    private static final int MIN_WINDOW = 6;
    private static final int MAX_WINDOW = 120;
    private static final int DEFAULT_WINDOW = 24;
    private static final int MAX_SERIES_POINTS = 240;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final StrategyLivePublisher live;
    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final ObjectProvider<MlTrainingServiceImpl> mlTrainingServiceProvider;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;
    private final MarketDataStreamService marketDataStreamService;
    private final AdaptiveRuntimeController adaptiveRuntimeController;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private MlTrainingServiceImpl mlTrainer() {
        return mlTrainingServiceProvider != null ? mlTrainingServiceProvider.getIfAvailable() : null;
    }

    public record WindowZoneSnapshot(BigDecimal high, BigDecimal low) {}
    private record TimedClose(long timeSec, BigDecimal close) {}

    private static class LocalState {
        Instant startedAt;
        boolean active;

        String lastSettingsFingerprint;
        Instant lastSettingsUpdatedAt;
        Instant lastScalpingUpdatedAt;

        StrategySettings strategySettings;
        ScalpingStrategySettings scalpingSettings;

        String symbol;
        String exchange;
        NetworkType network;
        String timeframe;

        Instant lastSettingsLoadAt;

        Deque<BigDecimal> closeWindow = new ArrayDeque<>();
        Deque<TimedClose> timedCloses = new ArrayDeque<>();

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;
        String entrySide;

        Instant entryOpenedAt;
        Instant lastTradeClosedAt;
        Instant lastIntrabarEvalAt;

        BigDecimal lastWindowHigh;
        BigDecimal lastWindowLow;
        ScalpingFeatureSnapshot lastFeatures;
        BigDecimal lastPrice;

        long ticks;
        long warmups;
        long entries;
        long exits;
        long candles;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    public WindowZoneSnapshot getLastWindowZone(long chatId) {
        LocalState st = states.get(chatId);
        if (st == null || st.lastWindowHigh == null || st.lastWindowLow == null) return null;
        return new WindowZoneSnapshot(st.lastWindowHigh, st.lastWindowLow);
    }

    public ScalpingFeatureSnapshot getLastFeatures(long chatId) {
        LocalState st = states.get(chatId);
        return st == null ? null : st.lastFeatures;
    }

    public Map<String, Object> getLastMlFeatures(long chatId) {
        ScalpingFeatureSnapshot snapshot = getLastFeatures(chatId);
        return snapshot == null ? Map.of() : snapshot.toMlFeatures();
    }

    @Override
    public void start(Long chatId, String ignored) {
        start(chatId, ignored, null, null);
    }

    @Override
    public void start(Long chatId, String ignored, String exchange, NetworkType network) {
        StrategySettings strategy = loadStrategySettings(chatId);
        ScalpingStrategySettings cfg = scalpingSettingsService.getEffective(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.strategySettings = strategy;
        st.scalpingSettings = cfg;
        st.symbol = resolveSymbol(strategy, cfg);
        st.exchange = safeUpper(exchange) != null ? safeUpper(exchange) : safeUpper(strategy.getExchangeName());
        st.network = network != null ? network : strategy.getNetworkType();
        st.timeframe = resolveTimeframe(strategy, cfg);
        st.lastSettingsLoadAt = Instant.now();
        st.lastSettingsUpdatedAt = toInstant(strategy.getUpdatedAt());
        st.lastScalpingUpdatedAt = cfg != null ? cfg.getUpdatedAt() : null;
        st.lastSettingsFingerprint = buildSettingsFingerprint(strategy, cfg);

        preloadFromCache(chatId, st);
        states.put(chatId, st);

        adaptiveRuntimeController.onStrategyStarted(chatId, StrategyType.SCALPING, st.exchange, st.network, st.symbol, st.timeframe);
        syncPositionState(chatId, st, true);

        safeLive(() -> live.pushState(chatId, StrategyType.SCALPING, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, st.timeframe, Signal.hold(st.inPosition ? "position_restored" : "started")));

        log.info("[SCALPING] ▶ START chatId={} symbol={} ex={} net={} tf={} cfgWindow={} effWindow={} cachedCloses={} impulse={} emaDiff={} volumeRatio={} spreadLimit={} atrLimit={} rsiFilter={} rrMin={}",
                chatId, st.symbol, st.exchange, st.network, st.timeframe,
                cfg != null ? cfg.getWindowSize() : null,
                effectiveWindowSize(cfg),
                st.closeWindow.size(),
                cfg != null ? cfg.getMinImpulsePct() : null,
                cfg != null ? cfg.getEmaDiffThreshold() : null,
                cfg != null ? cfg.getVolumeRatio() : null,
                cfg != null ? cfg.getSpreadLimitPct() : null,
                cfg != null ? cfg.getAtrPctRange() : null,
                cfg != null ? cfg.getRsiFilter() : null,
                cfg != null ? cfg.getRiskRewardMin() : null);

        if (strategy != null && strategy.isMlGateEnabled()) {
            log.info("[SCALPING] 🧠 ML context chatId={} symbol={} modelKey={} modelVer={} gateMinProb={}",
                    chatId,
                    st.symbol,
                    strategy.getMlModelKey(),
                    strategy.getMlModelVersion(),
                    strategy.getGateMinProb());
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

        adaptiveRuntimeController.onStrategyStopped(chatId, StrategyType.SCALPING, st.exchange, st.network);

        if (st.symbol != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, st.symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, st.symbol));
            safeLive(() -> live.clearWindowZone(chatId, StrategyType.SCALPING, st.symbol));
            safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, st.timeframe, Signal.hold("stopped")));
            safeLive(() -> live.pushState(chatId, StrategyType.SCALPING, st.symbol, false));
        }

        log.info("[SCALPING] ⏹ STOP chatId={} symbol={} ticks={} candles={} warmups={} entries={} exits={} inPos={}",
                chatId, st.symbol, st.ticks, st.candles, st.warmups, st.entries, st.exits, st.inPosition);
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
    public AiStrategyOrchestrator.PreparationResult prepareStart(long chatId,
                                                                 StrategyType type,
                                                                 String symbol,
                                                                 String timeframe,
                                                                 String exchange,
                                                                 NetworkType network) {
        if (type != StrategyType.SCALPING) {
            return AiStrategyOrchestrator.PreparationResult.fail("wrong_type");
        }

        try {
            StrategySettings strategy = loadStrategySettings(chatId);
            ScalpingStrategySettings cfg = scalpingSettingsService.getEffective(chatId);

            String effectiveSymbol = safeUpper(symbol);
            if (effectiveSymbol == null) effectiveSymbol = resolveSymbol(strategy, cfg);

            String effectiveTf = safeTf(timeframe);
            if (effectiveTf == null) effectiveTf = resolveTimeframe(strategy, cfg);

            String effectiveExchange = safeUpper(exchange);
            if (effectiveExchange == null) effectiveExchange = strategy != null ? safeUpper(strategy.getExchangeName()) : null;

            NetworkType effectiveNetwork = network != null ? network : (strategy != null ? strategy.getNetworkType() : null);

            if (effectiveSymbol == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("symbol_missing");
            }
            if (effectiveTf == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("timeframe_missing");
            }
            if (effectiveExchange == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("exchange_missing");
            }
            if (effectiveNetwork == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("network_missing");
            }

            int desired = Math.max(effectiveWindowSize(cfg) * 3, 120);
            Integer limitFromSettings = strategy != null ? strategy.getCachedCandlesLimit() : null;
            if ((limitFromSettings == null || limitFromSettings <= 0) && cfg != null && cfg.getCachedCandlesLimit() != null) {
                limitFromSettings = cfg.getCachedCandlesLimit();
            }
            if (limitFromSettings != null && limitFromSettings > 0) {
                desired = Math.max(desired, limitFromSettings);
            }
            desired = Math.min(desired, 1000);

            List<Candle> candles = marketDataStreamService.getCachedCandles(
                    chatId,
                    StrategyType.SCALPING,
                    effectiveExchange,
                    effectiveNetwork,
                    effectiveSymbol,
                    effectiveTf,
                    desired
            );

            int usable = 0;
            if (candles != null) {
                for (Candle candle : candles) {
                    if (candle == null || !candle.isClosed()) continue;
                    if (candle.getClose() <= 0.0d) continue;
                    usable++;
                }
            }

            int minNeeded = Math.max(effectiveWindowSize(cfg) + 12, 48);
            if (usable < minNeeded) {
                log.warn("[SCALPING] prepareStart blocked chatId={} symbol={} ex={} net={} tf={} usable={} need={}",
                        chatId, effectiveSymbol, effectiveExchange, effectiveNetwork, effectiveTf, usable, minNeeded);
                return AiStrategyOrchestrator.PreparationResult.fail("no_context_samples");
            }

            MlTrainingServiceImpl trainer = mlTrainer();
            if (trainer == null) {
                log.warn("[SCALPING] prepareStart blocked chatId={} symbol={} ex={} net={} tf={} reason=trainer_missing",
                        chatId, effectiveSymbol, effectiveExchange, effectiveNetwork, effectiveTf);
                return AiStrategyOrchestrator.PreparationResult.fail("trainer_missing");
            }

            MlTrainingResult trainResult = trainer.trainOnSelectedCandles(
                    chatId,
                    StrategyType.SCALPING,
                    effectiveExchange,
                    effectiveNetwork,
                    effectiveSymbol,
                    effectiveTf,
                    desired,
                    "prepare_start_train"
            );

            if (trainResult == null) {
                log.warn("[SCALPING] prepareStart blocked chatId={} symbol={} ex={} net={} tf={} reason=train_result_null",
                        chatId, effectiveSymbol, effectiveExchange, effectiveNetwork, effectiveTf);
                return AiStrategyOrchestrator.PreparationResult.fail("train_result_null");
            }

            if (!trainResult.ok() || !trainResult.applied()) {
                String reason = (trainResult.error() != null && !trainResult.error().isBlank())
                        ? trainResult.error()
                        : (!trainResult.ok() ? "train_not_ok" : "train_not_applied");
                log.warn("[SCALPING] prepareStart blocked chatId={} symbol={} ex={} net={} tf={} reason={} modelKey={} modelVer={} schemaHash={}",
                        chatId,
                        effectiveSymbol,
                        effectiveExchange,
                        effectiveNetwork,
                        effectiveTf,
                        reason,
                        trainResult.modelKey(),
                        trainResult.modelVersion(),
                        trainResult.schemaHash());
                return AiStrategyOrchestrator.PreparationResult.fail(reason);
            }

            StrategySettings refreshed = loadStrategySettings(chatId);
            String modelVersion = refreshed != null ? refreshed.getMlModelVersion() : null;
            if (modelVersion == null || modelVersion.isBlank()) {
                log.warn("[SCALPING] prepareStart blocked chatId={} symbol={} ex={} net={} tf={} reason=model_version_missing_after_train modelKey={} schemaHash={}",
                        chatId, effectiveSymbol, effectiveExchange, effectiveNetwork, effectiveTf,
                        trainResult.modelKey(), trainResult.schemaHash());
                return AiStrategyOrchestrator.PreparationResult.fail("model_version_missing_after_train");
            }

            log.info("[SCALPING] prepareStart OK chatId={} symbol={} ex={} net={} tf={} usable={} window={} desired={} modelKey={} modelVer={} schemaHash={}",
                    chatId,
                    effectiveSymbol,
                    effectiveExchange,
                    effectiveNetwork,
                    effectiveTf,
                    usable,
                    effectiveWindowSize(cfg),
                    desired,
                    refreshed != null ? refreshed.getMlModelKey() : trainResult.modelKey(),
                    modelVersion,
                    refreshed != null ? refreshed.getMlSchemaHash() : trainResult.schemaHash());

            return AiStrategyOrchestrator.PreparationResult.ok("context_ready");
        } catch (Exception e) {
            log.warn("[SCALPING] prepareStart failed chatId={} err={}", chatId, rootMessage(e));
            return AiStrategyOrchestrator.PreparationResult.fail("prepare_exception:" + rootMessage(e));
        }
    }

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {
        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        adaptiveRuntimeController.onTick(chatId, StrategyType.SCALPING, st.exchange, st.network);
        st.ticks++;
        if (price == null || price.signum() <= 0) return;

        Instant time = ts != null ? ts : Instant.now();
        String tickSymbol = safeUpper(symbolFromTick);
        String currentSymbol = safeUpper(st.symbol);

        if (currentSymbol != null && tickSymbol != null && !currentSymbol.equals(tickSymbol)) return;
        if (currentSymbol == null && tickSymbol != null) {
            st.symbol = tickSymbol;
            currentSymbol = tickSymbol;
        }

        st.lastPrice = price;
        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.SCALPING, symbolForLive, st.timeframe, price, time));

        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, time);
            syncPositionState(chatId, st, false);
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                tryClosePosition(chatId, st, st.symbol, price, time);
                return;
            }
            maybeOpenOnTick(chatId, st, price, time);
        }
    }

    @Override
    public void onCandleClosed(long chatId, StrategyType type, String symbol, String timeframe,
                               UnifiedKline kline, String exchange, NetworkType network) {
        if (type != StrategyType.SCALPING || kline == null || !kline.isClosed()) return;

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        Instant time = Instant.ofEpochMilli(kline.getCloseTime() > 0 ? kline.getCloseTime() : kline.getOpenTime());
        synchronized (st) {
            refreshSettingsIfNeeded(chatId, st, time);
            syncPositionState(chatId, st, false);
            if (!matchesContext(st, symbol, timeframe, exchange, network)) return;

            BigDecimal close = positive(kline.getClose());
            if (close == null) {
                pushHoldThrottled(chatId, st.symbol, st, "candle_close_invalid", time);
                return;
            }

            st.candles++;
            st.lastPrice = close;
            appendClose(st, close, toSec(kline.getCloseTime() > 0 ? kline.getCloseTime() : kline.getOpenTime()));

            ScalpingStrategySettings cfg = st.scalpingSettings;
            StrategySettings strategy = st.strategySettings;
            String resolvedSymbol = st.symbol;
            int windowSize = effectiveWindowSize(cfg);

            if (st.closeWindow.size() < windowSize) {
                st.warmups++;
                pushHoldThrottled(chatId, resolvedSymbol, st, "warming_up_candles", time);
                return;
            }

            ScalpingFeatureSnapshot features = ScalpingFeatureCalculator.calculate(st.closeWindow, cfg, time);
            st.lastFeatures = features;
            if (features == null) {
                pushHoldThrottled(chatId, resolvedSymbol, st, "features_unavailable", time);
                return;
            }

            st.lastWindowHigh = features.windowHigh();
            st.lastWindowLow = features.windowLow();
            pushVisualFeatures(chatId, st, features, kline, time);

            adaptiveRuntimeController.onCandleObserved(
                    chatId,
                    StrategyType.SCALPING,
                    st.exchange,
                    st.network,
                    st.symbol,
                    st.timeframe,
                    features.atrPct(),
                    features.spreadPct(),
                    features.volumeToAverage(),
                    time
            );

            String blockReason = !st.inPosition ? evaluateEntryBlockReason(cfg, features) : null;
            pushStatsSignal(chatId, st, features, blockReason, time);

            log.info("[SCALPING] candle chatId={} symbol={} close={} impulse={} emaDiff={} volRatio={} spread={} atr={} rsi={} rr={} score={} inPos={}",
                    chatId, resolvedSymbol, bd(features.lastPrice()), bd(features.priceChangePct()), bd(features.emaDiff()),
                    bd(features.volumeToAverage()), bd(features.spreadPct()), bd(features.atrPct()), bd(features.rsi()),
                    bd(features.riskRewardRatio()), bd(features.score()), st.inPosition);

            if (!st.inPosition) {
                if (blockReason != null) {
                    pushHoldThrottled(chatId, resolvedSymbol, st, blockReason, time);
                } else {
                    tryOpenPosition(chatId, st, strategy, cfg, resolvedSymbol, close, time, features);
                }
            }
        }
    }

    private void maybeOpenOnTick(Long chatId, LocalState st, BigDecimal price, Instant time) {
        if (st == null || st.inPosition || price == null || price.signum() <= 0) return;
        if (st.scalpingSettings == null || st.strategySettings == null) return;

        int windowSize = effectiveWindowSize(st.scalpingSettings);
        if (st.closeWindow.size() < windowSize) return;

        if (st.lastTradeClosedAt != null && Duration.between(st.lastTradeClosedAt, time).compareTo(POST_EXIT_COOLDOWN) < 0) return;
        if (st.lastIntrabarEvalAt != null && Duration.between(st.lastIntrabarEvalAt, time).compareTo(INTRABAR_ENTRY_REEVAL) < 0) return;
        st.lastIntrabarEvalAt = time;

        Deque<BigDecimal> preview = new ArrayDeque<>(st.closeWindow);
        preview.addLast(price);
        while (preview.size() > windowSize) preview.removeFirst();
        if (preview.size() < windowSize) return;

        ScalpingFeatureSnapshot features = ScalpingFeatureCalculator.calculate(preview, st.scalpingSettings, time);
        st.lastFeatures = features;
        if (features == null) return;

        st.lastWindowHigh = features.windowHigh();
        st.lastWindowLow = features.windowLow();

        String blockReason = evaluateEntryBlockReason(st.scalpingSettings, features);
        pushStatsSignal(chatId, st, features, blockReason, time);

        if (blockReason != null) {
            pushHoldThrottled(chatId, st.symbol, st, blockReason, time);
            return;
        }

        log.info("[SCALPING] ⚡ intrabar-ready chatId={} symbol={} price={} impulse={} emaDiff={} volRatio={} spread={} atr={} rsi={} rr={} fromLow={} fromHigh={} score={}",
                chatId,
                st.symbol,
                bd(price),
                bd(features.priceChangePct()),
                bd(features.emaDiff()),
                bd(features.volumeToAverage()),
                bd(features.spreadPct()),
                bd(features.atrPct()),
                bd(features.rsi()),
                bd(features.riskRewardRatio()),
                bd(features.priceFromWindowLow()),
                bd(features.priceFromWindowHigh()),
                bd(features.score()));

        tryOpenPosition(chatId, st, st.strategySettings, st.scalpingSettings, st.symbol, price, time, features);
    }

    private void pushVisualFeatures(Long chatId, LocalState st, ScalpingFeatureSnapshot features, UnifiedKline kline, Instant time) {
        String symbol = st.symbol;
        if (symbol == null || features == null) return;

        safeLive(() -> live.pushWindowZone(chatId, StrategyType.SCALPING, symbol, features.windowHigh(), features.windowLow()));
        safeLive(() -> live.pushAtr(chatId, StrategyType.SCALPING, symbol,
                features.atrPct().doubleValue(), features.windowRange().doubleValue()));

        buildAndPushSeries(chatId, st);

        if (st.inPosition && st.tp != null && st.sl != null) {
            safeLive(() -> live.pushTpSl(chatId, StrategyType.SCALPING, symbol, st.tp, st.sl));
        }

        if (kline != null) {
            safeLive(() -> live.publishCandle(chatId, StrategyType.SCALPING, kline));
        }
    }


    private void syncPositionState(Long chatId, LocalState st, boolean pushVisualsIfRestored) {
        if (chatId == null || st == null || positionStore == null) return;
        if (st.exchange == null || st.network == null || st.symbol == null) return;

        try {
            var opt = positionStore.getPosition(chatId, StrategyType.SCALPING, st.exchange, st.network, st.symbol);
            if (opt.isPresent() && opt.get().qty() != null && opt.get().qty().signum() > 0) {
                PositionStore.PositionSnapshot snap = opt.get();
                boolean wasInPosition = st.inPosition;
                boolean changed = !wasInPosition
                        || !Objects.equals(st.entryQty, snap.qty())
                        || !Objects.equals(st.entryPrice, snap.entryPrice())
                        || !Objects.equals(st.tp, snap.tp())
                        || !Objects.equals(st.sl, snap.sl());

                st.inPosition = true;
                st.isLong = true;
                st.entryQty = snap.qty();
                st.entryPrice = snap.entryPrice();
                st.tp = snap.tp();
                st.sl = snap.sl();
                st.entryOrderId = snap.entryOrderId();
                st.entrySide = "BUY";
                st.entryOpenedAt = snap.openedAt();

                if (changed) {
                    log.info("[SCALPING] ♻ POSITION SYNC chatId={} symbol={} qty={} entry={} tp={} sl={} orderId={}",
                            chatId, st.symbol, bd(st.entryQty), bd(st.entryPrice), bd(st.tp), bd(st.sl), st.entryOrderId);
                }

                if ((pushVisualsIfRestored || !wasInPosition) && st.symbol != null) {
                    BigDecimal priceLine = st.entryPrice != null ? st.entryPrice : st.lastPrice;
                    if (priceLine != null) {
                        safeLive(() -> live.pushPriceLine(chatId, StrategyType.SCALPING, st.symbol, "ENTRY", priceLine));
                    }
                    if (st.tp != null && st.sl != null) {
                        safeLive(() -> live.pushTpSl(chatId, StrategyType.SCALPING, st.symbol, st.tp, st.sl));
                    }
                }
                return;
            }

            if (st.inPosition) {
                log.warn("[SCALPING] 🧹 PositionStore empty, clearing local position chatId={} symbol={} qty={} entry={}",
                        chatId, st.symbol, bd(st.entryQty), bd(st.entryPrice));
                clearLocalPosition(st);
                if (st.symbol != null) {
                    safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, st.symbol));
                    safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, st.symbol));
                }
            }
        } catch (Exception e) {
            log.debug("[SCALPING] position sync skipped chatId={} symbol={} err={}", chatId, st.symbol, e.toString());
        }
    }

    private void clearLocalPosition(LocalState st) {
        if (st == null) return;
        st.inPosition = false;
        st.isLong = false;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
        st.entryQty = null;
        st.entryOrderId = null;
        st.entrySide = null;
        st.entryOpenedAt = null;
    }

    private void buildAndPushSeries(Long chatId, LocalState st) {
        List<TimedClose> data = new ArrayList<>(st.timedCloses);
        if (data.size() < 3) return;
        int start = Math.max(0, data.size() - MAX_SERIES_POINTS);
        data = data.subList(start, data.size());

        int window = effectiveWindowSize(st.scalpingSettings);
        int fastPeriod = Math.max(3, window / 4);
        int slowPeriod = Math.max(fastPeriod + 2, window / 2);

        List<StrategyLiveEvent.SeriesPointPayload> emaFast = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> emaSlow = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> midSeries = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> rsiSeries = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> volSeries = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> spreadSeries = new ArrayList<>();
        List<StrategyLiveEvent.SeriesPointPayload> scoreSeries = new ArrayList<>();

        double alphaFast = 2.0d / (fastPeriod + 1.0d);
        double alphaSlow = 2.0d / (slowPeriod + 1.0d);
        Double emaFastVal = null;
        Double emaSlowVal = null;
        Deque<BigDecimal> rolling = new ArrayDeque<>();

        for (TimedClose tc : data) {
            BigDecimal close = tc.close;
            if (close == null || close.signum() <= 0) continue;
            rolling.addLast(close);
            while (rolling.size() > window) rolling.removeFirst();

            double px = close.doubleValue();
            emaFastVal = (emaFastVal == null) ? px : px * alphaFast + emaFastVal * (1.0d - alphaFast);
            emaSlowVal = (emaSlowVal == null) ? px : px * alphaSlow + emaSlowVal * (1.0d - alphaSlow);

            emaFast.add(point(tc.timeSec, emaFastVal));
            emaSlow.add(point(tc.timeSec, emaSlowVal));

            if (rolling.size() >= 3) {
                ScalpingFeatureSnapshot snap = ScalpingFeatureCalculator.calculate(rolling, st.scalpingSettings, Instant.ofEpochSecond(tc.timeSec));
                if (snap != null) {
                    BigDecimal mid = snap.windowHigh().add(snap.windowLow()).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
                    midSeries.add(point(tc.timeSec, mid));
                    rsiSeries.add(point(tc.timeSec, priceFromOsc(snap.lastPrice(), snap.rsi(), 1.50d)));
                    volSeries.add(point(tc.timeSec, priceFromOsc(snap.lastPrice(), snap.volumeToAverage(), 0.60d)));
                    spreadSeries.add(point(tc.timeSec, priceFromOsc(snap.lastPrice(), snap.spreadPct(), 0.40d)));
                    scoreSeries.add(point(tc.timeSec, priceFromOsc(snap.lastPrice(), snap.score(), 0.25d)));
                }
            }
        }

        safeLive(() -> live.pushEmaSeries(chatId, StrategyType.SCALPING, st.symbol, st.timeframe, emaFast, emaSlow));
        safeLive(() -> live.pushSeriesBundle(chatId, StrategyType.SCALPING, st.symbol, st.timeframe, List.of(
                namedSeries("WINDOW_MID", "#94a3b8", midSeries),
                namedSeries("RSI_VIEW", "#facc15", rsiSeries),
                namedSeries("VOL_VIEW", "#38bdf8", volSeries),
                namedSeries("SPREAD_VIEW", "#f87171", spreadSeries),
                namedSeries("SCORE_VIEW", "#e879f9", scoreSeries)
        )));
    }

    private void pushStatsSignal(Long chatId, LocalState st, ScalpingFeatureSnapshot features, String blockReason, Instant time) {
        if (features == null || st == null || st.symbol == null) return;
        String reason = "SCALPING_STATS|"
                + "impulse=" + fmt(features.priceChangePct())
                + "|emaDiff=" + fmt(features.emaDiff())
                + "|volumeRatio=" + fmt(features.volumeToAverage())
                + "|spreadPct=" + fmt(features.spreadPct())
                + "|atrPct=" + fmt(features.atrPct())
                + "|windowRange=" + fmt(features.windowRange())
                + "|fromLow=" + fmt(features.priceFromWindowLow())
                + "|fromHigh=" + fmt(features.priceFromWindowHigh())
                + "|rsi=" + fmt(features.rsi())
                + "|rr=" + fmt(features.riskRewardRatio())
                + "|score=" + fmt(features.score())
                + "|block=" + (blockReason == null ? "READY" : blockReason)
                + "|ticks=" + st.ticks
                + "|candles=" + st.candles;
        safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, st.symbol, st.timeframe, Signal.hold(reason)));
    }

    private void preloadFromCache(Long chatId, LocalState st) {
        try {
            if (st == null || st.scalpingSettings == null || st.strategySettings == null) return;
            String symbol = resolveSymbol(st.strategySettings, st.scalpingSettings);
            String timeframe = resolveTimeframe(st.strategySettings, st.scalpingSettings);
            String exchange = st.exchange;
            NetworkType network = st.network;
            if (symbol == null || timeframe == null || exchange == null || network == null) return;

            int desired = Math.max(effectiveWindowSize(st.scalpingSettings) * 3, 120);
            Integer limitFromSettings = st.strategySettings.getCachedCandlesLimit();
            if (limitFromSettings == null && st.scalpingSettings.getCachedCandlesLimit() != null) {
                limitFromSettings = st.scalpingSettings.getCachedCandlesLimit();
            }
            if (limitFromSettings != null && limitFromSettings > 0) desired = Math.max(desired, limitFromSettings);
            desired = Math.min(desired, 1000);

            List<Candle> candles = marketDataStreamService.getCachedCandles(chatId, StrategyType.SCALPING, exchange, network, symbol, timeframe, desired);
            if (candles == null || candles.isEmpty()) return;

            st.closeWindow.clear();
            st.timedCloses.clear();
            for (Candle candle : candles) {
                if (candle == null || !candle.isClosed()) continue;
                BigDecimal close = BigDecimal.valueOf(candle.getClose());
                if (close.signum() <= 0) continue;
                appendClose(st, close, toSec(candle.getTime()));
                st.lastPrice = close;
            }

            log.info("[SCALPING] 📥 preload candles chatId={} symbol={} tf={} cached={} usable={}",
                    chatId, symbol, timeframe, candles.size(), st.closeWindow.size());
        } catch (Exception e) {
            log.warn("[SCALPING] ⚠ preloadFromCache failed chatId={} err={}", chatId, e.toString());
        }
    }

    private void appendClose(LocalState st, BigDecimal close, long timeSec) {
        st.closeWindow.addLast(close);
        int windowSize = effectiveWindowSize(st.scalpingSettings);
        while (st.closeWindow.size() > windowSize) st.closeWindow.removeFirst();

        st.timedCloses.addLast(new TimedClose(timeSec, close));
        while (st.timedCloses.size() > MAX_SERIES_POINTS) st.timedCloses.removeFirst();
    }

    private void tryOpenPosition(Long chatId,
                                 LocalState st,
                                 StrategySettings strategy,
                                 ScalpingStrategySettings cfg,
                                 String symbol,
                                 BigDecimal price,
                                 Instant time,
                                 ScalpingFeatureSnapshot features) {
        log.info("[SCALPING] ⚡ ENTRY try chatId={} symbol={} price={} features={}", chatId, symbol, bd(price), features.toMlFeatures());
        try {
            Object res = invokeTradeEntry(chatId, symbol, price, resolveEntryDiffPct(features), cfg, time, strategy);
            if (!invokeBoolean(res, "executed", "isExecuted")) {
                String reason = invokeString(res, "reason", "getReason", "message", "getMessage");
                if (reason == null || reason.isBlank()) reason = "entry_blocked";
                log.info("[SCALPING] ✋ ENTRY blocked chatId={} reason={} features={}", chatId, reason, features.toMlFeatures());
                pushHoldThrottled(chatId, symbol, st, reason, time);
                return;
            }

            st.entries++;
            st.inPosition = true;
            st.isLong = true;
            st.entryPrice = positive(invokeBigDecimal(res, "entryPrice", "getEntryPrice", "price", "getPrice"));
            if (st.entryPrice == null) st.entryPrice = price;
            st.tp = positive(invokeBigDecimal(res, "tp", "getTp", "tpPrice", "getTpPrice"));
            if (st.tp == null && cfg != null && cfg.getTakeProfitPct() != null) {
                st.tp = calcLongTarget(st.entryPrice, cfg.getTakeProfitPct());
            }
            st.sl = positive(invokeBigDecimal(res, "sl", "getSl", "slPrice", "getSlPrice"));
            if (st.sl == null && cfg != null && cfg.getStopLossPct() != null) {
                st.sl = calcLongStop(st.entryPrice, cfg.getStopLossPct());
            }
            st.entryQty = positive(invokeBigDecimal(res, "qty", "getQty", "quantity", "getQuantity"));
            st.entryOrderId = invokeLong(res, "orderId", "getOrderId", "id", "getId");
            st.entrySide = "BUY";
            st.entryOpenedAt = time;
            st.lastHoldReason = null;
            st.lastIntrabarEvalAt = time;

            safeLive(() -> live.pushTrade(chatId, StrategyType.SCALPING, symbol, "BUY", st.entryPrice, st.entryQty, time));
            safeLive(() -> live.pushPriceLine(chatId, StrategyType.SCALPING, symbol, "ENTRY", st.entryPrice));
            if (st.tp != null && st.sl != null) {
                safeLive(() -> live.pushTpSl(chatId, StrategyType.SCALPING, symbol, st.tp, st.sl));
            }

            adaptiveRuntimeController.onEntry(chatId, StrategyType.SCALPING, st.exchange, st.network, symbol, st.timeframe, time);

            log.info("[SCALPING] ✅ ENTRY OK chatId={} symbol={} qty={} entry={} tp={} sl={} orderId={} score={} features={}",
                    chatId, symbol, bd(st.entryQty), bd(st.entryPrice), bd(st.tp), bd(st.sl), st.entryOrderId, bd(features.score()), features.toMlFeatures());
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.error("[SCALPING] ❌ ENTRY failed chatId={} symbol={} err={}", chatId, symbol, msg, e);
            pushHoldThrottled(chatId, symbol, st, msg == null || msg.isBlank() ? "entry_failed" : msg, time);
        }
    }

    private Object invokeTradeEntry(Long chatId,
                                    String symbol,
                                    BigDecimal price,
                                    BigDecimal diffPct,
                                    ScalpingStrategySettings cfg,
                                    Instant time,
                                    StrategySettings strategy) throws Exception {
        BigDecimal tpPct = percent(cfg != null ? cfg.getTakeProfitPct() : null);
        BigDecimal slPct = percent(cfg != null ? cfg.getStopLossPct() : null);

        Exception lastError = null;
        Method[] methods = tradeExecutionService.getClass().getMethods();
        java.util.Arrays.sort(methods, Comparator
                .comparingInt(Method::getParameterCount)
                .reversed());

        for (Method method : methods) {
            if (!"executeEntry".equals(method.getName())) continue;
            try {
                Object[] args = buildExecuteEntryArgs(method, chatId, symbol, price, diffPct, tpPct, slPct, time, strategy);
                if (args == null) continue;

                Object result = method.invoke(tradeExecutionService, args);
                if (shouldSkipEntryResult(result)) {
                    lastError = new IllegalStateException(
                            "executeEntry(chatId,type,symbol,price,diffPct,time,ss) запрещён: TP/SL должны приходить из таблицы конкретной стратегии. Используй executeEntry(..., tpPct, slPct)."
                    );
                    continue;
                }
                return result;
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                String msg = rootMessage(cause);
                if (msg != null && msg.contains("TP/SL должны приходить")) {
                    lastError = new IllegalStateException(msg, cause);
                    continue;
                }
                if (cause instanceof Exception ex) {
                    lastError = ex;
                } else {
                    lastError = new RuntimeException(cause);
                }
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("Подходящий executeEntry overload не найден");
    }

    private boolean shouldSkipEntryResult(Object result) {
        if (result == null) return false;
        if (invokeBoolean(result, "executed", "isExecuted")) return false;

        String reason = invokeString(result, "reason", "getReason", "message", "getMessage");
        return reason != null && reason.contains("TP/SL должны приходить");
    }

    private Object[] buildExecuteEntryArgs(Method method,
                                           Long chatId,
                                           String symbol,
                                           BigDecimal price,
                                           BigDecimal diffPct,
                                           BigDecimal tpPct,
                                           BigDecimal slPct,
                                           Instant time,
                                           StrategySettings strategy) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length < 7 || types.length > 10) return null;

        ArrayDeque<BigDecimal> decimals = new ArrayDeque<>();
        decimals.add(price);
        decimals.add(diffPct != null ? diffPct : BigDecimal.ZERO);
        if (tpPct != null) decimals.add(tpPct);
        if (slPct != null) decimals.add(slPct);

        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == Long.class || type == long.class) {
                args[i] = chatId;
                continue;
            }
            if (type == StrategyType.class) {
                args[i] = StrategyType.SCALPING;
                continue;
            }
            if (type == String.class) {
                args[i] = symbol;
                continue;
            }
            if (type == Instant.class) {
                args[i] = time;
                continue;
            }
            if (type == BigDecimal.class) {
                if (decimals.isEmpty()) return null;
                args[i] = decimals.removeFirst();
                continue;
            }
            if (strategy != null && type.isAssignableFrom(strategy.getClass())) {
                args[i] = strategy;
                continue;
            }
            if (type.isAssignableFrom(StrategySettings.class)) {
                args[i] = strategy;
                continue;
            }
            return null;
        }
        return args;
    }

    private void tryClosePosition(Long chatId, LocalState st, String symbol, BigDecimal price, Instant time) {
        try {
            Object ex = tradeExecutionService.executeExitIfHit(chatId, StrategyType.SCALPING, symbol, price, time, st.isLong, st.entryQty, st.tp, st.sl, st.exchange, st.network);
            if (!invokeBoolean(ex, "executed", "isExecuted")) return;

            BigDecimal pnlPct = invokeBigDecimal(ex, "pnlPct", "getPnlPct", "realizedPnlPct", "getRealizedPnlPct");
            String reason = invokeString(ex, "reason", "getReason", "exitReason", "getExitReason");
            BigDecimal holdSeconds = st.entryOpenedAt == null
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(Math.max(0L, Duration.between(st.entryOpenedAt, time).getSeconds()));

            st.exits++;
            safeLive(() -> live.pushTrade(chatId, StrategyType.SCALPING, symbol, "SELL", price, st.entryQty, time));

            log.info("[SCALPING] ✅ EXIT OK chatId={} symbol={} price={} tp={} sl={} qty={} pnlPct={} reason={}",
                    chatId, symbol, bd(price), bd(st.tp), bd(st.sl), bd(st.entryQty), bd(pnlPct), reason);

            adaptiveRuntimeController.onExit(chatId, StrategyType.SCALPING, st.exchange, st.network, symbol, st.timeframe,
                    pnlPct, BigDecimal.ZERO, reason, holdSeconds, time);

            st.inPosition = false;
            st.entryQty = null;
            st.entryOrderId = null;
            st.entryPrice = null;
            st.tp = null;
            st.sl = null;
            st.entrySide = null;
            st.entryOpenedAt = null;
            st.lastTradeClosedAt = time;

            safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, symbol));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, symbol));
        } catch (Exception e) {
            log.error("[SCALPING] ❌ EXIT failed chatId={} symbol={} err={}", chatId, symbol, e.getMessage(), e);
        }
    }

    private String evaluateEntryBlockReason(ScalpingStrategySettings cfg, ScalpingFeatureSnapshot f) {
        if (cfg == null || f == null) return "no_features";

        double minImpulsePct = Math.max(0.0010d, normalizePercentThreshold(cfg.getMinImpulsePct()));
        double emaDiffThreshold = Math.max(0.0002d, normalizeEmaThreshold(cfg.getEmaDiffThreshold()));
        double spreadLimitPct = Math.max(0.02d, normalizePercentThreshold(cfg.getSpreadLimitPct()));
        double atrLimitPct = Math.max(0.10d, normalizePercentThreshold(cfg.getAtrPctRange()));
        double volumeRatio = nz(cfg.getVolumeRatio(), 1.0d);
        double rsiFilter = nz(cfg.getRsiFilter(), 38.0d);
        double rrMin = Math.max(1.0d, nz(cfg.getRiskRewardMin(), 1.1d));

        if (f.spreadPct().doubleValue() > spreadLimitPct) return "spread_too_wide";
        if (f.atrPct().doubleValue() > atrLimitPct) return "atr_too_high";
        if (f.riskRewardRatio().doubleValue() < rrMin) return "risk_reward_low";
        if (f.rsi().doubleValue() >= 84.0d) return "rsi_too_hot";

        double commonVolumeNeed = Math.max(0.35d, Math.min(volumeRatio, 1.00d));
        double breakoutDistance = Math.max(0.10d, f.windowRange().doubleValue() * 0.50d);
        double reboundDistance = Math.max(0.18d, f.windowRange().doubleValue() * 0.45d);

        double momentumRsiMin = Math.max(37.0d, rsiFilter - 5.0d);
        double reboundRsiMin = Math.max(12.0d, rsiFilter - 28.0d);
        double reboundRsiMax = Math.min(58.0d, Math.max(rsiFilter + 14.0d, 50.0d));

        double momentumImpulseNeed = Math.max(0.0015d, minImpulsePct * 0.55d);
        double reboundImpulseNeed = Math.max(0.0008d, minImpulsePct * 0.20d);
        double reboundEmaNeed = Math.max(0.0002d, emaDiffThreshold * 0.35d);
        double scoreOverride = Math.max(3.2d, rrMin * 2.2d);

        boolean liquidEnough = f.volumeToAverage().doubleValue() >= commonVolumeNeed;
        boolean insideBreakoutZone = f.priceFromWindowHigh().doubleValue() <= breakoutDistance;
        boolean insideReboundZone = f.priceFromWindowLow().doubleValue() <= reboundDistance;

        boolean momentumSetup =
                liquidEnough
                        && insideBreakoutZone
                        && f.priceChangePct().doubleValue() >= momentumImpulseNeed
                        && f.emaDiff().doubleValue() >= emaDiffThreshold
                        && f.rsi().doubleValue() >= momentumRsiMin;

        boolean reboundSetup =
                liquidEnough
                        && insideReboundZone
                        && f.rsi().doubleValue() >= reboundRsiMin
                        && f.rsi().doubleValue() <= reboundRsiMax
                        && (f.priceChangePct().doubleValue() >= reboundImpulseNeed
                        || f.emaDiff().doubleValue() >= reboundEmaNeed);

        boolean scoreOverrideSetup =
                liquidEnough
                        && insideReboundZone
                        && f.score().doubleValue() >= scoreOverride
                        && f.rsi().doubleValue() >= Math.max(10.0d, reboundRsiMin - 6.0d)
                        && f.rsi().doubleValue() <= Math.min(62.0d, reboundRsiMax + 6.0d)
                        && (f.emaDiff().doubleValue() >= reboundEmaNeed * 0.70d
                        || f.priceChangePct().doubleValue() >= reboundImpulseNeed * 0.55d);

        boolean microReboundSetup =
                liquidEnough
                        && insideReboundZone
                        && f.priceFromWindowLow().doubleValue() <= reboundDistance * 0.75d
                        && f.emaDiff().doubleValue() >= reboundEmaNeed
                        && f.rsi().doubleValue() >= Math.max(10.0d, reboundRsiMin - 4.0d)
                        && f.rsi().doubleValue() <= Math.min(60.0d, reboundRsiMax + 4.0d);

        if (momentumSetup || reboundSetup || scoreOverrideSetup || microReboundSetup) return null;

        if (!liquidEnough) return "volume_ratio_low";
        if (f.priceChangePct().doubleValue() < reboundImpulseNeed && f.emaDiff().doubleValue() < reboundEmaNeed) return "impulse_below_min";
        if (f.emaDiff().doubleValue() < reboundEmaNeed) return "ema_trend_weak";

        boolean farFromBreakout = !insideBreakoutZone;
        boolean farFromRebound = !insideReboundZone;
        if (farFromBreakout && farFromRebound) return "outside_entry_zone";

        if (f.rsi().doubleValue() < reboundRsiMin) return "rsi_too_cold";
        if (f.rsi().doubleValue() > reboundRsiMax && f.rsi().doubleValue() < momentumRsiMin) return "rsi_filter_block";

        return "setup_not_ready";
    }

    private BigDecimal resolveEntryDiffPct(ScalpingFeatureSnapshot features) {
        if (features == null) {
            return new BigDecimal("0.000001");
        }

        BigDecimal momentum = positive(features.priceChangePct());
        if (momentum != null) {
            return momentum;
        }

        BigDecimal emaDiff = positive(features.emaDiff());
        if (emaDiff != null) {
            return emaDiff;
        }

        BigDecimal score = positive(features.score());
        if (score != null) {
            return score;
        }

        return new BigDecimal("0.000001");
    }

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st.lastSettingsLoadAt != null && Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }
        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            ScalpingStrategySettings scalping = scalpingSettingsService.getEffective(chatId);
            Instant loadedUpd = loaded != null ? toInstant(loaded.getUpdatedAt()) : null;
            Instant scalpingUpd = scalping != null ? scalping.getUpdatedAt() : null;
            String fp = buildSettingsFingerprint(loaded, scalping);

            boolean changed = st.lastSettingsFingerprint == null
                    || !st.lastSettingsFingerprint.equals(fp)
                    || !Objects.equals(st.lastSettingsUpdatedAt, loadedUpd)
                    || !Objects.equals(st.lastScalpingUpdatedAt, scalpingUpd);

            String oldSymbol = safeUpper(st.symbol);
            String oldTf = safeTf(st.timeframe);

            if (loaded != null) {
                st.strategySettings = loaded;
                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }
            if (scalping != null) st.scalpingSettings = scalping;

            st.symbol = resolveSymbol(loaded, scalping);
            st.timeframe = resolveTimeframe(loaded, scalping);
            st.lastSettingsLoadAt = now;

            if (!changed) return;

            st.lastSettingsFingerprint = fp;
            st.lastSettingsUpdatedAt = loadedUpd;
            st.lastScalpingUpdatedAt = scalpingUpd;

            log.info("[SCALPING] ⚙ settings updated chatId={} symbol={} ex={} net={} tf={} cfgWindow={} effWindow={} impulse={} emaDiff={} volumeRatio={} spreadLimit={} atrLimit={} rsiFilter={} rrMin={} tp={} sl={} orderVolume={}",
                    chatId, st.symbol, st.exchange, st.network, st.timeframe,
                    scalping != null ? scalping.getWindowSize() : null,
                    effectiveWindowSize(scalping),
                    scalping != null ? scalping.getMinImpulsePct() : null,
                    scalping != null ? scalping.getEmaDiffThreshold() : null,
                    scalping != null ? scalping.getVolumeRatio() : null,
                    scalping != null ? scalping.getSpreadLimitPct() : null,
                    scalping != null ? scalping.getAtrPctRange() : null,
                    scalping != null ? scalping.getRsiFilter() : null,
                    scalping != null ? scalping.getRiskRewardMin() : null,
                    scalping != null ? scalping.getTakeProfitPct() : null,
                    scalping != null ? scalping.getStopLossPct() : null,
                    scalping != null ? scalping.getOrderVolume() : null);

            String newSymbol = safeUpper(st.symbol);
            String newTf = safeTf(st.timeframe);
            if (!Objects.equals(oldSymbol, newSymbol) || !Objects.equals(oldTf, newTf)) {
                st.closeWindow.clear();
                st.timedCloses.clear();
                st.lastFeatures = null;
                st.lastWindowHigh = null;
                st.lastWindowLow = null;
                st.lastHoldReason = null;
                if (oldSymbol != null) {
                    safeLive(() -> live.clearWindowZone(chatId, StrategyType.SCALPING, oldSymbol));
                    safeLive(() -> live.clearTpSl(chatId, StrategyType.SCALPING, oldSymbol));
                    safeLive(() -> live.clearPriceLines(chatId, StrategyType.SCALPING, oldSymbol));
                }
                preloadFromCache(chatId, st);
            }
        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[SCALPING] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildSettingsFingerprint(StrategySettings ss, ScalpingStrategySettings sc) {
        if (ss == null && sc == null) return "null";
        String symbol = resolveSymbol(ss, sc);
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = resolveTimeframe(ss, sc);
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit())
                : (sc != null && sc.getCachedCandlesLimit() != null ? String.valueOf(sc.getCachedCandlesLimit()) : "null");
        String window = sc != null ? num(sc.getWindowSize()) : "null";
        String impulse = sc != null ? num(sc.getMinImpulsePct()) : "null";
        String emaDiff = sc != null ? num(sc.getEmaDiffThreshold()) : "null";
        String volumeRatio = sc != null ? num(sc.getVolumeRatio()) : "null";
        String spread = sc != null ? num(sc.getSpreadLimitPct()) : "null";
        String atr = sc != null ? num(sc.getAtrPctRange()) : "null";
        String rsi = sc != null ? num(sc.getRsiFilter()) : "null";
        String rr = sc != null ? num(sc.getRiskRewardMin()) : "null";
        String tp = sc != null ? num(sc.getTakeProfitPct()) : "null";
        String sl = sc != null ? num(sc.getStopLossPct()) : "null";
        String orderVolume = sc != null ? num(sc.getOrderVolume()) : "null";
        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + window + "|" + impulse + "|" + emaDiff + "|"
                + volumeRatio + "|" + spread + "|" + atr + "|" + rsi + "|" + rr + "|" + tp + "|" + sl + "|" + orderVolume;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService.findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == StrategyType.SCALPING)
                .sorted(Comparator.comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings для SCALPING не найдены (chatId=" + chatId + ")"));
    }

    private void safeLive(Runnable r) { try { r.run(); } catch (Exception ignored) {} }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (chatId == null || st == null || now == null) return;
        String actualSymbol = (symbol != null && !symbol.isBlank()) ? symbol : st.symbol;
        if (actualSymbol == null || actualSymbol.isBlank()) return;
        String actualReason = (reason == null || reason.isBlank()) ? "unknown_hold_reason" : reason;
        if (Objects.equals(st.lastHoldReason, actualReason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 15_000) return;
        }
        st.lastHoldReason = actualReason;
        st.lastHoldAt = now;

        adaptiveRuntimeController.onHold(chatId, StrategyType.SCALPING, st.exchange, st.network, actualReason, now);

        log.info("[SCALPING] HOLD chatId={} sym={} reason={} inPosition={} ticks={} candles={}", chatId, actualSymbol, actualReason, st.inPosition, st.ticks, st.candles);
        safeLive(() -> live.pushSignal(chatId, StrategyType.SCALPING, actualSymbol, st.timeframe, Signal.hold(actualReason)));
    }

    private boolean matchesContext(LocalState st, String symbol, String timeframe, String exchange, NetworkType network) {
        if (st == null) return false;
        String sym = safeUpper(symbol);
        String tf = safeTf(timeframe);
        String ex = safeUpper(exchange);
        if (sym != null && st.symbol != null && !st.symbol.equals(sym)) return false;
        if (tf != null && st.timeframe != null && !st.timeframe.equals(tf)) return false;
        if (ex != null && st.exchange != null && !st.exchange.equalsIgnoreCase(ex)) return false;
        return network == null || st.network == null || st.network == network;
    }

    private static String resolveSymbol(StrategySettings strategy, ScalpingStrategySettings cfg) {
        String symbol = strategy != null ? safeUpper(strategy.getSymbol()) : null;
        if (symbol != null) return symbol;
        return cfg != null ? safeUpper(cfg.getSymbol()) : null;
    }

    private static String resolveTimeframe(StrategySettings strategy, ScalpingStrategySettings cfg) {
        String tf = strategy != null ? safeTf(strategy.getTimeframe()) : null;
        if (tf != null) return tf;
        return cfg != null ? safeTf(cfg.getTimeframe()) : null;
    }

    private static int effectiveWindowSize(ScalpingStrategySettings cfg) {
        if (cfg == null || cfg.getWindowSize() == null) return DEFAULT_WINDOW;
        int raw = cfg.getWindowSize();
        if (raw < MIN_WINDOW) return MIN_WINDOW;
        return Math.min(raw, MAX_WINDOW);
    }

    private static double normalizePercentThreshold(Double raw) {
        return raw == null ? 0.0d : raw;
    }

    private static double normalizeEmaThreshold(Double raw) {
        return raw == null ? 0.0d : raw;
    }

    private static StrategyLiveEvent.SeriesPointPayload point(long timeSec, double value) {
        return point(timeSec, BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP));
    }

    private static StrategyLiveEvent.SeriesPointPayload point(long timeSec, BigDecimal value) {
        return StrategyLiveEvent.SeriesPointPayload.builder().time(timeSec).value(value).build();
    }

    private static StrategyLiveEvent.NamedSeriesPayload namedSeries(String name, String color, List<StrategyLiveEvent.SeriesPointPayload> data) {
        return StrategyLiveEvent.NamedSeriesPayload.builder().name(name).color(color).data(data).build();
    }

    private static BigDecimal priceFromOsc(BigDecimal price, BigDecimal value, double pctScale) {
        if (price == null || value == null) return null;
        BigDecimal deltaPct = value.multiply(BigDecimal.valueOf(pctScale / 100.0d));
        return price.multiply(BigDecimal.ONE.add(deltaPct)).setScale(8, RoundingMode.HALF_UP);
    }

    private static long toSec(long ms) {
        return ms > 0 ? ms / 1000L : Instant.now().getEpochSecond();
    }

    private static BigDecimal percent(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal calcLongTarget(BigDecimal entry, Double tpPct) {
        if (entry == null || tpPct == null) return null;
        BigDecimal pct = BigDecimal.valueOf(tpPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        return entry.multiply(BigDecimal.ONE.add(pct)).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal calcLongStop(BigDecimal entry, Double slPct) {
        if (entry == null || slPct == null) return null;
        BigDecimal pct = BigDecimal.valueOf(slPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        return entry.multiply(BigDecimal.ONE.subtract(pct)).setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean invokeBoolean(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        if (value instanceof Boolean b) return b;
        return value instanceof Number n && n.intValue() != 0;
    }

    private static BigDecimal invokeBigDecimal(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value != null) {
            try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) {}
        }
        return BigDecimal.ZERO;
    }

    private static Long invokeLong(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String invokeString(Object target, String... methods) {
        Object value = invokeFirst(target, methods);
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeFirst(Object target, String... methods) {
        if (target == null || methods == null) return null;
        for (String methodName : methods) {
            if (methodName == null || methodName.isBlank()) continue;
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) return null;
        Throwable cur = throwable;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? throwable.getClass().getSimpleName() : msg;
    }

    private static double nz(Double value, double def) { return value == null ? def : value; }
    private static BigDecimal positive(BigDecimal value) { return value != null && value.signum() > 0 ? value : null; }
    private static String safeUpper(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT); }
    private static String safeTf(String s) { if (s == null) return null; String t = s.trim().toLowerCase(Locale.ROOT); return t.isEmpty() ? null : t; }
    private static Instant toInstant(LocalDateTime ldt) { return ldt == null ? null : ldt.atZone(ZONE).toInstant(); }
    private static String bd(BigDecimal value) { return value == null ? "null" : value.stripTrailingZeros().toPlainString(); }
    private static String fmt(BigDecimal value) { return value == null ? "null" : value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private static String num(Object value) {
        if (value == null) return "null";
        if (value instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
}


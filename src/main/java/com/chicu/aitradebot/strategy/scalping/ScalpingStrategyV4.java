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
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.EntryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
public class ScalpingStrategyV4 implements TradingStrategy,
        AiStrategyOrchestrator.CandleCloseAware,
        AiStrategyOrchestrator.PrepareStartAware {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final Duration HOLD_LOG_EVERY = Duration.ofSeconds(45);
    private static final Duration INTRABAR_REEVAL = Duration.ofSeconds(2);
    private static final int MIN_CANDLES_TO_WORK = 24;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final BigDecimal MIN_SPREAD_EDGE_PCT = new BigDecimal("0.06");

    private final StrategyLivePublisher live;
    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final ObjectProvider<MlTrainingServiceImpl> mlTrainingServiceProvider;
    private final MarketDataStreamService marketDataStreamService;
    private final AdaptiveRuntimeController adaptiveRuntimeController;

    private final TrendPullbackEntryEngine trendPullbackEntryEngine;
    private final RangeBounceEntryEngine rangeBounceEntryEngine;
    private final BreakoutContinuationEntryEngine breakoutContinuationEntryEngine;
    private final ScalpingMarketRegimeDetector marketRegimeDetector;
    private final ScalpingExecutionGuard executionGuard;
    private final ScalpingPositionManager positionManager;
    private final ScalpingRiskProfileResolver riskProfileResolver;
    private final ScalpingMlGate mlGate;
    private final com.chicu.aitradebot.trade.TradeExecutionService tradeExecutionService;

    private final Map<Long, ScalpingRuntimeState> states = new ConcurrentHashMap<>();

    private MlTrainingServiceImpl mlTrainer() {
        return mlTrainingServiceProvider != null ? mlTrainingServiceProvider.getIfAvailable() : null;
    }

    @Override
    public void start(Long chatId, String ignored) {
        start(chatId, ignored, null, null);
    }

    @Override
    public void start(Long chatId, String ignored, String exchange, NetworkType network) {
        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, StrategyType.SCALPING);
        ScalpingStrategySettings settings = scalpingSettingsService.getEffective(chatId);

        ScalpingRuntimeState state = new ScalpingRuntimeState();
        state.setActive(true);
        state.setStartedAt(Instant.now());
        state.setStrategySettings(strategy);
        state.setScalpingSettings(settings);
        state.setExchange(firstNonBlank(upper(exchange), upper(strategy.getExchangeName()), "BINANCE"));
        state.setNetwork(network != null ? network : (strategy.getNetworkType() != null ? strategy.getNetworkType() : NetworkType.TESTNET));
        state.setSymbol(firstNonBlank(upper(strategy.getSymbol()), upper(settings.getSymbol()), "BTCUSDT"));
        state.setTimeframe(firstNonBlank(lower(strategy.getTimeframe()), lower(settings.getTimeframe()), "1m"));
        state.setLastSettingsLoadAt(Instant.now());
        preloadFromCache(chatId, state);
        positionManager.syncFromStore(chatId, state, true);
        states.put(chatId, state);

        adaptiveRuntimeController.onStrategyStarted(chatId, StrategyType.SCALPING,
                state.getExchange(), state.getNetwork(), state.getSymbol(), state.getTimeframe());

        live.pushState(chatId, StrategyType.SCALPING, state.getSymbol(), true);
        live.pushSignal(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTimeframe(), Signal.hold("Стратегия запущена"));

        log.info("[SCALPING] ▶️ Запуск скальпера chatId={} ex={} net={} symbol={} tf={} window={} regimeAuto={} trend={} range={} breakout={} orderVolume={}",
                chatId,
                state.getExchange(),
                state.getNetwork(),
                state.getSymbol(),
                state.getTimeframe(),
                settings.getWindowSize(),
                settings.getRegimeAutoEnabled(),
                settings.getAllowTrendTrades(),
                settings.getAllowRangeTrades(),
                settings.getAllowBreakoutTrades(),
                settings.getOrderVolume());
    }

    @Override
    public void stop(Long chatId, String ignored) {
        stop(chatId, ignored, null, null);
    }

    @Override
    public void stop(Long chatId, String ignored, String exchange, NetworkType network) {
        ScalpingRuntimeState state = states.remove(chatId);
        if (state == null) return;

        adaptiveRuntimeController.onStrategyStopped(chatId, StrategyType.SCALPING, state.getExchange(), state.getNetwork());
        live.clearTpSl(chatId, StrategyType.SCALPING, state.getSymbol());
        live.clearPriceLines(chatId, StrategyType.SCALPING, state.getSymbol());
        live.clearWindowZone(chatId, StrategyType.SCALPING, state.getSymbol());
        live.pushSignal(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTimeframe(), Signal.hold("Стратегия остановлена"));
        live.pushState(chatId, StrategyType.SCALPING, state.getSymbol(), false);

        log.info("[SCALPING] ⏹️ Скальпер остановлен chatId={} symbol={} ticks={} candles={} entries={} exits={} inPosition={}",
                chatId,
                state.getSymbol(),
                state.getTicks(),
                state.getCandles(),
                state.getEntries(),
                state.getExits(),
                state.isInPosition());
    }

    @Override
    public boolean isActive(Long chatId) {
        ScalpingRuntimeState state = states.get(chatId);
        return state != null && state.isActive();
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        ScalpingRuntimeState state = states.get(chatId);
        return state != null ? state.getStartedAt() : null;
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
            StrategySettings strategy = strategySettingsService.getOrCreate(chatId, StrategyType.SCALPING);
            ScalpingStrategySettings settings = scalpingSettingsService.getEffective(chatId);
            String sym = firstNonBlank(upper(symbol), upper(strategy.getSymbol()), upper(settings.getSymbol()));
            String tf = firstNonBlank(lower(timeframe), lower(strategy.getTimeframe()), lower(settings.getTimeframe()));
            String ex = firstNonBlank(upper(exchange), upper(strategy.getExchangeName()));
            NetworkType net = network != null ? network : strategy.getNetworkType();
            if (sym == null || tf == null || ex == null || net == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("context_missing");
            }

            int desired = Math.max(settings.getCachedCandlesLimit(), Math.max(settings.getWindowSize() * 3, 180));
            int required = Math.max(MIN_CANDLES_TO_WORK, settings.getWindowSize() + 8);
            List<Candle> candles = marketDataStreamService.getCachedCandles(chatId, StrategyType.SCALPING, ex, net, sym, tf, desired);
            int usable = countUsableClosedCandles(candles);

            MlTrainingServiceImpl trainer = mlTrainer();
            if (trainer == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("trainer_missing");
            }

            if (usable < required) {
                log.warn("[SCALPING] В runtime-кэше мало свечей перед стартом chatId={} symbol={} tf={} usable={} need={} | пытаюсь прогреть контекст через ML loader",
                        chatId, sym, tf, usable, required);
            } else {
                log.info("[SCALPING] Контекст перед стартом готов chatId={} symbol={} tf={} usable={} need={}",
                        chatId, sym, tf, usable, required);
            }

            MlTrainingResult result = trainer.trainOnSelectedCandles(
                    chatId,
                    StrategyType.SCALPING,
                    ex,
                    net,
                    sym,
                    tf,
                    desired,
                    "prepare_start_train"
            );
            if (result == null) {
                return AiStrategyOrchestrator.PreparationResult.fail("train_result_null");
            }
            if (!result.ok() || !result.applied()) {
                return AiStrategyOrchestrator.PreparationResult.fail(result.error() != null ? result.error() : "train_not_applied");
            }

            List<Candle> warmedCandles = marketDataStreamService.getCachedCandles(chatId, StrategyType.SCALPING, ex, net, sym, tf, desired);
            int warmedUsable = countUsableClosedCandles(warmedCandles);
            if (warmedUsable < required) {
                log.warn("[SCALPING] После прогрева контекст всё ещё слабый chatId={} ex={} net={} symbol={} tf={} usable={} need={} | модель обучена, но старт пока рискованный",
                        chatId, ex, net, sym, tf, warmedUsable, required);
            }

            log.info("[SCALPING] ✅ Подготовка к старту завершена chatId={} ex={} net={} symbol={} tf={} candlesBefore={} candlesAfter={} modelKey={} modelVer={}",
                    chatId, ex, net, sym, tf, usable, warmedUsable, result.modelKey(), result.modelVersion());
            return AiStrategyOrchestrator.PreparationResult.ok("context_ready");
        } catch (Exception e) {
            log.warn("[SCALPING] Подготовка к старту завершилась ошибкой chatId={} err={}", chatId, rootMessage(e));
            return AiStrategyOrchestrator.PreparationResult.fail("prepare_exception:" + rootMessage(e));
        }
    }

    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {
        ScalpingRuntimeState state = states.get(chatId);
        if (state == null || !state.isActive() || price == null || price.signum() <= 0) return;
        Instant now = ts != null ? ts : Instant.now();
        state.setTicks(state.getTicks() + 1);
        state.setLastPrice(price);
        live.pushPriceTick(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTimeframe(), price, now);

        synchronized (state) {
            refreshSettingsIfNeeded(chatId, state, now);
            positionManager.syncFromStore(chatId, state, false);
            adaptiveRuntimeController.onTick(chatId, StrategyType.SCALPING, state.getExchange(), state.getNetwork());

            if (state.isInPosition()) {
                positionManager.manage(chatId, state, price, now, state.getLastFeatures(), state.getLastMarketSnapshot(), state.getScalpingSettings());
                return;
            }

            if (!Boolean.TRUE.equals(state.getScalpingSettings().getUseIntrabarConfirmation())) {
                return;
            }
            if (state.getLastIntrabarEvalAt() != null && Duration.between(state.getLastIntrabarEvalAt(), now).compareTo(INTRABAR_REEVAL) < 0) {
                return;
            }
            state.setLastIntrabarEvalAt(now);

            if (state.getLastFeatures() == null || state.getLastMarketSnapshot() == null) {
                return;
            }
            if (state.getCloseWindow().size() < Math.max(MIN_CANDLES_TO_WORK, state.getScalpingSettings().getWindowSize())) {
                return;
            }

            maybeOpenPosition(chatId, state, price, now, state.getLastFeatures(), state.getLastMarketSnapshot(), true);
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
        if (type != StrategyType.SCALPING || kline == null || !kline.isClosed()) return;
        ScalpingRuntimeState state = states.get(chatId);
        if (state == null || !state.isActive()) return;

        Instant now = Instant.ofEpochMilli(kline.getCloseTime() > 0 ? kline.getCloseTime() : kline.getOpenTime());
        synchronized (state) {
            refreshSettingsIfNeeded(chatId, state, now);
            if (!matchesContext(state, symbol, timeframe, exchange, network)) return;
            positionManager.syncFromStore(chatId, state, false);

            BigDecimal close = positive(kline.getClose());
            if (close == null) {
                pushHold(chatId, state, "close_invalid", "Закрытие свечи некорректно", now);
                return;
            }

            appendCandle(state, toCandleInput(kline, now));
            state.setCandles(state.getCandles() + 1);
            state.setLastPrice(close);
            live.publishCandle(chatId, StrategyType.SCALPING, kline);

            if (state.getCandleWindow().size() < Math.max(MIN_CANDLES_TO_WORK, state.getScalpingSettings().getWindowSize())) {
                state.setWarmups(state.getWarmups() + 1);
                pushHold(chatId, state, "warmup", "Накопление контекста перед входом", now);
                return;
            }

            ScalpingFeatureSnapshot features = ScalpingFeatureCalculator.calculateFromCandles(state.getCandleWindow(), state.getScalpingSettings(), now);
            if (features == null) {
                pushHold(chatId, state, "features_unavailable", "Не удалось вычислить признаки рынка", now);
                return;
            }
            state.setLastFeatures(features);

            ScalpingMarketRegimeSnapshot snapshot = marketRegimeDetector.detect(state.getCandleWindow(), features, state.getScalpingSettings(), now);
            state.setPreviousRegime(state.getLastMarketSnapshot() != null ? state.getLastMarketSnapshot().regime() : null);
            state.setLastMarketSnapshot(snapshot);
            live.pushWindowZone(chatId, StrategyType.SCALPING, state.getSymbol(), features.windowHigh(), features.windowLow());

            adaptiveRuntimeController.onCandleObserved(chatId, StrategyType.SCALPING, state.getExchange(), state.getNetwork(),
                    state.getSymbol(), state.getTimeframe(), features.atrPct(), features.spreadPct(), features.volumeRatio(), now);

            logRegimeIfChanged(chatId, state, snapshot, features);

            if (state.isInPosition()) {
                positionManager.manage(chatId, state, close, now, features, snapshot, state.getScalpingSettings());
                return;
            }

            maybeOpenPosition(chatId, state, close, now, features, snapshot, false);
        }
    }

    private void maybeOpenPosition(Long chatId,
                                   ScalpingRuntimeState state,
                                   BigDecimal price,
                                   Instant now,
                                   ScalpingFeatureSnapshot features,
                                   ScalpingMarketRegimeSnapshot snapshot,
                                   boolean intrabar) {
        if (state.isInPosition()) return;
        EntryDecision decision = selectDecision(state, snapshot, features);
        if (decision == null || !decision.allowed()) {
            pushHold(chatId, state, "decision_blocked", decision != null ? decision.reason() : "Сетап не найден", now);
            return;
        }

        ScalpingRiskProfile risk = riskProfileResolver.resolve(snapshot, decision, state.getScalpingSettings());
        decision = decision.withRisk(risk);

        ScalpingMlGate.MlGateResult ml = mlGate.evaluate(state.getStrategySettings(), snapshot, decision);
        if (ml.veto()) {
            pushHold(chatId, state, "ml_veto", ml.reason(), now);
            return;
        }
        decision = decision.withMlAdjustments(ml.riskScaleMultiplier(), ml.tpMultiplier(), ml.slMultiplier(), ml.reason());

        ScalpingExecutionGuard.GuardResult guard = executionGuard.validate(
                chatId,
                state,
                state.getStrategySettings(),
                state.getScalpingSettings(),
                features,
                snapshot,
                decision,
                now
        );
        if (!guard.allowed()) {
            pushHold(chatId, state, guard.code(), humanizeGuard(guard), now);
            return;
        }

        if (!hasPositiveNetEdge(decision, features)) {
            pushHold(chatId, state, "net_edge_low", "Сделка пропущена: ожидаемая прибыль после спреда слишком мала", now);
            return;
        }
        if (!passesFeeFloor(chatId, state, decision)) {
            pushHold(chatId, state, "fee_floor_block", "Сделка пропущена: TP не перекрывает комиссии биржи", now);
            return;
        }

        BigDecimal diffPct = features.priceChangePct() != null && features.priceChangePct().signum() > 0
                ? features.priceChangePct()
                : new BigDecimal("0.000001");

        EntryResult entry = tradeExecutionService.executeEntry(
                chatId,
                StrategyType.SCALPING,
                state.getSymbol(),
                price,
                diffPct,
                now,
                state.getStrategySettings(),
                decision.tpPct(),
                decision.slPct()
        );

        if (entry == null || !entry.executed()) {
            String reason = entry != null ? entry.reason() : "entry_null";
            pushHold(chatId, state, "entry_blocked", "Вход не выполнен: " + reason, now);
            return;
        }

        state.setEntries(state.getEntries() + 1);
        state.setEntryPrice(entry.entryPrice() != null ? entry.entryPrice() : price);
        state.setEntryQty(entry.qty());
        state.setTp(entry.tp());
        state.setSl(entry.sl());
        state.setEntryOrderId(entry.orderId());
        state.setLastSetupType(decision.setupType());
        positionManager.onEntryOpened(chatId, state, decision, now);
        positionManager.syncFromStore(chatId, state, true);

        adaptiveRuntimeController.onEntry(chatId, StrategyType.SCALPING, state.getExchange(), state.getNetwork(),
                state.getSymbol(), state.getTimeframe(), now);

        live.pushTrade(chatId, StrategyType.SCALPING, state.getSymbol(), "BUY", state.getEntryPrice(), state.getEntryQty(), now);
        live.pushSignal(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTimeframe(),
                Signal.buy(price.doubleValue(), decision.reason()));

        log.info("[SCALPING] ✅ Вход открыт chatId={} symbol={} regime={} setup={} intrabar={} entry={} qty={} tp={} sl={} riskScale={} reason={}",
                chatId,
                state.getSymbol(),
                snapshot.regime(),
                decision.setupType(),
                intrabar,
                fmt(state.getEntryPrice()),
                fmt(state.getEntryQty()),
                fmt(state.getTp()),
                fmt(state.getSl()),
                fmt(decision.riskScale()),
                decision.reason());
    }

    private EntryDecision selectDecision(ScalpingRuntimeState state,
                                         ScalpingMarketRegimeSnapshot snapshot,
                                         ScalpingFeatureSnapshot features) {
        if (snapshot == null || features == null) {
            return EntryDecision.block(ScalpingMarketRegime.NO_TRADE, ScalpingSetupType.NO_TRADE, "нет market snapshot", Map.of());
        }

        EntryDecision breakout = breakoutContinuationEntryEngine.evaluate(state.getPreviousRegime(), snapshot, features, state.getScalpingSettings());
        EntryDecision trend = trendPullbackEntryEngine.evaluate(snapshot, features, state.getScalpingSettings());
        EntryDecision range = rangeBounceEntryEngine.evaluate(snapshot, features, state.getScalpingSettings());

        return List.of(breakout, trend, range).stream()
                .filter(Objects::nonNull)
                .filter(EntryDecision::allowed)
                .max(Comparator.comparing(d -> d.score() != null ? d.score() : BigDecimal.ZERO))
                .orElseGet(() -> switch (snapshot.regime()) {
                    case TREND_UP -> trend;
                    case RANGE -> range;
                    case SQUEEZE, TREND_DOWN, CHAOS, NO_TRADE -> EntryDecision.block(snapshot.regime(), ScalpingSetupType.NO_TRADE, snapshot.reason(), features.toMlFeatures());
                });
    }

    private void preloadFromCache(Long chatId, ScalpingRuntimeState state) {
        try {
            int desired = Math.max(state.getScalpingSettings().getCachedCandlesLimit(), Math.max(state.getScalpingSettings().getWindowSize() * 3, 180));
            List<Candle> candles = marketDataStreamService.getCachedCandles(
                    chatId,
                    StrategyType.SCALPING,
                    state.getExchange(),
                    state.getNetwork(),
                    state.getSymbol(),
                    state.getTimeframe(),
                    desired
            );
            if (candles == null || candles.isEmpty()) return;
            for (Candle candle : candles) {
                if (candle == null || !candle.isClosed() || candle.getClose() <= 0.0d) continue;
                state.getCloseWindow().addLast(BigDecimal.valueOf(candle.getClose()));
                state.getCandleWindow().addLast(new ScalpingFeatureCalculator.CandleInput(
                        Instant.ofEpochMilli(candle.getTime()),
                        BigDecimal.valueOf(candle.getOpen()),
                        BigDecimal.valueOf(candle.getHigh()),
                        BigDecimal.valueOf(candle.getLow()),
                        BigDecimal.valueOf(candle.getClose()),
                        BigDecimal.valueOf(candle.getVolume())
                ));
                trimWindows(state);
                state.setLastPrice(BigDecimal.valueOf(candle.getClose()));
            }
            log.info("[SCALPING] 📥 Контекст загружен из кэша chatId={} symbol={} tf={} candles={}",
                    chatId, state.getSymbol(), state.getTimeframe(), state.getCandleWindow().size());
        } catch (Exception e) {
            log.warn("[SCALPING] preloadFromCache пропущен chatId={} err={}", chatId, e.toString());
        }
    }

    private void appendCandle(ScalpingRuntimeState state, ScalpingFeatureCalculator.CandleInput candle) {
        if (state == null || candle == null) return;
        state.getCloseWindow().addLast(candle.close());
        state.getCandleWindow().addLast(candle);
        trimWindows(state);
    }

    private void trimWindows(ScalpingRuntimeState state) {
        int hardLimit = Math.max(state.getScalpingSettings().getCachedCandlesLimit(), state.getScalpingSettings().getWindowSize() * 3);
        while (state.getCloseWindow().size() > hardLimit) state.getCloseWindow().removeFirst();
        while (state.getCandleWindow().size() > hardLimit) state.getCandleWindow().removeFirst();
    }


    private int countUsableClosedCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return 0;
        int usable = 0;
        for (Candle candle : candles) {
            if (candle != null && candle.isClosed() && candle.getClose() > 0.0d) {
                usable++;
            }
        }
        return usable;
    }

    private void refreshSettingsIfNeeded(Long chatId, ScalpingRuntimeState state, Instant now) {
        if (state.getLastSettingsLoadAt() != null && Duration.between(state.getLastSettingsLoadAt(), now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }
        StrategySettings strategy = strategySettingsService.getOrCreate(chatId, StrategyType.SCALPING);
        ScalpingStrategySettings settings = scalpingSettingsService.getEffective(chatId);
        String newFingerprint = buildSettingsFingerprint(strategy, settings);
        boolean changed = !Objects.equals(state.getSettingsFingerprint(), newFingerprint);
        state.setStrategySettings(strategy);
        state.setScalpingSettings(settings);
        state.setExchange(firstNonBlank(upper(strategy.getExchangeName()), state.getExchange(), "BINANCE"));
        state.setNetwork(strategy.getNetworkType() != null ? strategy.getNetworkType() : state.getNetwork());
        state.setSymbol(firstNonBlank(upper(strategy.getSymbol()), upper(settings.getSymbol()), state.getSymbol()));
        state.setTimeframe(firstNonBlank(lower(strategy.getTimeframe()), lower(settings.getTimeframe()), state.getTimeframe()));
        state.setSettingsFingerprint(newFingerprint);
        state.setLastSettingsLoadAt(now);

        if (changed) {
            log.info("[SCALPING] ⚙️ Настройки обновлены chatId={} symbol={} tf={} trend={} range={} breakout={} spreadMax={} atr=[{},{}] volumeMin={}",
                    chatId,
                    state.getSymbol(),
                    state.getTimeframe(),
                    settings.getAllowTrendTrades(),
                    settings.getAllowRangeTrades(),
                    settings.getAllowBreakoutTrades(),
                    settings.getMaxSpreadPct(),
                    settings.getMinAtrPct(),
                    settings.getMaxAtrPct(),
                    settings.getMinVolumeRatio());
        }
    }

    private void logRegimeIfChanged(Long chatId,
                                    ScalpingRuntimeState state,
                                    ScalpingMarketRegimeSnapshot snapshot,
                                    ScalpingFeatureSnapshot features) {
        String signature = snapshot.regime() + "|" + snapshot.reason();
        if (Objects.equals(signature, state.getLastRegimeLogSignature())) {
            return;
        }
        state.setLastRegimeLogSignature(signature);
        log.info("[SCALPING] 📊 Режим рынка chatId={} symbol={} tf={} regime={} reason={} trend={} range={} chaos={} squeeze={} atr={} spread={} rsi={} volRatio={} emaFast={} emaSlow={}",
                chatId,
                state.getSymbol(),
                state.getTimeframe(),
                snapshot.regime(),
                snapshot.reason(),
                fmt(snapshot.trendScore()),
                fmt(snapshot.rangeScore()),
                fmt(snapshot.chaosScore()),
                fmt(snapshot.squeezeScore()),
                fmt(features.atrPct()),
                fmt(features.spreadPct()),
                fmt(features.rsi()),
                fmt(features.volumeRatio()),
                fmt(features.emaFast()),
                fmt(features.emaSlow()));
    }

    private void pushHold(Long chatId,
                          ScalpingRuntimeState state,
                          String code,
                          String text,
                          Instant now) {
        if (state == null || state.getSymbol() == null) return;
        Instant logNow = Instant.now();
        String holdSignature = (code == null ? "hold" : code) + "|" + (text == null ? "" : text);
        if (Objects.equals(state.getLastHoldReason(), holdSignature)
                && state.getLastHoldAt() != null
                && Duration.between(state.getLastHoldAt(), logNow).compareTo(HOLD_LOG_EVERY) < 0) {
            return;
        }
        state.setLastHoldReason(holdSignature);
        state.setLastHoldAt(logNow);
        adaptiveRuntimeController.onHold(chatId, StrategyType.SCALPING, state.getExchange(), state.getNetwork(), code, logNow);
        live.pushSignal(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTimeframe(), Signal.hold(text));

        boolean noisyTechnicalReason = Objects.equals(code, "warmup")
                || Objects.equals(code, "features_unavailable")
                || Objects.equals(code, "decision_blocked");

        if (noisyTechnicalReason) {
            if (log.isDebugEnabled()) {
                log.debug("[SCALPING] ⏸ Вход пока не открыт chatId={} symbol={} code={} text={}", chatId, state.getSymbol(), code, text);
            }
            return;
        }

        log.info("[SCALPING] ⏸ Вход пропущен chatId={} symbol={} code={} text={}", chatId, state.getSymbol(), code, text);
    }

    private boolean matchesContext(ScalpingRuntimeState state,
                                   String symbol,
                                   String timeframe,
                                   String exchange,
                                   NetworkType network) {
        String sym = upper(symbol);
        String tf = lower(timeframe);
        String ex = upper(exchange);
        if (sym != null && state.getSymbol() != null && !state.getSymbol().equals(sym)) return false;
        if (tf != null && state.getTimeframe() != null && !state.getTimeframe().equals(tf)) return false;
        if (ex != null && state.getExchange() != null && !state.getExchange().equalsIgnoreCase(ex)) return false;
        return network == null || state.getNetwork() == null || state.getNetwork() == network;
    }

    private ScalpingFeatureCalculator.CandleInput toCandleInput(UnifiedKline kline, Instant fallbackTs) {
        return new ScalpingFeatureCalculator.CandleInput(
                fallbackTs != null ? fallbackTs : Instant.now(),
                positive(kline.getOpen()),
                positive(kline.getHigh()),
                positive(kline.getLow()),
                positive(kline.getClose()),
                positive(kline.getVolume())
        );
    }

    private String buildSettingsFingerprint(StrategySettings strategy, ScalpingStrategySettings settings) {
        if (strategy == null || settings == null) return "null";
        return upper(strategy.getExchangeName()) + "|" + strategy.getNetworkType() + "|" + upper(strategy.getSymbol()) + "|" + lower(strategy.getTimeframe())
                + "|" + settings.getAllowTrendTrades() + "|" + settings.getAllowRangeTrades() + "|" + settings.getAllowBreakoutTrades()
                + "|" + settings.getTrendTpPct() + "|" + settings.getTrendSlPct() + "|" + settings.getRangeTpPct() + "|" + settings.getRangeSlPct()
                + "|" + settings.getBreakoutTpPct() + "|" + settings.getBreakoutSlPct() + "|" + settings.getMaxSpreadPct()
                + "|" + settings.getMinAtrPct() + "|" + settings.getMaxAtrPct() + "|" + settings.getMinVolumeRatio();
    }

    private String humanizeGuard(ScalpingExecutionGuard.GuardResult guard) {
        if (guard == null) return "guard вернул пустой результат";
        return switch (guard.code()) {
            case "spread_bad" -> "Спред плохой, вход запрещён";
            case "atr_too_small" -> "ATR слишком маленький, движения не хватит";
            case "atr_too_large" -> "ATR слишком большой, рынок слишком нервный";
            case "volume_low" -> "Объём слабый, импульс не подтверждён";
            case "risk_reward_low" -> "Отношение риск/прибыль слишком слабое";
            case "wallet_base_untracked_position" -> "На кошельке уже есть base-актив, сначала синхронизируй позицию";
            case "decision_blocked" -> guard.message();
            case "regime_block", "trend_down_spot_block" -> "Текущий режим рынка не подходит для спота";
            case "cooldown_after_stop" -> "После стопа ещё действует защитный кулдаун";
            case "cooldown_after_exit" -> "Слишком рано после прошлого выхода";
            case "max_consecutive_stops" -> "Слишком много стопов подряд, временно не торгую";
            case "net_edge_low" -> "Ожидаемая прибыль после спреда слишком мала";
            case "fee_floor_block" -> "TP не перекрывает комиссии биржи";
            default -> guard.message() != null ? guard.message() : guard.code();
        };
    }

    private boolean hasPositiveNetEdge(EntryDecision decision, ScalpingFeatureSnapshot features) {
        if (decision == null || decision.tpPct() == null || decision.slPct() == null || features == null || features.spreadPct() == null) {
            return false;
        }
        if (decision.tpPct().signum() <= 0 || decision.slPct().signum() <= 0 || features.spreadPct().signum() < 0) {
            return false;
        }

        BigDecimal spreadCostPct = features.spreadPct().multiply(BigDecimal.valueOf(2));
        BigDecimal minTpWithSpread = spreadCostPct.add(MIN_SPREAD_EDGE_PCT);
        if (decision.tpPct().compareTo(minTpWithSpread) < 0) {
            return false;
        }

        BigDecimal netTpPct = decision.tpPct().subtract(spreadCostPct);
        return netTpPct.compareTo(decision.slPct()) > 0;
    }

    private boolean passesFeeFloor(Long chatId, ScalpingRuntimeState state, EntryDecision decision) {
        if (chatId == null || state == null || decision == null || decision.tpPct() == null || decision.slPct() == null) {
            return false;
        }
        BigDecimal minHealthyTp = tradeExecutionService.resolveMinHealthyTpPct(
                chatId,
                state.getExchange(),
                state.getNetwork(),
                decision.slPct()
        );
        return decision.tpPct().compareTo(minHealthyTp) >= 0;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String upper(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) return null;
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null && !current.getMessage().isBlank() ? current.getMessage() : throwable.getClass().getSimpleName();
    }
}



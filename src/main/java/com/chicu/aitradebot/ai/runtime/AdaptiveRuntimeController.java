package com.chicu.aitradebot.ai.runtime;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.service.StrategySettingsService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveRuntimeController {

    private record Key(long chatId, StrategyType type, String exchange, NetworkType network) {}

    private final StrategyPerformanceMonitor performanceMonitor;
    private final StrategySettingsService strategySettingsService;
    private final ObjectProvider<AiStrategyOrchestrator> orchestratorProvider;

    @Value("${adaptive.runtime.enabled:true}")
    private boolean enabled;

    @Value("${adaptive.runtime.reviewEverySeconds:30}")
    private long reviewEverySeconds;

    @Value("${adaptive.runtime.decisionCooldownSeconds:60}")
    private long decisionCooldownSeconds;

    @Value("${adaptive.runtime.minCandlesBeforeStarvation:6}")
    private long minCandlesBeforeStarvation;

    @Value("${adaptive.runtime.starvationCandles:12}")
    private long starvationCandles;

    @Value("${adaptive.runtime.lossTradesBeforeDefensive:3}")
    private long lossTradesBeforeDefensive;

    @Value("${adaptive.runtime.lossPnlPctThreshold:-0.35}")
    private BigDecimal lossPnlPctThreshold;

    @Value("${adaptive.runtime.profitTradesBeforeExpand:4}")
    private long profitTradesBeforeExpand;

    @Value("${adaptive.runtime.profitPnlPctThreshold:0.30}")
    private BigDecimal profitPnlPctThreshold;

    @Value("${adaptive.runtime.sameReasonCooldownSeconds:120}")
    private long sameReasonCooldownSeconds;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "adaptive-runtime");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<Key, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, Instant> lastDecisionAt = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, String> lastRegimeSignature = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, String> lastDecisionSignature = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, Instant> lastSameDecisionAt = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> future : jobs.values()) {
            try {
                future.cancel(false);
            } catch (Exception ignored) {
            }
        }
        jobs.clear();
        scheduler.shutdownNow();
    }

    public void onStrategyStarted(long chatId,
                                  StrategyType type,
                                  String exchange,
                                  NetworkType network,
                                  String symbol,
                                  String timeframe) {
        if (!enabled || type == null) {
            return;
        }
        Key key = key(chatId, type, exchange, network);
        performanceMonitor.onStrategyStarted(chatId, type, exchange, network, symbol, timeframe);

        jobs.computeIfAbsent(key, ignored -> scheduler.scheduleWithFixedDelay(
                () -> review(chatId, type, exchange, network),
                Math.max(10L, reviewEverySeconds),
                Math.max(10L, reviewEverySeconds),
                TimeUnit.SECONDS
        ));
    }

    public void onStrategyStopped(long chatId, StrategyType type, String exchange, NetworkType network) {
        Key key = key(chatId, type, exchange, network);
        ScheduledFuture<?> future = jobs.remove(key);
        if (future != null) {
            try {
                future.cancel(false);
            } catch (Exception ignored) {
            }
        }
        performanceMonitor.onStrategyStopped(chatId, type, exchange, network);
        lastDecisionAt.remove(key);
        lastRegimeSignature.remove(key);
        lastDecisionSignature.remove(key);
        lastSameDecisionAt.remove(key);
    }

    public void onTick(long chatId, StrategyType type, String exchange, NetworkType network) {
        performanceMonitor.onTick(chatId, type, exchange, network);
    }

    public void onCandleObserved(long chatId,
                                 StrategyType type,
                                 String exchange,
                                 NetworkType network,
                                 String symbol,
                                 String timeframe,
                                 BigDecimal atrPct,
                                 BigDecimal spreadPct,
                                 BigDecimal volumeRatio,
                                 Instant at) {
        performanceMonitor.onCandleObserved(chatId, type, exchange, network, symbol, timeframe, atrPct, spreadPct, volumeRatio, at);
        review(chatId, type, exchange, network);
    }

    public void onHold(long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String reason,
                       Instant at) {
        performanceMonitor.onHold(chatId, type, exchange, network, reason, at);
    }

    public void onEntry(long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType network,
                        String symbol,
                        String timeframe,
                        Instant at) {
        performanceMonitor.onEntry(chatId, type, exchange, network, symbol, timeframe, at);
    }

    public void onExit(long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol,
                       String timeframe,
                       BigDecimal pnlPct,
                       BigDecimal pnlUsd,
                       String exitReason,
                       BigDecimal holdSeconds,
                       Instant at) {
        performanceMonitor.onExit(chatId, type, exchange, network, symbol, timeframe, pnlPct, pnlUsd, exitReason, holdSeconds, at);
        review(chatId, type, exchange, network);
    }

    public StrategyPerformanceSnapshot snapshot(long chatId,
                                                StrategyType type,
                                                String exchange,
                                                NetworkType network) {
        return performanceMonitor.getSnapshot(chatId, type, exchange, network);
    }

    private void review(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (!enabled || type == null) {
            return;
        }

        StrategySettings settings = loadSettings(chatId, type);
        if (!isAdaptiveAllowed(settings)) {
            return;
        }

        StrategyPerformanceSnapshot snapshot = performanceMonitor.getSnapshot(chatId, type, exchange, network);
        Key key = key(chatId, type, exchange, network);
        AdaptiveRuntimeDecision decision = decide(key, snapshot, settings);
        if (decision.isNoop()) {
            return;
        }

        String decisionSignature = decision.action() + "|" + decision.reason();
        String prevSignature = lastDecisionSignature.get(key);
        Instant prevSameAt = lastSameDecisionAt.get(key);
        long sameCooldown = Math.max(30L, sameReasonCooldownSeconds);
        if (decisionSignature.equals(prevSignature)
                && prevSameAt != null
                && Duration.between(prevSameAt, Instant.now()).toSeconds() < sameCooldown) {
            return;
        }

        AiStrategyOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            log.warn("🤖 [ADAPTIVE] orchestrator missing chatId={} type={} reason={}", chatId, type, decision.reason());
            return;
        }

        if (decision.triggerTrain()) {
            orchestrator.triggerTrainDebounced(chatId, type, safeUpper(exchange), network, decision.reason(), decision.trainDebounce());
        }
        if (decision.triggerTune()) {
            orchestrator.triggerTuneDebounced(chatId, type, safeUpper(exchange), network, decision.reason(), decision.tuneDebounce());
        }

        Instant now = Instant.now();
        lastDecisionAt.put(key, now);
        lastDecisionSignature.put(key, decisionSignature);
        lastSameDecisionAt.put(key, now);

        log.info("🤖 [ADAPTIVE] action={} chatId={} type={} ex={} net={} sym={} tf={} reason={} candles={} entries={} closed={} rollingPnlPct={} blocker={}",
                decision.action(),
                chatId,
                type,
                safeUpper(exchange),
                network,
                snapshot.symbol(),
                snapshot.timeframe(),
                decision.reason(),
                snapshot.candles(),
                snapshot.entries(),
                snapshot.closedTrades(),
                snapshot.rollingPnlPct().stripTrailingZeros().toPlainString(),
                snapshot.dominantBlocker());
    }

    private AdaptiveRuntimeDecision decide(Key key,
                                           StrategyPerformanceSnapshot snapshot,
                                           StrategySettings settings) {
        if (snapshot == null) {
            return AdaptiveRuntimeDecision.none("snapshot_missing");
        }
        if (snapshot.inPosition()) {
            return AdaptiveRuntimeDecision.none("in_position");
        }
        if (isCoolingDown(key)) {
            return AdaptiveRuntimeDecision.none("decision_cooldown");
        }

        String dominantBlocker = safeBlocker(snapshot.dominantBlocker(), snapshot.lastBlockReason());

        if (snapshot.candles() >= minCandlesBeforeStarvation
                && !snapshot.hasEntries()
                && snapshot.isStarving(starvationCandles)) {
            return AdaptiveRuntimeDecision.tune(
                    "STARVATION_BOOTSTRAP",
                    "starvation:" + dominantBlocker,
                    Duration.ofSeconds(45)
            );
        }

        if (snapshot.candlesSinceLastEntry() >= starvationCandles && dominantBlocker != null) {
            return AdaptiveRuntimeDecision.tune(
                    "STARVATION_RELAX",
                    "starvation:" + dominantBlocker,
                    Duration.ofSeconds(45)
            );
        }

        if (snapshot.closedTrades() >= lossTradesBeforeDefensive
                && (snapshot.lossStreak() >= 2 || snapshot.isLosing(lossPnlPctThreshold))) {
            return AdaptiveRuntimeDecision.trainAndTune(
                    "DEFENSIVE_RECOVERY",
                    "loss_recovery:" + dominantBlocker,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(30)
            );
        }

        if (snapshot.closedTrades() >= profitTradesBeforeExpand
                && snapshot.isProfitable(profitPnlPctThreshold)
                && snapshot.rollingWinRate().compareTo(new BigDecimal("0.55")) >= 0) {
            return AdaptiveRuntimeDecision.tune(
                    "PROFIT_EXPAND",
                    "profit_expand:" + dominantBlocker,
                    Duration.ofSeconds(90)
            );
        }

        String regime = regimeSignature(snapshot);
        String lastRegime = lastRegimeSignature.put(key, regime);
        if (snapshot.candles() >= minCandlesBeforeStarvation && lastRegime != null && !lastRegime.equals(regime)) {
            return AdaptiveRuntimeDecision.tune(
                    "REGIME_SHIFT",
                    "regime_shift:" + dominantBlocker,
                    Duration.ofSeconds(60)
            );
        }

        return AdaptiveRuntimeDecision.none("steady");
    }

    private boolean isCoolingDown(Key key) {
        Instant last = lastDecisionAt.get(key);
        return last != null && Duration.between(last, Instant.now()).toSeconds() < Math.max(10L, decisionCooldownSeconds);
    }

    private StrategySettings loadSettings(long chatId, StrategyType type) {
        try {
            StrategySettings settings = strategySettingsService.getSettings(chatId, type);
            return settings != null ? settings : strategySettingsService.getOrCreate(chatId, type);
        } catch (Exception e) {
            log.warn("🤖 [ADAPTIVE] settings load failed chatId={} type={} err={}", chatId, type, e.toString());
            return null;
        }
    }

    private boolean isAdaptiveAllowed(StrategySettings settings) {
        if (settings == null || !settings.isActive()) {
            return false;
        }
        AdvancedControlMode mode = settings.getAdvancedControlMode() != null
                ? settings.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;
        if (mode == AdvancedControlMode.MANUAL) {
            return false;
        }
        String phase = settings.getRunPhase() == null ? "LIVE" : settings.getRunPhase().trim().toUpperCase();
        return !"BACKTEST".equals(phase) && !"COLLECT".equals(phase);
    }

    private static Key key(long chatId, StrategyType type, String exchange, NetworkType network) {
        return new Key(chatId, type, safeUpper(exchange), network);
    }

    private static String regimeSignature(StrategyPerformanceSnapshot snapshot) {
        return bucket(snapshot.avgAtrPct(), new BigDecimal("0.10"))
                + "|" + bucket(snapshot.avgSpreadPct(), new BigDecimal("0.05"))
                + "|" + bucket(snapshot.avgVolumeRatio(), new BigDecimal("0.10"));
    }

    private static String bucket(BigDecimal value, BigDecimal step) {
        BigDecimal safeValue = value == null ? BigDecimal.ZERO : value.abs();
        BigDecimal safeStep = step == null || step.signum() <= 0 ? BigDecimal.ONE : step;
        return safeValue.divide(safeStep, 0, BigDecimal.ROUND_FLOOR).toPlainString();
    }

    private static String safeBlocker(String dominant, String fallback) {
        String first = trimToNull(dominant);
        if (first != null) {
            return first;
        }
        String second = trimToNull(fallback);
        return second != null ? second : "unknown";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeUpper(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }
}



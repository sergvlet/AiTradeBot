package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingServiceImpl;
import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsCommandService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.chicu.aitradebot.trade.ExitResult;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import jakarta.annotation.PostConstruct;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private static final BigDecimal PROB_MIN = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
    private static final BigDecimal PROB_MAX = BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);
    private static final BigDecimal DEFAULT_GATE_MIN_PROB = new BigDecimal("0.550000");

    private final OrderService orderService;
    private final StrategySettingsCommandService strategySettingsCommandService;
    private final StrategySettingsService settingsService;
    private final StrategySettingsRepository strategySettingsRepository;
    private final StrategyRegistry strategyRegistry;
    private final MarketStreamService marketStreamService;
    private final MarketDataStreamService marketDataStreamService;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;

    /**
     * ML autotune runtime (оркестратор — главный lifecycle хаб)
     * ObjectProvider — защита от циклов и от временного отсутствия ML-слоя
     */
    private final ObjectProvider<MlAutoTuneRuntime> mlAutoTuneRuntime;
    private final ObjectProvider<MlGateway> mlGatewayProvider;
    private final ObjectProvider<MlTrainingServiceImpl> mlTrainingServiceProvider;
    private final ObjectProvider<AutoTunerOrchestrator> autoTunerProvider;

    @Value("${orch.market-events.listener-enabled:false}")
    private boolean eventBridgeEnabled;

    @Value("${orch.market-events.block-when-degraded:true}")
    private boolean blockWhenDegraded;

    @Value("${orch.market-events.degraded-log-cooldown-ms:30000}")
    private long degradedLogCooldownMs;

    @Value("${orch.market-events.degraded-bypass-max-age-ms:5000}")
    private long degradedBypassMaxAgeMs;

    @Value("${orch.startup-restore.initial-delay-ms:1500}")
    private long startupRestoreInitialDelayMs;

    @Value("${orch.startup-restore.ml-wait-ms:15000}")
    private long startupRestoreMlWaitMs;

    private MlAutoTuneRuntime ml() {
        return mlAutoTuneRuntime != null ? mlAutoTuneRuntime.getIfAvailable() : null;
    }

    private MlGateway mlGateway() {
        return mlGatewayProvider != null ? mlGatewayProvider.getIfAvailable() : null;
    }

    private MlTrainingServiceImpl trainer() {
        return mlTrainingServiceProvider != null ? mlTrainingServiceProvider.getIfAvailable() : null;
    }

    private AutoTunerOrchestrator autoTuner() {
        return autoTunerProvider != null ? autoTunerProvider.getIfAvailable() : null;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    private static String readStringNoThrow(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;

        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) continue;
            try {
                var m = target.getClass().getMethod(methodName);
                Object v = m.invoke(target);
                if (v == null) continue;

                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Boolean readBooleanNoThrow(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) {
                continue;
            }
            try {
                var m = target.getClass().getMethod(methodName);
                Object v = m.invoke(target);
                if (v instanceof Boolean b) {
                    return b;
                }
                if (v != null) {
                    return Boolean.parseBoolean(String.valueOf(v));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String resolveEffectiveModelVersion(StrategyType type, StrategySettings s) {
        String fromSettings = blankToNull(s != null ? s.getMlModelVersion() : null);
        if (fromSettings != null) {
            return fromSettings;
        }

        if (requiresContextBoundPreparedModel(type)) {
            return null;
        }

        try {
            MlGateway gw = mlGateway();
            if (gw == null || !gw.isEnabled()) {
                return null;
            }

            Object health = gw.health();
            if (health == null) {
                return null;
            }

            return blankToNull(readStringNoThrow(
                    health,
                    "getModelVersion",
                    "getModel_version",
                    "getVersion",
                    "getCurrentModelVersion",
                    "getCurrent_model_version"
            ));
        } catch (Exception ignored) {
            return null;
        }
    }

    // =====================================================================
    // RUN CONTEXT (источник истины)
    // =====================================================================

    /** Жёстко фиксируем один запуск на (chatId,type). */
    private record RunKey(long chatId, StrategyType type) {}

    public record RunBinding(
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe,
            Instant startedAt
    ) {}

    private final ConcurrentMap<RunKey, RunBinding> running = new ConcurrentHashMap<>();

    /** Счётчик “игноров”, чтобы логировать предсказуемо (первые 3 + каждый 200-й). */
    private final ConcurrentMap<RunKey, AtomicLong> ignoreCounters = new ConcurrentHashMap<>();

    /** Дебаунс ручных триггеров тюнинга. */
    private final ConcurrentMap<RunKey, Long> lastTuneTriggerAtMs = new ConcurrentHashMap<>();

    /** Дебаунс ручных триггеров обучения. */
    private final ConcurrentMap<RunKey, Long> lastTrainTriggerAtMs = new ConcurrentHashMap<>();

    /** Антиспам логов degraded. */
    private final ConcurrentMap<RunKey, Long> lastDegradedLogAtMs = new ConcurrentHashMap<>();

    // 🔒 атомарность операций на (chatId,type)
    private final ConcurrentMap<RunKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(RunKey key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    // =====================================================================
    // RUNTIME POLICY CACHE (чтобы не лезть в БД на каждый тик)
    // =====================================================================

    private boolean requiresContextBoundPreparedModel(StrategyType type) {
        if (type == null) {
            return false;
        }
        return type == StrategyType.WINDOW_SCALPING
                || type == StrategyType.EMA_CROSSOVER
                || type == StrategyType.FIBONACCI_GRID
                || type == StrategyType.SCALPING;
    }

    private record RuntimePolicy(
            AdvancedControlMode mode,
            String runPhase,
            boolean autoTuneEnabled,
            boolean mlGateEnabled,
            BigDecimal gateMinProb,
            String mlModelVersion
    ) {}

    private final ConcurrentMap<RunKey, RuntimePolicy> runtimePolicyCache = new ConcurrentHashMap<>();

    private static String sanitizePhase(String p) {
        if (p == null) return "LIVE";
        String x = p.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? "LIVE" : x;
    }

    private RuntimePolicy policyOf(StrategyType type, StrategySettings s) {
        AdvancedControlMode m = (s != null && s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        String rp = sanitizePhase(s != null ? s.getRunPhase() : null);

        boolean autoTune = (s != null) && s.isAutoTuneEnabled();
        boolean gate = (s != null) && s.isMlGateEnabled();
        BigDecimal thr = (s != null) ? s.getGateMinProb() : null;
        String modelVer = resolveEffectiveModelVersion(type, s);

        return new RuntimePolicy(m, rp, autoTune, gate, thr, modelVer);
    }

    /** блокируем торговые события для фаз COLLECT/BACKTEST */
    private boolean isMarketEventsBlocked(RunKey key) {
        RuntimePolicy rp = runtimePolicyCache.get(key);
        if (rp == null) return false;
        String phase = rp.runPhase();
        return "COLLECT".equalsIgnoreCase(phase) || "BACKTEST".equalsIgnoreCase(phase);
    }

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4 initialized | mlRuntime={} | eventBridge={} | blockWhenDegraded={}",
                (ml() != null ? "ON" : "OFF"),
                eventBridgeEnabled,
                blockWhenDegraded);

        restoreActiveStrategiesAsync();
    }

    private void restoreActiveStrategiesAsync() {
        CompletableFuture.runAsync(() -> {
            if (!sleepQuietly(Math.max(1000L, startupRestoreInitialDelayMs))) {
                return;
            }

            waitForMlReadinessBeforeRestore();

            try {
                restoreActiveStrategiesBestEffort();
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] active restore failed: {}", e.toString());
            }
        });
    }

    private void waitForMlReadinessBeforeRestore() {
        MlGateway gw = mlGateway();
        if (gw == null) {
            return;
        }

        boolean enabled;
        try {
            enabled = gw.isEnabled();
        } catch (Exception e) {
            log.debug("⚠️ [ORCH] ML readiness check skipped: {}", e.toString());
            return;
        }

        if (!enabled) {
            return;
        }

        long waitMs = Math.max(0L, startupRestoreMlWaitMs);
        if (waitMs <= 0L) {
            return;
        }

        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (isMlReadyForRestore(gw)) {
                log.info("🧠 [ORCH] startup restore: ML sidecar is ready");
                return;
            }

            if (!sleepQuietly(500L)) {
                return;
            }
        }

        log.warn("⚠️ [ORCH] startup restore: ML sidecar readiness wait timed out after {} ms", waitMs);
    }

    private boolean isMlReadyForRestore(MlGateway gw) {
        try {
            Object health = gw.health();
            if (health == null) {
                return false;
            }

            Boolean ok = readBooleanNoThrow(health, "isOk", "getOk", "ok");
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }

            String status = readStringNoThrow(health, "getStatus", "status", "getState", "state");
            return status != null
                    && ("ok".equalsIgnoreCase(status) || "healthy".equalsIgnoreCase(status) || "up".equalsIgnoreCase(status));
        } catch (Exception e) {
            log.debug("⚠️ [ORCH] ML readiness probe failed: {}", e.toString());
            return false;
        }
    }

    private void restoreActiveStrategiesBestEffort() {
        List<StrategySettings> all = discoverAllStrategySettings();
        if (all.isEmpty()) {
            log.info("🧠 [ORCH] startup restore: active StrategySettings not discovered");
            return;
        }

        int restored = 0;
        int skipped = 0;

        for (StrategySettings s : all) {
            if (s == null || !s.isActive() || s.getChatId() == null || s.getChatId() <= 0 || s.getType() == null) {
                continue;
            }

            Long chatId = s.getChatId();
            StrategyType type = s.getType();
            String ex = sanitizeExchange(s.getExchangeName());
            NetworkType net = s.getNetworkType();

            if (isRunning(chatId, type, ex, net)) {
                skipped++;
                continue;
            }

            try {
                StrategyRunInfo info = startStrategy(chatId, type, ex, net);
                boolean ok = info != null && info.isActive();
                if (ok) {
                    restored++;
                    log.info("♻️ [ORCH] startup restore OK chatId={} type={} ex={} net={} sym={} tf={}",
                            chatId,
                            type,
                            ex,
                            net,
                            info.getSymbol(),
                            info.getTimeframe());
                } else {
                    skipped++;
                    log.warn("⚠️ [ORCH] startup restore skipped chatId={} type={} ex={} net={} reason={}",
                            chatId,
                            type,
                            ex,
                            net,
                            info != null ? info.getMessage() : "null_info");
                }
            } catch (Exception e) {
                skipped++;
                log.warn("⚠️ [ORCH] startup restore exception chatId={} type={} ex={} net={} err={}",
                        chatId,
                        type,
                        ex,
                        net,
                        e.toString());
            }
        }

        log.info("🧠 [ORCH] startup restore finished restored={} skipped={} discovered={}", restored, skipped, all.size());
    }

    @SuppressWarnings("unchecked")
    private List<StrategySettings> discoverAllStrategySettings() {
        try {
            List<StrategySettings> fromRepo = strategySettingsRepository.findAll();
            if (fromRepo != null && !fromRepo.isEmpty()) {
                return fromRepo;
            }
        } catch (Exception e) {
            log.debug("⚠️ [ORCH] repository findAll failed: {}", e.toString());
        }

        List<StrategySettings> direct = extractStrategySettings(invokeNoArg(settingsService,
                "findAll",
                "listAll",
                "getAll",
                "findAllSettings",
                "findAllActive",
                "findActive",
                "listActive"
        ));
        if (!direct.isEmpty()) {
            return direct;
        }

        for (java.lang.reflect.Field field : settingsService.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object candidate = field.get(settingsService);
                if (candidate == null) {
                    continue;
                }

                List<StrategySettings> extracted = extractStrategySettings(invokeNoArg(candidate,
                        "findAll",
                        "listAll",
                        "getAll",
                        "findAllSettings",
                        "findAllActive",
                        "findActive",
                        "listActive"
                ));
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            } catch (Exception ignored) {
            }
        }

        return List.of();
    }

    private Object invokeNoArg(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) {
                continue;
            }
            try {
                var m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<StrategySettings> extractStrategySettings(Object raw) {
        if (raw == null) {
            return List.of();
        }

        List<StrategySettings> out = new ArrayList<>();

        if (raw instanceof StrategySettings one) {
            out.add(one);
            return out;
        }

        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof StrategySettings s) {
                    out.add(s);
                }
            }
            return out;
        }

        if (raw.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(raw, i);
                if (item instanceof StrategySettings s) {
                    out.add(s);
                }
            }
            return out;
        }

        try {
            var contentMethod = raw.getClass().getMethod("getContent");
            Object content = contentMethod.invoke(raw);
            if (content instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item instanceof StrategySettings s) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    public Optional<RunBinding> getBinding(long chatId, StrategyType type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(running.get(new RunKey(chatId, type)));
    }

    public boolean isRunning(long chatId, StrategyType type) {
        if (type == null) return false;
        return running.containsKey(new RunKey(chatId, type));
    }

    /**
     * Проверка “запущено ли в конкретном контексте” (exchange/network).
     * Если exchange == null → не проверяем exchange.
     * Если network == null → не проверяем network.
     */
    public boolean isRunning(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (type == null) return false;

        RunBinding b = running.get(new RunKey(chatId, type));
        if (b == null) return false;

        String ex = sanitizeExchange(exchange);
        if (ex != null && !eq(ex, b.exchange())) return false;

        if (network != null && network != b.network()) return false;

        return true;
    }

    // =====================================================================
    // MARKET EVENTS: слушаем события из MarketDataStreamService
    // =====================================================================

    /**
     * По умолчанию выключено, чтобы не было двойного диспатча:
     * MarketStreamServiceImpl уже напрямую вызывает onPriceUpdate/onCandleClosed.
     */
    @EventListener
    public void onMarketTickEvent(MarketDataStreamService.MarketTickEvent ev) {
        if (!eventBridgeEnabled) return;
        if (ev == null) return;
        onPriceUpdate(
                ev.chatId(),
                ev.strategyType(),
                ev.exchange(),
                ev.networkType(),
                ev.symbol(),
                ev.timeframe(),
                ev.price(),
                ev.tsMs()
        );
    }

    /**
     * По умолчанию выключено, чтобы не было двойного диспатча.
     */
    @EventListener
    public void onCandleClosedEvent(MarketDataStreamService.CandleClosedEvent ev) {
        if (!eventBridgeEnabled) return;
        if (ev == null) return;
        UnifiedKline k = ev.kline();
        onCandleClosed(
                ev.chatId(),
                ev.strategyType(),
                ev.exchange(),
                ev.networkType(),
                ev.symbol(),
                ev.timeframe(),
                k
        );
    }

    // =====================================================================
    // RUNTIME POLICY (единые правила режима/фазы)
    // =====================================================================

    private static AdvancedControlMode safeMode(StrategySettings s) {
        return (s != null && s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;
    }

    private static BigDecimal clampProb(BigDecimal v) {
        if (v == null) return null;
        BigDecimal x = v.setScale(6, RoundingMode.HALF_UP);
        if (x.compareTo(PROB_MIN) < 0) return PROB_MIN;
        if (x.compareTo(PROB_MAX) > 0) return PROB_MAX;
        return x;
    }

    /**
     * Единая точка принятия решения:
     * - приводит настройки к жёстким правилам MANUAL/HYBRID/AI (и сохраняет при необходимости)
     * - обновляет runtimePolicyCache
     * - включает/выключает MlAutoTuneRuntime
     */
    private void applyRuntimePolicy(long chatId,
                                    StrategyType type,
                                    String exchange,
                                    NetworkType network,
                                    StrategySettings s) {
        if (type == null || s == null) return;

        RunKey key = new RunKey(chatId, type);

        AdvancedControlMode mode = safeMode(s);
        String phase = sanitizePhase(s.getRunPhase());

        // AUTO-FIX: если по старой логике AI/HYBRID загнал фазу в COLLECT — ордера не будут выставляться.
        boolean phaseAutoFixed = false;
        if (mode != AdvancedControlMode.MANUAL && "COLLECT".equalsIgnoreCase(phase)) {
            NetworkType net = (network != null) ? network : s.getNetworkType();
            String newPhase = (net == NetworkType.TESTNET) ? "PAPER" : "LIVE";
            if (!newPhase.equalsIgnoreCase(phase)) {
                phase = newPhase;
                s.setRunPhase(newPhase);
                phaseAutoFixed = true;
            }
        }

        boolean desiredAutoTune;
        boolean desiredGateEnabled;
        BigDecimal desiredGateMinProb;

        if (mode == AdvancedControlMode.MANUAL) {
            desiredAutoTune = false;
            desiredGateEnabled = false;
            desiredGateMinProb = null;
        } else if (mode == AdvancedControlMode.HYBRID) {
            desiredAutoTune = true;
            desiredGateEnabled = true;
            desiredGateMinProb = clampProb(s.getGateMinProb() != null ? s.getGateMinProb() : DEFAULT_GATE_MIN_PROB);
        } else {
            desiredAutoTune = true;
            desiredGateEnabled = s.isMlGateEnabled();
            desiredGateMinProb = desiredGateEnabled
                    ? clampProb(s.getGateMinProb() != null ? s.getGateMinProb() : DEFAULT_GATE_MIN_PROB)
                    : null;
        }

        boolean changed = false;
        if (phaseAutoFixed) changed = true;

        if (s.isAutoTuneEnabled() != desiredAutoTune) {
            s.setAutoTuneEnabled(desiredAutoTune);
            changed = true;
        }
        if (s.isMlGateEnabled() != desiredGateEnabled) {
            s.setMlGateEnabled(desiredGateEnabled);
            changed = true;
        }

        BigDecimal currentThr = s.getGateMinProb();
        if (desiredGateMinProb == null) {
            if (currentThr != null) {
                s.setGateMinProb(null);
                changed = true;
            }
        } else {
            BigDecimal cur = (currentThr == null) ? null : clampProb(currentThr);
            if (cur == null || cur.compareTo(desiredGateMinProb) != 0) {
                s.setGateMinProb(desiredGateMinProb);
                changed = true;
            }
        }

        if (changed) {
            try {
                s = saveSettingsWithRetry(s, "applyRuntimePolicy");
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] applyRuntimePolicy save failed chatId={} type={} : {}", chatId, type, e.getMessage());
            }
        }

        BigDecimal effThr = desiredGateEnabled ? desiredGateMinProb : null;
        String effectiveModelVer = resolveEffectiveModelVersion(type, s);

        RuntimePolicy rp = new RuntimePolicy(
                mode,
                phase,
                desiredAutoTune,
                desiredGateEnabled,
                effThr,
                effectiveModelVer
        );
        runtimePolicyCache.put(key, rp);

        if (mode == AdvancedControlMode.MANUAL) {
            safeAutotuneStop(chatId, type, exchange, network);
        } else {
            if (desiredAutoTune) safeAutotuneStart(chatId, type, exchange, network);
            else safeAutotuneStop(chatId, type, exchange, network);
        }

        log.info("🧩 [ORCH] policy chatId={} type={} mode={} phase={} autoTune={} mlGate={} thr={} modelVer={}",
                chatId, type, mode, phase,
                desiredAutoTune,
                desiredGateEnabled,
                (effThr != null ? effThr.toPlainString() : "null"),
                (effectiveModelVer != null ? effectiveModelVer : "null")
        );
    }

    /**
     * Подстраховка: если стратегия запущена, но runtimePolicyCache пуст.
     */
    private void ensurePolicyLoadedIfMissing(long chatId, StrategyType type, RunBinding b) {
        if (type == null || b == null) return;

        RunKey key = new RunKey(chatId, type);
        if (runtimePolicyCache.containsKey(key)) return;

        try {
            StrategySettings s = loadSettingsReadOnly(chatId, type);
            if (s == null) return;
            syncSettingsContextIfNeeded(s, b.exchange(), b.network());
            applyRuntimePolicy(chatId, type, b.exchange(), b.network(), s);
        } catch (Exception ignored) {
        }
    }

    /**
     * Обновить runtime фазу/режим без рестарта.
     */
    public void refreshRuntimePhase(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (type == null) return;

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);
        if (b == null) return;

        String ex = sanitizeExchange(exchange);
        NetworkType net = network;

        if (ex == null) ex = b.exchange();
        if (net == null) net = b.network();
        if (ex == null || net == null) return;

        try {
            StrategySettings s = loadSettingsStrict(chatId, type, ex, net);
            if (s == null) return;
            applyRuntimePolicy(chatId, type, ex, net, s);
        } catch (Exception e) {
            log.debug("⚠ [ORCH] refreshRuntimePhase failed: {}", e.getMessage());
        }
    }


    private record LifecycleCloseResult(
            boolean ok,
            boolean attempted,
            String reason
    ) {}

    private LifecycleCloseResult closeOpenPositionBeforeLifecycleTransition(Long chatId,
                                                                            StrategyType type,
                                                                            RunBinding binding,
                                                                            StrategySettings settings,
                                                                            String lifecycleReason) {

        if (chatId == null || type == null || binding == null) {
            return new LifecycleCloseResult(true, false, "no_binding");
        }

        String ex = sanitizeExchange(binding.exchange());
        NetworkType net = binding.network();
        String sym = sanitizeSymbol(binding.symbol());

        if (ex == null || net == null || sym == null) {
            return new LifecycleCloseResult(true, false, "binding_incomplete");
        }

        String phase = sanitizePhase(settings != null ? settings.getRunPhase() : null);
        if (phaseSkipsProtectiveExit(phase)) {
            return new LifecycleCloseResult(true, false, "phase=" + phase);
        }

        Optional<PositionStore.PositionSnapshot> posOpt;
        try {
            posOpt = positionStore.getPosition(chatId, type, ex, net, sym);
        } catch (Exception e) {
            log.error("🛑 [ORCH] {} aborted: не удалось прочитать позицию | chatId={} type={} ex={} net={} sym={} err={}",
                    lifecycleReason, chatId, type, ex, net, sym, e.toString());
            return new LifecycleCloseResult(false, false, "position_lookup_failed");
        }

        if (posOpt == null || posOpt.isEmpty()) {
            return new LifecycleCloseResult(true, false, "no_position");
        }

        PositionStore.PositionSnapshot snap = posOpt.get();

        BigDecimal qty = positiveOrNull(snap.qty());
        BigDecimal entryPrice = positiveOrNull(snap.entryPrice());
        BigDecimal tp = positiveOrNull(snap.tp());
        BigDecimal sl = positiveOrNull(snap.sl());

        if (qty == null) {
            log.error("🛑 [ORCH] {} aborted: позиция найдена, но qty отсутствует | chatId={} type={} ex={} net={} sym={}",
                    lifecycleReason, chatId, type, ex, net, sym);
            return new LifecycleCloseResult(false, true, "position_qty_missing");
        }

        BigDecimal priceHint = positiveOrNull(sl);
        if (priceHint == null) priceHint = positiveOrNull(entryPrice);
        if (priceHint == null) priceHint = BigDecimal.ONE;

        if (sl == null) {
            sl = priceHint;
        }
        if (tp == null) {
            BigDecimal base = positiveOrNull(entryPrice);
            if (base == null) base = priceHint;
            tp = base.multiply(new BigDecimal("2")).setScale(8, RoundingMode.HALF_UP);
        }

        Instant now = Instant.now();

        log.warn("🛡️ [ORCH] Protective exit before {} | chatId={} type={} ex={} net={} sym={} qty={} entry={} tp={} sl={} priceHint={}",
                lifecycleReason,
                chatId,
                type,
                ex,
                net,
                sym,
                qty.stripTrailingZeros().toPlainString(),
                entryPrice != null ? entryPrice.stripTrailingZeros().toPlainString() : "null",
                tp != null ? tp.stripTrailingZeros().toPlainString() : "null",
                sl != null ? sl.stripTrailingZeros().toPlainString() : "null",
                priceHint.stripTrailingZeros().toPlainString()
        );

        ExitResult exitResult;
        try {
            exitResult = tradeExecutionService.executeExitIfHit(
                    chatId,
                    type,
                    sym,
                    priceHint,
                    now,
                    true,
                    qty,
                    tp,
                    sl,
                    ex,
                    net
            );
        } catch (Exception e) {
            log.error("🛑 [ORCH] {} aborted: protective SELL threw exception | chatId={} type={} ex={} net={} sym={} err={}",
                    lifecycleReason, chatId, type, ex, net, sym, e.toString(), e);
            return new LifecycleCloseResult(false, true, "protective_exit_exception");
        }

        if (exitResult != null && exitResult.executed()) {
            log.info("🛡️ [ORCH] Protective exit done before {} | chatId={} type={} ex={} net={} sym={} exitPrice={} pnlPct={}",
                    lifecycleReason,
                    chatId,
                    type,
                    ex,
                    net,
                    sym,
                    exitResult.exitPrice() != null ? exitResult.exitPrice().stripTrailingZeros().toPlainString() : "null",
                    exitResult.pnlPct() != null ? exitResult.pnlPct().stripTrailingZeros().toPlainString() : "null"
            );
            return new LifecycleCloseResult(true, true, "executed");
        }

        String reason = (exitResult != null && exitResult.reason() != null && !exitResult.reason().isBlank())
                ? exitResult.reason()
                : "protective_exit_failed";

        if ("no_real_position".equalsIgnoreCase(reason)) {
            log.warn("🛡️ [ORCH] Protective exit skipped before {}: биржа не подтвердила открытую позицию | chatId={} type={} ex={} net={} sym={}",
                    lifecycleReason, chatId, type, ex, net, sym);
            return new LifecycleCloseResult(true, true, reason);
        }

        log.error("🛑 [ORCH] {} aborted: open position was not closed | chatId={} type={} ex={} net={} sym={} reason={}",
                lifecycleReason, chatId, type, ex, net, sym, reason);
        return new LifecycleCloseResult(false, true, reason);
    }

    private static boolean phaseSkipsProtectiveExit(String phase) {
        return "COLLECT".equalsIgnoreCase(phase) || "BACKTEST".equalsIgnoreCase(phase);
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return (value != null && value.signum() > 0) ? value : null;
    }

    // =====================================================================
    // ATOMIC RESTART (главный метод)
    // =====================================================================

    public StrategyRunInfo restartStrategyAtomic(Long chatId,
                                                 StrategyType type,
                                                 String exchange,
                                                 NetworkType network,
                                                 String reason) {

        if (chatId == null || chatId <= 0 || type == null) {
            return StrategyRunInfo.builder()
                    .chatId(chatId)
                    .type(type)
                    .active(false)
                    .exchangeName(sanitizeExchange(exchange))
                    .networkType(network)
                    .message("Ошибка: chatId/type пустые")
                    .updatedAt(Instant.now())
                    .build();
        }

        RunKey key = new RunKey(chatId, type);
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            RunBinding current = running.get(key);
            if (current == null) {
                return getStatus(chatId, type, exchange, network);
            }

            StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);
            if (s == null) {
                return StrategyRunInfo.builder()
                        .chatId(chatId)
                        .type(type)
                        .active(true)
                        .exchangeName(current.exchange())
                        .networkType(current.network())
                        .symbol(current.symbol())
                        .timeframe(current.timeframe())
                        .message("Ошибка: StrategySettings не удалось загрузить (рестарт пропущен)")
                        .updatedAt(Instant.now())
                        .build();
            }

            String newSym = sanitizeSymbol(s.getSymbol());
            String newTf = sanitizeTf(s.getTimeframe());

            String newEx = sanitizeExchange(exchange);
            if (newEx == null) newEx = sanitizeExchange(s.getExchangeName());
            if (newEx == null) newEx = "BINANCE";

            NetworkType newNet = (network != null ? network : s.getNetworkType());
            if (newNet == null) newNet = NetworkType.TESTNET;

            if (newSym == null) {
                return buildRunInfoFromBinding(s, current, true, "Рестарт невозможен: не выбран symbol");
            }

            RunBinding desired = new RunBinding(newEx, newNet, newSym, newTf, Instant.now());

            boolean changed =
                    !eq(current.exchange(), desired.exchange())
                    || current.network() != desired.network()
                    || !eq(current.symbol(), desired.symbol())
                    || !eq(current.timeframe(), desired.timeframe());

            if (!changed) {
                applyRuntimePolicy(chatId, type, current.exchange(), current.network(), s);
                return buildRunInfoFromBinding(s, current, true, "Контекст не изменился (рестарт не нужен)");
            }

            log.warn("🔄 [ORCH] ATOMIC_RESTART chatId={} type={} reason={} old=[ex={} net={} {} {}] new=[ex={} net={} {} {}]",
                    chatId, type, (reason == null ? "n/a" : reason),
                    current.exchange(), current.network(), current.symbol(), current.timeframe(),
                    desired.exchange(), desired.network(), desired.symbol(), desired.timeframe()
            );

            TradingStrategy strategy = strategyRegistry.get(type);
            if (strategy == null) {
                return buildRunInfoFromBinding(s, current, true, "Стратегия не найдена (рестарт пропущен)");
            }

            String prepareError = runPrepareBeforeStart(
                    chatId,
                    type,
                    desired.exchange(),
                    desired.network(),
                    desired.symbol(),
                    desired.timeframe(),
                    s
            );
            if (prepareError != null) {
                boolean softPrepareError = isSoftPrepareError(prepareError);
                if (softPrepareError) {
                    clearStaleMlContextOnSoftPrepareFailure(
                            s,
                            type,
                            desired.exchange(),
                            desired.network(),
                            desired.symbol(),
                            desired.timeframe()
                    );
                }

                if (softPrepareError && allowStartWithoutPreparedModel(s, type)) {
                    log.warn("⚠️ [ORCH] SOFT_PREPARE_MISS chatId={} type={} ex={} net={} sym={} tf={} reason={} | продолжу переключение контекста без подготовленной модели",
                            chatId, type, desired.exchange(), desired.network(), desired.symbol(), desired.timeframe(), prepareError);
                } else {
                    return buildRunInfoFromBinding(
                            s,
                            current,
                            true,
                            "Рестарт отменён: подготовка нового контекста не пройдена (" + prepareError + ")"
                    );
                }
            }

            StrategySettings preparedSettings = loadSettingsStrict(chatId, type, desired.exchange(), desired.network());
            if (preparedSettings != null) {
                s = preparedSettings;
            }

            LifecycleCloseResult closeResult = closeOpenPositionBeforeLifecycleTransition(
                    chatId,
                    type,
                    current,
                    s,
                    "RESTART"
            );
            if (!closeResult.ok()) {
                return buildRunInfoFromBinding(
                        s,
                        current,
                        true,
                        "Рестарт отменён: не удалось безопасно закрыть позицию (" + closeResult.reason() + ")"
                );
            }

            try {
                strategy.stop(chatId, current.symbol(), current.exchange(), current.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] stop(old) failed chatId={} type={} : {}", chatId, type, e.getMessage());
            }
            safeAutotuneStop(chatId, type, current.exchange(), current.network());

            running.put(key, desired);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            lastTrainTriggerAtMs.remove(key);
            lastDegradedLogAtMs.remove(key);

            try {
                marketStreamService.ensureSubscribed(
                        chatId,
                        type,
                        desired.symbol(),
                        desired.timeframe(),
                        desired.exchange(),
                        desired.network()
                );

                strategy.start(chatId, desired.symbol(), desired.exchange(), desired.network());
            } catch (Exception e) {
                running.put(key, current);
                runtimePolicyCache.put(key, policyOf(type, s));

                try {
                    marketStreamService.ensureSubscribed(
                            chatId,
                            type,
                            current.symbol(),
                            current.timeframe(),
                            current.exchange(),
                            current.network()
                    );
                } catch (Exception ignored) {
                }

                log.error("❌ [ORCH] start(new) failed chatId={} type={} ex={} net={} sym={} tf={}",
                        chatId, type, desired.exchange(), desired.network(), desired.symbol(), desired.timeframe(), e);
                return buildRunInfoFromBinding(s, current, true, "Ошибка рестарта: не удалось запустить новый контекст");
            }

            syncSettingsContextIfNeeded(s, desired.exchange(), desired.network());
            if (!s.isActive()) {
                s.setActive(true);
                s.setStartedAt(LocalDateTime.now());
                s.setStoppedAt(null);
                s = saveSettingsWithRetry(s, "restart_active_state");
            }

            applyRuntimePolicy(chatId, type, desired.exchange(), desired.network(), s);

            log.info("▶️ [ORCH] RUN {} chatId={} ex={} net={} symbol={} tf={} mode={}",
                    type, chatId, desired.exchange(), desired.network(), desired.symbol(), desired.timeframe(), safeMode(s));

            return buildRunInfoFromBinding(s, desired, true, "Контекст изменён — стратегия перезапущена");

        } finally {
            lock.unlock();
        }
    }

    

    private boolean isSoftPrepareError(String prepareError) {
        if (prepareError == null || prepareError.isBlank()) {
            return false;
        }

        String normalized = prepareError.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("no_selected_candles")
                || normalized.contains("no_samples")
                || normalized.contains("no_context_samples")
                || normalized.contains("not_enough_candle_rows")
                || normalized.contains("not_enough_samples")
                || normalized.contains("too_few_trades")
                || normalized.contains("not_enough_trades")
                || normalized.contains("no_improvement")
                || normalized.contains("prepare_tune_failed")
                || normalized.contains("cooldown")
                || normalized.contains("tune_skip")
                || normalized.contains("validate_skip");
    }

    private boolean allowStartWithoutPreparedModel(StrategySettings settings, StrategyType type) {
        AdvancedControlMode mode = safeMode(settings);

        if (mode == AdvancedControlMode.HYBRID) {
            return true;
        }

        return type == StrategyType.WINDOW_SCALPING
               || type == StrategyType.FIBONACCI_GRID
               || type == StrategyType.EMA_CROSSOVER;
    }
    private StrategySettings markStartFailedInactive(StrategySettings settings, String source) {
        if (settings == null) {
            return null;
        }

        boolean changed = false;

        if (settings.isActive()) {
            settings.setActive(false);
            changed = true;
        }

        LocalDateTime now = LocalDateTime.now();
        if (settings.getStoppedAt() == null
                || (settings.getStartedAt() != null && settings.getStoppedAt().isBefore(settings.getStartedAt()))) {
            settings.setStoppedAt(now);
            changed = true;
        }

        if (!changed) {
            return settings;
        }

        try {
            return saveSettingsWithRetry(settings, source);
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] {} mark inactive failed chatId={} type={} err={}",
                    source,
                    settings.getChatId(),
                    settings.getType(),
                    e.toString());
            return settings;
        }
    }

    private void clearStaleMlContextOnSoftPrepareFailure(StrategySettings settings,
                                                         StrategyType type,
                                                         String exchange,
                                                         NetworkType network,
                                                         String symbol,
                                                         String timeframe) {
        if (settings == null || type == null) {
            return;
        }

        Long chatId = settings.getChatId();
        if (chatId == null || chatId <= 0) {
            return;
        }

        try {
            StrategySettings target = settings;
            try {
                StrategySettings fresh = settingsService.getOrCreate(chatId, type);
                if (fresh != null) {
                    target = fresh;
                }
            } catch (Exception ignored) {
            }

            String contextualModelKey = MlGateway.buildContextModelKey(type, exchange, network != null ? network.name() : null, symbol, timeframe);
            String livePhase = resolvePhaseAfterPrepare(network != null ? network : target.getNetworkType());

            boolean changed = false;

            if (!Objects.equals(blankToNull(target.getMlModelKey()), blankToNull(contextualModelKey))) {
                target.setMlModelKey(contextualModelKey);
                changed = true;
            }
            if (blankToNull(target.getMlModelVersion()) != null) {
                target.setMlModelVersion(null);
                changed = true;
            }
            if (blankToNull(target.getMlSchemaHash()) != null) {
                target.setMlSchemaHash(null);
                changed = true;
            }
            if (requiresContextBoundPreparedModel(type) && target.isMlGateEnabled()) {
                target.setMlGateEnabled(false);
                changed = true;
            }
            if (requiresContextBoundPreparedModel(type) && target.getGateMinProb() != null) {
                target.setGateMinProb(null);
                changed = true;
            }
            if (!livePhase.equalsIgnoreCase(sanitizePhase(target.getRunPhase()))) {
                target.setRunPhase(livePhase);
                changed = true;
            }

            if (changed) {
                settingsService.save(target);
            }

            if (target != settings) {
                settings.setMlModelKey(target.getMlModelKey());
                settings.setMlModelVersion(target.getMlModelVersion());
                settings.setMlSchemaHash(target.getMlSchemaHash());
                settings.setMlGateEnabled(target.isMlGateEnabled());
                settings.setGateMinProb(target.getGateMinProb());
                settings.setRunPhase(target.getRunPhase());
            }
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] clearStaleMlContextOnSoftPrepareFailure failed chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, exchange, network, symbol, timeframe, e.toString());
        }
    }

    private boolean requiresPrepareBeforeStart(StrategyType type, StrategySettings settings) {
        if (type == null) return false;

        if (type != StrategyType.WINDOW_SCALPING
            && type != StrategyType.EMA_CROSSOVER
            && type != StrategyType.FIBONACCI_GRID
            && type != StrategyType.SCALPING) {
            return false;
        }

        AdvancedControlMode mode = safeMode(settings);
        return mode == AdvancedControlMode.AI || mode == AdvancedControlMode.HYBRID;
    }
    private String resolvePhaseAfterPrepare(NetworkType network) {
        return network == NetworkType.TESTNET ? "PAPER" : "LIVE";
    }

    private String runPrepareBeforeStart(Long chatId,
                                         StrategyType type,
                                         String exchange,
                                         NetworkType network,
                                         String symbol,
                                         String timeframe,
                                         StrategySettings settings) {
        if (!requiresPrepareBeforeStart(type, settings)) {
            return null;
        }

        Integer candlesLimit = settings != null ? settings.getCachedCandlesLimit() : null;
        if (candlesLimit == null || candlesLimit <= 0) {
            candlesLimit = 1000;
        }

        try {
            settings.setRunPhase("PREPARE");
            settingsService.save(settings);
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] prepare phase save failed chatId={} type={} err={}", chatId, type, e.toString());
        }

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy instanceof PrepareStartAware aware) {
            PreparationResult preparationResult;
            try {
                preparationResult = aware.prepareStart(chatId, type, symbol, timeframe, exchange, network);
            } catch (Exception e) {
                log.error("❌ [ORCH] prepareStart exception chatId={} type={} ex={} net={} sym={} tf={}",
                        chatId, type, exchange, network, symbol, timeframe, e);
                return "prepare_failed:train_exception";
            }

            if (preparationResult == null || !preparationResult.ok()) {
                String reason = preparationResult != null ? blankToNull(preparationResult.message()) : null;
                if (reason == null) {
                    reason = "train_failed";
                }
                log.warn("🧠 [ORCH] PREPARE TRAIN SKIP/BLOCK chatId={} type={} ex={} net={} sym={} tf={} reason={}",
                        chatId, type, exchange, network, symbol, timeframe, reason);
                return "prepare_failed:" + reason;
            }

            try {
                StrategySettings reloaded = loadSettingsStrict(chatId, type, exchange, network);
                if (reloaded != null) {
                    reloaded.setRunPhase(resolvePhaseAfterPrepare(network));
                    settingsService.save(reloaded);
                } else {
                    settings.setRunPhase(resolvePhaseAfterPrepare(network));
                    settingsService.save(settings);
                }
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] prepare final phase save failed chatId={} type={} err={}", chatId, type, e.toString());
            }

            return null;
        }

        MlTrainingServiceImpl trainer = trainer();
        if (trainer == null) {
            return "prepare_failed:trainer_missing";
        }

        MlTrainingResult trainRes;
        try {
            trainRes = trainer.trainOnSelectedCandles(
                    chatId,
                    type,
                    exchange,
                    network,
                    symbol,
                    timeframe,
                    candlesLimit,
                    "prepare_start_train"
            );
        } catch (Exception e) {
            log.error("❌ [ORCH] prepare train exception chatId={} type={} ex={} net={} sym={} tf={}",
                    chatId, type, exchange, network, symbol, timeframe, e);
            return "prepare_failed:train_exception";
        }

        if (trainRes == null || !trainRes.ok() || !trainRes.applied()) {
            String reason = (trainRes != null && trainRes.error() != null && !trainRes.error().isBlank())
                    ? trainRes.error()
                    : "train_failed";
            log.warn("🧠 [ORCH] PREPARE TRAIN SKIP/BLOCK chatId={} type={} ex={} net={} sym={} tf={} reason={}",
                    chatId, type, exchange, network, symbol, timeframe, reason);
            return "prepare_failed:" + reason;
        }

        AutoTunerOrchestrator tuner = autoTuner();
        if (tuner != null) {
            try {
                TuningRequest req = TuningRequest.builder()
                        .chatId(chatId)
                        .strategyType(type)
                        .exchange(exchange)
                        .network(network)
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .candlesLimit(candlesLimit)
                        .reason("prepare_start_validate")
                        .build();

                TuningResult tuneRes = tuner.tune(req);
                if (tuneRes != null) {
                    log.info("🧠 [ORCH] PREPARE TUNE result chatId={} type={} ex={} net={} sym={} tf={} applied={} reason={}",
                            chatId, type, exchange, network, symbol, timeframe, tuneRes.applied(), tuneRes.reason());
                }
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] prepare tune failed chatId={} type={} ex={} net={} sym={} tf={} err={}",
                        chatId, type, exchange, network, symbol, timeframe, e.toString());
            }
        }

        try {
            StrategySettings reloaded = loadSettingsStrict(chatId, type, exchange, network);
            if (reloaded != null) {
                reloaded.setRunPhase(resolvePhaseAfterPrepare(network));
                settingsService.save(reloaded);
            } else {
                settings.setRunPhase(resolvePhaseAfterPrepare(network));
                settingsService.save(settings);
            }
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] prepare final phase save failed chatId={} type={} err={}", chatId, type, e.toString());
        }

        return null;
    }

// =====================================================================
    // START
    // =====================================================================
    public StrategyRunInfo startStrategy(Long chatId, StrategyType type, String exchange, NetworkType network) {

        if (chatId == null || chatId <= 0 || type == null) {
            return StrategyRunInfo.builder()
                    .chatId(chatId)
                    .type(type)
                    .active(false)
                    .exchangeName(sanitizeExchange(exchange))
                    .networkType(network)
                    .message("Ошибка: chatId/type пустые")
                    .updatedAt(Instant.now())
                    .build();
        }

        RunKey key = new RunKey(chatId, type);
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);
            if (s == null) {
                return StrategyRunInfo.builder()
                        .chatId(chatId)
                        .type(type)
                        .active(false)
                        .exchangeName(sanitizeExchange(exchange))
                        .networkType(network)
                        .message("Ошибка: StrategySettings не удалось загрузить")
                        .updatedAt(Instant.now())
                        .build();
            }

            String sym = sanitizeSymbol(s.getSymbol());
            String tf = sanitizeTf(s.getTimeframe());

            String ex = sanitizeExchange(exchange);
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = (network != null ? network : s.getNetworkType());
            if (net == null) net = NetworkType.TESTNET;

            if (sym == null) return buildRunInfo(s, false, "Ошибка: не выбран символ");

            String prepareError = runPrepareBeforeStart(chatId, type, ex, net, sym, tf, s);
            if (prepareError != null) {
                boolean softPrepareError = isSoftPrepareError(prepareError);
                if (softPrepareError) {
                    clearStaleMlContextOnSoftPrepareFailure(s, type, ex, net, sym, tf);
                }

                if (softPrepareError && allowStartWithoutPreparedModel(s, type)) {
                    log.warn("⚠️ [ORCH] SOFT_PREPARE_MISS chatId={} type={} ex={} net={} sym={} tf={} reason={} | запускаю стратегию без подготовленной модели",
                            chatId, type, ex, net, sym, tf, prepareError);
                } else {
                    s = markStartFailedInactive(s, "start_prepare_failed");
                    return buildRunInfo(s, false, "Подготовка перед стартом не пройдена (" + prepareError + ")");
                }
            }

            StrategySettings preparedSettings = loadSettingsStrict(chatId, type, ex, net);
            if (preparedSettings != null) {
                s = preparedSettings;
                sym = sanitizeSymbol(s.getSymbol());
                tf = sanitizeTf(s.getTimeframe());
                if (sym == null) return buildRunInfo(s, false, "Ошибка: не выбран символ");
            }

            TradingStrategy strategy = strategyRegistry.get(type);
            if (strategy == null) return buildRunInfo(s, false, "Стратегия не найдена");

            RunBinding newBinding = new RunBinding(ex, net, sym, tf, Instant.now());
            RunBinding existing = running.get(key);

            if (existing != null
                && eq(existing.exchange(), newBinding.exchange())
                && existing.network() == newBinding.network()
                && eq(existing.symbol(), newBinding.symbol())
                && eq(existing.timeframe(), newBinding.timeframe())) {

                log.info("⏭ [ORCH] Уже запущено: {} chatId={} ex={} net={} {} {} mode={}",
                        type, chatId, ex, net, sym, tf, safeMode(s));

                syncSettingsContextIfNeeded(s, ex, net);
                if (!s.isActive()) {
                    s.setActive(true);
                    s.setStartedAt(LocalDateTime.now());
                    s.setStoppedAt(null);
                    s = saveSettingsWithRetry(s, "start_already_running_active_state");
                }

                applyRuntimePolicy(chatId, type, ex, net, s);

                marketStreamService.ensureSubscribed(chatId, type, sym, tf, ex, net);
                return buildRunInfo(s, true, "Стратегия уже запущена");
            }

            if (existing != null) {
                log.warn("⚠️ [ORCH] Перезапуск binding: {} chatId={} было ex={} net={} {} {} -> стало ex={} net={} {} {}",
                        type, chatId,
                        existing.exchange(), existing.network(), existing.symbol(), existing.timeframe(),
                        ex, net, sym, tf);

                LifecycleCloseResult closeResult = closeOpenPositionBeforeLifecycleTransition(
                        chatId,
                        type,
                        existing,
                        s,
                        "START_CONTEXT_SWITCH"
                );
                if (!closeResult.ok()) {
                    return buildRunInfoFromBinding(
                            s,
                            existing,
                            true,
                            "Перезапуск отменён: не удалось безопасно закрыть позицию (" + closeResult.reason() + ")"
                    );
                }

                try {
                    strategy.stop(chatId, existing.symbol(), existing.exchange(), existing.network());
                } catch (Exception e) {
                    log.warn("⚠️ [ORCH] Не удалось корректно остановить старый runtime: {}", e.getMessage());
                }

                safeAutotuneStop(chatId, type, existing.exchange(), existing.network());
            }

            running.put(key, newBinding);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            lastTrainTriggerAtMs.remove(key);
            lastDegradedLogAtMs.remove(key);

            try {
                marketStreamService.ensureSubscribed(chatId, type, sym, tf, ex, net);

                strategy.start(chatId, sym, ex, net);
            } catch (Exception e) {
                running.remove(key, newBinding);
                runtimePolicyCache.remove(key);

                try {
                    marketStreamService.unsubscribe(chatId, type);
                } catch (Exception ignored) {
                }
                log.error("❌ [ORCH] startStrategy failed type={} chatId={} ex={} net={} sym={} tf={}",
                        type, chatId, ex, net, sym, tf, e);
                s = markStartFailedInactive(s, "start_strategy_failed");
                return buildRunInfo(s, false, "Ошибка запуска стратегии");
            }

            StrategySettings freshSettings = loadSettingsStrict(chatId, type, ex, net);
            if (freshSettings != null) {
                s = freshSettings;
                sym = sanitizeSymbol(s.getSymbol());
                tf = sanitizeTf(s.getTimeframe());
                if (sym == null) return buildRunInfo(s, false, "Ошибка: не выбран символ");
            }

            syncSettingsContextIfNeeded(s, ex, net);

            s.setActive(true);
            s.setStartedAt(LocalDateTime.now());
            s.setStoppedAt(null);
            s = saveSettingsWithRetry(s, "start_active_state");

            log.info("▶️ [ORCH] START {} chatId={} ex={} net={} symbol={} tf={} mode={}",
                    type, chatId, ex, net, sym, tf, safeMode(s));

            applyRuntimePolicy(chatId, type, ex, net, s);
            return buildRunInfo(s, true, "Стратегия запущена");

        } finally {
            lock.unlock();
        }
    }

    // =====================================================================
    // STOP
    // =====================================================================
    public StrategyRunInfo stopStrategy(Long chatId, StrategyType type, String exchange, NetworkType network) {

        if (chatId == null || chatId <= 0 || type == null) {
            return StrategyRunInfo.builder()
                    .chatId(chatId)
                    .type(type)
                    .active(false)
                    .exchangeName(sanitizeExchange(exchange))
                    .networkType(network)
                    .message("Ошибка: chatId/type пустые")
                    .updatedAt(Instant.now())
                    .build();
        }

        RunKey key = new RunKey(chatId, type);
        ReentrantLock lock = lockFor(key);
        lock.lock();
        try {
            StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);
            if (s == null) {
                return StrategyRunInfo.builder()
                        .chatId(chatId)
                        .type(type)
                        .active(false)
                        .exchangeName(sanitizeExchange(exchange))
                        .networkType(network)
                        .message("Ошибка: StrategySettings не удалось загрузить")
                        .updatedAt(Instant.now())
                        .build();
            }

            String sym = sanitizeSymbol(s.getSymbol());
            RunBinding current = running.get(key);

            LifecycleCloseResult closeResult = closeOpenPositionBeforeLifecycleTransition(
                    chatId,
                    type,
                    current,
                    s,
                    "STOP"
            );
            if (!closeResult.ok()) {
                return current != null
                        ? buildRunInfoFromBinding(
                                s,
                                current,
                                true,
                                "Остановка отменена: не удалось безопасно закрыть позицию (" + closeResult.reason() + ")"
                          )
                        : buildRunInfo(s, true, "Остановка отменена: не удалось безопасно закрыть позицию (" + closeResult.reason() + ")");
            }

            try {
                marketStreamService.unsubscribe(chatId, type);
            } catch (Exception ignored) {
            }

            RunBinding removed = running.remove(key);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            lastTrainTriggerAtMs.remove(key);
            runtimePolicyCache.remove(key);
            lastDegradedLogAtMs.remove(key);

            String ex = removed != null ? removed.exchange() : sanitizeExchange(exchange);
            if (ex == null && current != null) ex = current.exchange();
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = removed != null ? removed.network() : (current != null ? current.network() : (network != null ? network : s.getNetworkType()));
            if (net == null) net = NetworkType.TESTNET;

            TradingStrategy strategy = strategyRegistry.get(type);

            if (strategy != null) {
                try {
                    if (removed != null) {
                        strategy.stop(chatId, removed.symbol(), removed.exchange(), removed.network());
                    } else if (current != null) {
                        strategy.stop(chatId, current.symbol(), current.exchange(), current.network());
                    } else {
                        strategy.stop(chatId, sym, ex, net);
                    }
                } catch (Exception e) {
                    log.error("❌ [ORCH] stopStrategy failed type={} chatId={} ex={} net={} sym={}",
                            type, chatId, ex, net, sym, e);
                }
            }

            StrategySettings freshSettings = loadSettingsStrict(chatId, type, ex, net);
            if (freshSettings != null) {
                s = freshSettings;
            }

            syncSettingsContextIfNeeded(s, ex, net);

            s.setActive(false);
            s.setStoppedAt(LocalDateTime.now());
            s = saveSettingsWithRetry(s, "stop_inactive_state");

            safeAutotuneStop(chatId, type, ex, net);

            log.info("⏹ [ORCH] STOP {} chatId={} | bindingRemoved={} protectiveExitAttempted={}",
                    type,
                    chatId,
                    removed != null,
                    closeResult.attempted());
            return buildRunInfo(s, false, "Стратегия остановлена");

        } finally {
            lock.unlock();
        }
    }

    /** Совместимость со старым вызовом из UI. */
    public StrategyRunInfo onSettingsChanged(Long chatId,
                                             StrategyType type,
                                             String exchange,
                                             NetworkType network) {
        return restartStrategyAtomic(chatId, type, exchange, network, "onSettingsChanged");
    }

    // =====================================================================
    // STATUS (read-only, без записи в БД)
    // =====================================================================
    public StrategyRunInfo getStatus(Long chatId, StrategyType type, String exchange, NetworkType network) {

        if (chatId == null || chatId <= 0 || type == null) {
            return StrategyRunInfo.builder()
                    .chatId(chatId)
                    .type(type)
                    .active(false)
                    .exchangeName(sanitizeExchange(exchange))
                    .networkType(network)
                    .message("Ошибка: chatId/type пустые")
                    .updatedAt(Instant.now())
                    .build();
        }

        StrategySettings s = loadSettingsReadOnly(chatId, type);
        if (s == null) {
            return StrategyRunInfo.builder()
                    .chatId(chatId)
                    .type(type)
                    .active(false)
                    .exchangeName(sanitizeExchange(exchange))
                    .networkType(network)
                    .message("StrategySettings отсутствуют")
                    .updatedAt(Instant.now())
                    .build();
        }

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);

        if (b == null) {
            String ex = sanitizeExchange(exchange);
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = (network != null ? network : s.getNetworkType());
            if (net == null) net = NetworkType.TESTNET;

            return StrategyRunInfo.builder()
                    .chatId(s.getChatId())
                    .type(s.getType())
                    .symbol(sanitizeSymbol(s.getSymbol()))
                    .active(false)
                    .timeframe(sanitizeTf(s.getTimeframe()))
                    .exchangeName(ex)
                    .networkType(net)
                    .version(s.getVersion())
                    .startedAt(toInstant(s.getStartedAt()))
                    .stoppedAt(toInstant(s.getStoppedAt()))
                    .updatedAt(Instant.now())
                    .message("Стратегия остановлена")
                    .build();
        }

        String reqEx = sanitizeExchange(exchange);
        NetworkType reqNet = network;

        boolean ctxMatch = true;
        if (reqEx != null && !eq(reqEx, b.exchange())) ctxMatch = false;
        if (reqNet != null && reqNet != b.network()) ctxMatch = false;

        String msg = ctxMatch
                ? "Стратегия запущена"
                : "Стратегия запущена в другом контексте: ex=" + b.exchange() + " net=" + b.network();

        return buildRunInfoFromBinding(s, b, true, msg);
    }

    // =====================================================================
    // ML AUTO-TUNE HOOKS
    // =====================================================================

    private StrategySettings saveSettingsWithRetry(StrategySettings s, String source) {
        if (s == null || s.getChatId() == null || s.getType() == null) return s;

        try {
            StrategySettings saved = strategySettingsCommandService.savePatchWithRetry(s.getChatId(), s.getType(), s);
            return saved != null ? saved : s;
        } catch (Exception patchEx) {
            log.warn("⚠️ [ORCH] {} savePatchWithRetry failed chatId={} type={} err={}",
                    source, s.getChatId(), s.getType(), patchEx.toString());
            StrategySettings saved = settingsService.save(s);
            return saved != null ? saved : s;
        }
    }

    private void safeAutotuneStart(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            MlAutoTuneRuntime rt = ml();
            if (rt == null) return;
            if (chatId == null || type == null || exchange == null || network == null) return;
            rt.onStrategyStarted(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] autotune start failed: {}", e.getMessage());
        }
    }

    private void safeAutotuneStop(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            MlAutoTuneRuntime rt = ml();
            if (rt == null) return;
            if (chatId == null || type == null || exchange == null || network == null) return;
            rt.onStrategyStopped(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] autotune stop failed: {}", e.getMessage());
        }
    }

    public void onPositionClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            MlAutoTuneRuntime rt = ml();
            if (rt == null) return;
            if (chatId == null || type == null || exchange == null || network == null) return;
            rt.onPositionClosed(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] onPositionClosed hook failed: {}", e.getMessage());
        }
    }

    public void triggerTuneDebounced(Long chatId,
                                     StrategyType type,
                                     String exchange,
                                     NetworkType network,
                                     String reason,
                                     Duration debounce) {

        MlAutoTuneRuntime rt = ml();
        if (rt == null) return;

        if (chatId == null || type == null || exchange == null || network == null) return;

        RunKey key = new RunKey(chatId, type);
        long now = System.currentTimeMillis();
        long d = Math.max(30_000, debounce != null ? debounce.toMillis() : 120_000);

        Long last = lastTuneTriggerAtMs.get(key);
        if (last != null && (now - last) < d) return;

        lastTuneTriggerAtMs.put(key, now);

        try {
            rt.triggerTuneDebounced(chatId, type, exchange, network,
                    reason != null ? reason : "orch-trigger", Duration.ofMillis(d));
        } catch (Exception e) {
            log.warn("🧠 [ORCH] triggerTuneDebounced failed: {}", e.getMessage());
        }
    }

    public void triggerTrainDebounced(Long chatId,
                                      StrategyType type,
                                      String exchange,
                                      NetworkType network,
                                      String reason,
                                      Duration debounce) {

        MlAutoTuneRuntime rt = ml();
        if (rt == null) return;

        if (chatId == null || type == null || exchange == null || network == null) return;

        RunKey key = new RunKey(chatId, type);
        long now = System.currentTimeMillis();
        long d = Math.max(10_000, debounce != null ? debounce.toMillis() : 60_000);

        Long last = lastTrainTriggerAtMs.get(key);
        if (last != null && (now - last) < d) return;

        lastTrainTriggerAtMs.put(key, now);

        try {
            rt.triggerTrainDebounced(chatId, type, exchange, network,
                    reason != null ? reason : "orch-train-trigger", Duration.ofMillis(d));
        } catch (Exception e) {
            log.warn("🧠 [ORCH] triggerTrainDebounced failed: {}", e.getMessage());
        }
    }

    // =====================================================================
    // MARKET STREAM входы (строгий контекст)
    // =====================================================================

    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs) {

        if (type == null || price == null || price.signum() <= 0) return;

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);
        if (b == null) return;

        ensurePolicyLoadedIfMissing(chatId, type, b);

        String ex = sanitizeExchange(exchange);
        if (ex == null) ex = b.exchange();
        NetworkType net = (network != null ? network : b.network());

        String sym = sanitizeSymbol(symbol);
        if (sym == null) sym = b.symbol();
        String tf = sanitizeTf(timeframe);
        if (tf == null) tf = b.timeframe();

        if (!eq(ex, b.exchange()) || net != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "TICK_IGNORED",
                    "пришло ex=" + ex + " net=" + net + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        if (isMarketEventsBlocked(key)) return;
        if (isDegradedBlocked(key, b, sym, tf)) return;

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return;

        long ts = (tradeTsMs > 0 ? tradeTsMs : System.currentTimeMillis());

        if (strategy instanceof PriceUpdateAware aware) {
            try {
                aware.onPriceUpdate(chatId, type, sym, tf, price, ts, b.exchange(), b.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] TICK_HANDLER_FAILED chatId={} type={} | {}", chatId, type, e.getMessage());
            }
            return;
        }

        try {
            strategy.onPriceUpdate(chatId, sym, price, Instant.ofEpochMilli(ts));
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] TICK_HANDLER_FAILED_LEGACY chatId={} type={} | {}", chatId, type, e.getMessage());
        }
    }

    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs) {

        if (type == null || price == null || price.signum() <= 0) return;

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);
        if (b == null) return;

        String sym = sanitizeSymbol(symbol);
        String tf = sanitizeTf(timeframe);

        if (sym != null && !eq(sym, b.symbol())) {
            logIgnore(key, "TICK_IGNORED_NOCTX",
                    "пришло " + sym + " " + (tf != null ? tf : "null") + " | ожидаю " + b.symbol() + " " + b.timeframe());
            return;
        }
        if (tf != null && !eq(tf, b.timeframe())) {
            logIgnore(key, "TICK_IGNORED_NOCTX",
                    "пришло " + (sym != null ? sym : "null") + " " + tf + " | ожидаю " + b.symbol() + " " + b.timeframe());
            return;
        }

        onPriceUpdate(chatId, type, b.exchange(), b.network(), b.symbol(), b.timeframe(), price, tradeTsMs);
    }

    public void onCandleClosed(long chatId,
                               StrategyType type,
                               String exchange,
                               NetworkType network,
                               String symbol,
                               String timeframe,
                               UnifiedKline kline) {

        if (type == null || kline == null) return;

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);
        if (b == null) return;

        ensurePolicyLoadedIfMissing(chatId, type, b);

        String ex = sanitizeExchange(exchange);
        if (ex == null) ex = b.exchange();
        NetworkType net = (network != null ? network : b.network());

        String sym = sanitizeSymbol(symbol);
        if (sym == null) sym = b.symbol();
        String tf = sanitizeTf(timeframe);
        if (tf == null) tf = b.timeframe();

        if (!eq(ex, b.exchange()) || net != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "CANDLE_IGNORED",
                    "пришло ex=" + ex + " net=" + net + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        if (isMarketEventsBlocked(key)) return;
        if (isDegradedBlocked(key, b, sym, tf)) return;

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return;

        if (strategy instanceof CandleCloseAware aware) {
            try {
                aware.onCandleClosed(chatId, type, sym, tf, kline, b.exchange(), b.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] CANDLE_HANDLER_FAILED chatId={} type={} | {}", chatId, type, e.getMessage());
            }
            return;
        }

        logIgnore(key, "CANDLE_UNSUPPORTED",
                "стратегия " + strategy.getClass().getSimpleName()
                + " не реализует CandleCloseAware (свеча пропущена)");
    }

    public void onCandleClosed(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               UnifiedKline kline) {

        if (type == null || kline == null) return;

        RunKey key = new RunKey(chatId, type);
        RunBinding b = running.get(key);
        if (b == null) return;

        String sym = sanitizeSymbol(symbol);
        String tf = sanitizeTf(timeframe);

        if (sym != null && !eq(sym, b.symbol())) {
            logIgnore(key, "CANDLE_IGNORED_NOCTX",
                    "пришло " + sym + " " + (tf != null ? tf : "null") + " | ожидаю " + b.symbol() + " " + b.timeframe());
            return;
        }
        if (tf != null && !eq(tf, b.timeframe())) {
            logIgnore(key, "CANDLE_IGNORED_NOCTX",
                    "пришло " + (sym != null ? sym : "null") + " " + tf + " | ожидаю " + b.symbol() + " " + b.timeframe());
            return;
        }

        onCandleClosed(chatId, type, b.exchange(), b.network(), b.symbol(), b.timeframe(), kline);
    }

    private void logIgnore(RunKey key, String code, String details) {
        AtomicLong c = ignoreCounters.computeIfAbsent(key, k -> new AtomicLong(0));
        long n = c.incrementAndGet();

        if (n <= 3 || (n % 200 == 0)) {
            log.warn("⚠️ [ORCH] {} chatId={} type={} #{} | {}",
                    code, key.chatId(), key.type(), n, details);
        }
    }

    private boolean isDegradedBlocked(RunKey key, RunBinding b, String symbol, String timeframe) {
        if (!blockWhenDegraded) return false;
        if (key == null || b == null) return false;

        String sym = sanitizeSymbol(symbol);
        if (sym == null) sym = b.symbol();

        String tf = sanitizeTf(timeframe);
        if (tf == null) tf = b.timeframe();

        if (sym == null || tf == null) return false;

        try {
            MarketDataStreamService.SubscriptionHealth health =
                    marketDataStreamService.getSubscriptionHealth(
                            key.chatId(),
                            key.type(),
                            b.exchange(),
                            b.network(),
                            sym,
                            tf
                    );

            if (health != null && health.degraded()) {
                if (hasFreshSourceBypass(health)) {
                    return false;
                }

                logDegradedThrottled(key, sym, tf, health);
                return true;
            }
        } catch (Exception e) {
            log.debug("⚠ [ORCH] degraded check failed chatId={} type={} err={}",
                    key.chatId(), key.type(), e.toString());
        }

        return false;
    }

    private boolean hasFreshSourceBypass(MarketDataStreamService.SubscriptionHealth health) {
        if (health == null) {
            return false;
        }

        long maxAge = Math.max(250L, degradedBypassMaxAgeMs);
        return isFreshAge(health.lastAggTradeAgeMs(), maxAge)
                || isFreshAge(health.lastKlineAgeMs(), maxAge)
                || isFreshAge(health.lastBookTickerAgeMs(), maxAge);
    }

    private boolean isFreshAge(long ageMs, long maxAgeMs) {
        return ageMs >= 0L && ageMs <= maxAgeMs;
    }

    private void logDegradedThrottled(RunKey key,
                                      String symbol,
                                      String timeframe,
                                      MarketDataStreamService.SubscriptionHealth health) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(2000L, degradedLogCooldownMs);

        Long prev = lastDegradedLogAtMs.get(key);
        if (prev != null && (now - prev) < cooldown) {
            return;
        }

        lastDegradedLogAtMs.put(key, now);

        log.warn("⚠️ [ORCH] MARKET_DEGRADED chatId={} type={} {} {} | reason={} | klineConnected={} aggConnected={} bookConnected={} | ageMs[kline={},agg={},book={}]",
                key.chatId(),
                key.type(),
                symbol,
                timeframe,
                health.reason(),
                health.klineConnected(),
                health.aggTradeConnected(),
                health.bookTickerConnected(),
                health.lastKlineAgeMs(),
                health.lastAggTradeAgeMs(),
                health.lastBookTickerAgeMs());
    }


    public record PreparationResult(boolean ok, String message) {
        public static PreparationResult ok(String message) {
            return new PreparationResult(true, message);
        }

        public static PreparationResult fail(String message) {
            return new PreparationResult(false, message);
        }
    }

    public interface PrepareStartAware {
        PreparationResult prepareStart(long chatId,
                                       StrategyType type,
                                       String symbol,
                                       String timeframe,
                                       String exchange,
                                       NetworkType network);
    }

    // =====================================================================
    // GLOBAL DASHBOARD
    // =====================================================================
    public record GlobalState(
            BigDecimal totalBalance,
            BigDecimal totalProfitPct,
            int activeStrategies
    ) {}

    public GlobalState getGlobalState(Long chatId) {
        int active;
        if (chatId == null || chatId <= 0) {
            active = 0;
        } else {
            long cnt = running.keySet().stream().filter(k -> k.chatId() == chatId).count();
            active = (int) Math.min(Integer.MAX_VALUE, cnt);
        }
        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }

    // =====================================================================
    // LOAD SETTINGS
    // =====================================================================

    /** Read-only загрузка (без sync/save, не портит контекст). */
    private StrategySettings loadSettingsReadOnly(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0 || type == null) return null;

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {
        }

        if (s == null) {
            try {
                s = settingsService.getOrCreate(chatId, type);
            } catch (Exception ignored) {
                return null;
            }
        }

        return s;
    }

    /**
     * Strict-load для START/STOP/RESTART: можно синхронизировать контекст (и сохранить).
     * Важно: не форсим TESTNET, если network == null.
     */
    private StrategySettings loadSettingsStrict(Long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId == null || chatId <= 0 || type == null) return null;

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {
        }

        if (s == null) {
            try {
                s = settingsService.getOrCreate(chatId, type);
            } catch (Exception ignored) {
                return null;
            }
        }

        String ex = sanitizeExchange(exchange);
        if (ex == null) ex = sanitizeExchange(s.getExchangeName());

        NetworkType net = (network != null ? network : s.getNetworkType());

        if (ex != null || net != null) {
            syncSettingsContextIfNeeded(s, ex, net);
        }

        return s;
    }

    private void syncSettingsContextIfNeeded(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return;

        boolean changed = false;

        if (exchange != null && (s.getExchangeName() == null || !eq(s.getExchangeName(), exchange))) {
            s.setExchangeName(exchange);
            changed = true;
        }

        if (network != null && s.getNetworkType() != network) {
            s.setNetworkType(network);
            changed = true;
        }

        if (changed) {
            try {
                saveSettingsWithRetry(s, "syncSettingsContextIfNeeded");
            } catch (Exception e) {
                log.debug("⚠ [ORCH] syncSettingsContextIfNeeded failed: {}", e.getMessage());
            }
        }
    }

    // =====================================================================
    // RUN INFO (DTO)
    // =====================================================================
    private StrategyRunInfo buildRunInfo(StrategySettings s, boolean active, String msg) {
        if (s == null) {
            return StrategyRunInfo.builder()
                    .active(active)
                    .message(msg)
                    .updatedAt(Instant.now())
                    .build();
        }

        return StrategyRunInfo.builder()
                .chatId(s.getChatId())
                .type(s.getType())
                .symbol(s.getSymbol())
                .active(active)
                .timeframe(s.getTimeframe())
                .exchangeName(s.getExchangeName())
                .networkType(s.getNetworkType())
                .version(s.getVersion())
                .startedAt(toInstant(s.getStartedAt()))
                .stoppedAt(toInstant(s.getStoppedAt()))
                .updatedAt(Instant.now())
                .message(msg)
                .build();
    }

    private StrategyRunInfo buildRunInfoFromBinding(StrategySettings s, RunBinding b, boolean active, String msg) {
        if (s == null) return buildRunInfo(null, active, msg);

        String ex = (b != null && b.exchange() != null) ? b.exchange() : sanitizeExchange(s.getExchangeName());
        NetworkType net = (b != null && b.network() != null) ? b.network() : s.getNetworkType();
        String sym = (b != null && b.symbol() != null) ? b.symbol() : sanitizeSymbol(s.getSymbol());
        String tf = (b != null && b.timeframe() != null) ? b.timeframe() : sanitizeTf(s.getTimeframe());

        return StrategyRunInfo.builder()
                .chatId(s.getChatId())
                .type(s.getType())
                .symbol(sym)
                .active(active)
                .timeframe(tf)
                .exchangeName(ex)
                .networkType(net)
                .version(s.getVersion())
                .startedAt(toInstant(s.getStartedAt()))
                .stoppedAt(toInstant(s.getStoppedAt()))
                .updatedAt(Instant.now())
                .message(msg)
                .build();
    }

    private Instant toInstant(LocalDateTime time) {
        return time != null
                ? time.atZone(ZoneId.systemDefault()).toInstant()
                : null;
    }

    
// =====================================================================
    // ORDER API
    // =====================================================================
    public record OrderResult(boolean success, String message, Long orderId) {}

    public record OrderView(
            Long id,
            String symbol,
            String side,
            String status,
            BigDecimal price,
            BigDecimal quantity,
            Boolean filled,
            Long timestamp
    ) {}

    private record ManualOrderContext(
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe
    ) {}

    public OrderResult marketBuy(Long chatId, String symbol, BigDecimal qty) {
        try {
            ManualOrderContext manualCtx = resolveManualOrderContext(chatId, symbol);
            if (manualCtx == null) {
                return new OrderResult(false, "Контекст стратегии для BUY не найден", null);
            }

            if (qty == null || qty.signum() <= 0) {
                return new OrderResult(false, "qty must be > 0", null);
            }

            String positionUid = buildManualEntryPositionUid(
                    chatId,
                    manualCtx.type(),
                    manualCtx.exchange(),
                    manualCtx.network(),
                    manualCtx.symbol()
            );

            OrderService.OrderContext orderCtx = new OrderService.OrderContext(
                    chatId,
                    manualCtx.type(),
                    manualCtx.symbol(),
                    manualCtx.timeframe(),
                    null,
                    "MANUAL_ENTRY",
                    manualCtx.exchange(),
                    manualCtx.network(),
                    "MANUAL_ENTRY",
                    positionUid
            );

            Order order = orderService.placeMarket(orderCtx, OrderSide.BUY, qty, BigDecimal.ZERO);
            return new OrderResult(true, "BUY OK", order != null ? order.getId() : null);
        } catch (IllegalStateException e) {
            log.warn("⚠️ marketBuy blocked chatId={} symbol={} err={}", chatId, symbol, e.getMessage());
            return new OrderResult(false, e.getMessage(), null);
        } catch (Exception e) {
            log.error("❌ marketBuy error chatId={} symbol={}", chatId, symbol, e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    public OrderResult marketSell(Long chatId, String symbol, BigDecimal qty) {
        try {
            ManualOrderContext manualCtx = resolveManualOrderContext(chatId, symbol);
            if (manualCtx == null) {
                return new OrderResult(false, "Контекст стратегии для SELL не найден", null);
            }

            if (qty == null || qty.signum() <= 0) {
                return new OrderResult(false, "qty must be > 0", null);
            }

            String positionUid = resolveManualExitPositionUid(
                    chatId,
                    manualCtx.type(),
                    manualCtx.exchange(),
                    manualCtx.network(),
                    manualCtx.symbol()
            );

            OrderService.OrderContext orderCtx = new OrderService.OrderContext(
                    chatId,
                    manualCtx.type(),
                    manualCtx.symbol(),
                    manualCtx.timeframe(),
                    null,
                    "MANUAL_CLOSE",
                    manualCtx.exchange(),
                    manualCtx.network(),
                    "MANUAL_CLOSE",
                    positionUid
            );

            Order order = orderService.placeMarket(orderCtx, OrderSide.SELL, qty, BigDecimal.ZERO);
            return new OrderResult(true, "SELL OK", order != null ? order.getId() : null);
        } catch (IllegalStateException e) {
            log.warn("⚠️ marketSell blocked chatId={} symbol={} err={}", chatId, symbol, e.getMessage());
            return new OrderResult(false, e.getMessage(), null);
        } catch (Exception e) {
            log.error("❌ marketSell error chatId={} symbol={}", chatId, symbol, e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    private ManualOrderContext resolveManualOrderContext(Long chatId, String symbol) {
        if (chatId == null || chatId <= 0) {
            throw new IllegalStateException("chatId пустой");
        }

        String sym = sanitizeSymbol(symbol);
        if (sym == null) {
            throw new IllegalStateException("symbol пустой");
        }

        List<ManualOrderContext> candidates = new ArrayList<>();

        for (var entry : running.entrySet()) {
            RunKey key = entry.getKey();
            RunBinding binding = entry.getValue();
            if (key == null || binding == null) continue;
            if (key.chatId() != chatId) continue;
            if (!eq(sym, binding.symbol())) continue;

            candidates.add(new ManualOrderContext(
                    key.type(),
                    binding.exchange(),
                    binding.network(),
                    binding.symbol(),
                    binding.timeframe()
            ));
        }

        if (candidates.isEmpty()) {
            for (StrategySettings s : discoverAllStrategySettings()) {
                if (s == null || s.getChatId() == null || !chatId.equals(s.getChatId()) || s.getType() == null) {
                    continue;
                }

                String candidateSymbol = sanitizeSymbol(s.getSymbol());
                if (!eq(sym, candidateSymbol)) {
                    continue;
                }

                candidates.add(new ManualOrderContext(
                        s.getType(),
                        sanitizeExchange(s.getExchangeName()),
                        s.getNetworkType(),
                        candidateSymbol,
                        sanitizeTf(s.getTimeframe())
                ));
            }
        }

        List<ManualOrderContext> distinct = new ArrayList<>();
        for (ManualOrderContext candidate : candidates) {
            boolean exists = distinct.stream().anyMatch(x ->
                    x.type() == candidate.type()
                            && eq(x.exchange(), candidate.exchange())
                            && x.network() == candidate.network()
                            && eq(x.symbol(), candidate.symbol())
                            && eq(x.timeframe(), candidate.timeframe()));
            if (!exists) {
                distinct.add(candidate);
            }
        }

        if (distinct.isEmpty()) {
            throw new IllegalStateException("Не найден run/settings context для symbol=" + sym);
        }

        if (distinct.size() > 1) {
            String variants = distinct.stream()
                    .map(x -> x.type() + ":" + x.exchange() + ":" + x.network() + ":" + x.symbol() + ":" + x.timeframe())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("ambiguous");
            throw new IllegalStateException("Несколько контекстов для symbol=" + sym + ": " + variants);
        }

        return distinct.get(0);
    }

    private String buildManualEntryPositionUid(Long chatId,
                                               StrategyType type,
                                               String exchange,
                                               NetworkType network,
                                               String symbol) {
        return "manual-entry:"
                + chatId + ":"
                + (type != null ? type.name() : "NA") + ":"
                + sanitizeExchange(exchange) + ":"
                + (network != null ? network.name() : "NA") + ":"
                + sanitizeSymbol(symbol) + ":"
                + Instant.now().toEpochMilli();
    }

    private String resolveManualExitPositionUid(Long chatId,
                                                StrategyType type,
                                                String exchange,
                                                NetworkType network,
                                                String symbol) {
        try {
            Optional<PositionStore.PositionSnapshot> opt = positionStore.getPosition(
                    chatId,
                    type,
                    exchange,
                    network,
                    symbol
            );
            if (opt.isPresent()) {
                PositionStore.PositionSnapshot snap = opt.get();
                long openedAtMs = snap.openedAt() != null ? snap.openedAt().toEpochMilli() : 0L;
                Long entryOrderId = snap.entryOrderId();
                return "pos:"
                        + chatId + ":"
                        + (type != null ? type.name() : "NA") + ":"
                        + sanitizeExchange(exchange) + ":"
                        + (network != null ? network.name() : "NA") + ":"
                        + sanitizeSymbol(symbol) + ":"
                        + openedAtMs + ":"
                        + (entryOrderId != null ? entryOrderId : "NA");
            }
        } catch (Exception e) {
            log.debug("⚠️ resolveManualExitPositionUid failed chatId={} type={} symbol={} err={}",
                    chatId, type, symbol, e.toString());
        }

        return "manual-close:"
                + chatId + ":"
                + (type != null ? type.name() : "NA") + ":"
                + sanitizeExchange(exchange) + ":"
                + (network != null ? network.name() : "NA") + ":"
                + sanitizeSymbol(symbol) + ":"
                + Instant.now().toEpochMilli();
    }

    public boolean cancelOrder(Long chatId, long orderId) {
        try {
            return orderService.cancelOrder(chatId, orderId);
        } catch (Exception e) {
            log.error("❌ cancelOrder error", e);
            return false;
        }
    }

    public List<OrderView> listOrders(Long chatId, String symbol) {
        try {
            return orderService.getOrdersByChatIdAndSymbol(chatId, symbol)
                    .stream()
                    .map(o -> new OrderView(
                            o.getId(),
                            o.getSymbol(),
                            o.getSide(),
                            o.getStatus(),
                            o.getPrice(),
                            o.getQuantity(),
                            o.isFilled(),
                            extractOrderTimestamp(o)
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("❌ listOrders error", e);
            return List.of();
        }
    }

    private Long extractOrderTimestamp(Order o) {
        if (o == null) return null;
        Long t = o.getTime();
        return (t != null && t > 0) ? t : null;
    }

// =====================================================================
    // ТИПОБЕЗОПАСНЫЕ ХУКИ ДЛЯ РЫНКА
    // =====================================================================
    public interface PriceUpdateAware {
        void onPriceUpdate(long chatId,
                           StrategyType type,
                           String symbol,
                           String timeframe,
                           BigDecimal price,
                           long tradeTsMs,
                           String exchange,
                           NetworkType network);
    }

    public interface CandleCloseAware {
        void onCandleClosed(long chatId,
                            StrategyType type,
                            String symbol,
                            String timeframe,
                            UnifiedKline kline,
                            String exchange,
                            NetworkType network);
    }

    // =====================================================================
    // small utils
    // =====================================================================
    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private static String sanitizeSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeTf(String tf) {
        if (tf == null) return null;
        String s = tf.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}



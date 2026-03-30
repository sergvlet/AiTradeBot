package com.chicu.aitradebot.ai.runtime;

import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingService;
import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeClosedEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlAutoTuneRuntime {

    private static final String PHASE_COLLECT  = "COLLECT";
    private static final String PHASE_BACKTEST = "BACKTEST";

    private final AutoTunerOrchestrator autoTuner;
    private final StrategySettingsService strategySettingsService;
    private final StrategyEnvResolver envResolver;
    private final PositionStore positionStore;

    // ✅ обучение
    private final MlTrainingService trainingService;

    // =====================================================
    // CONFIG (TUNE)
    // =====================================================

    @Value("${ai.autotune.periodicInitialDelayMinutes:30}")
    private long periodicInitialDelayMinutes;

    @Value("${ai.autotune.periodicEveryHours:6}")
    private long periodicEveryHours;

    /**
     * Фазы, в которых тюнинг выключаем:
     * например: BACKTEST,COLLECT
     */
    @Value("${ai.autotune.skipPhases:BACKTEST}")
    private String skipPhases;

    @Value("${ai.autotune.defaultDebounceSeconds:120}")
    private long defaultDebounceSeconds;

    @Value("${ai.autotune.minDebounceMillis:30000}")
    private long minDebounceMillis;

    /**
     * Реактивные триггеры по HOLD/low-confidence лучше держать выключенными по умолчанию,
     * иначе стратегия начинает тюнить себя на шуме в live.
     */
    @Value("${ai.autotune.allowReactiveTriggers:false}")
    private boolean allowReactiveTriggers;

    @Value("${ai.autotune.reactiveReasonPrefixes:hold:,low_confidence:}")
    private String reactiveReasonPrefixes;

    /**
     * Разрешить реактивный тюнинг при голодании по сделкам даже когда autoTuneEnabled=false,
     * если режим не MANUAL и фаза не заблокирована.
     */
    @Value("${ai.autotune.allowStarvationBypassAutoTuneDisabled:true}")
    private boolean allowStarvationBypassAutoTuneDisabled;

    @Value("${ai.autotune.starvationReasonPrefixes:starvation:,no_trade:}")
    private String starvationReasonPrefixes;

    // =====================================================
    // CONFIG (TRAIN)
    // =====================================================

    @Value("${ai.autotrain.enabled:true}")
    private boolean autoTrainEnabled;

    /** каждые N закрытий пытаемся обучить (0/<=0 = выключено) */
    @Value("${ai.autotrain.everyNClosedTrades:25}")
    private int everyNClosedTrades;

    /** триггерить обучение на SL/убытке */
    @Value("${ai.autotrain.triggerOnLoss:true}")
    private boolean triggerOnLoss;

    /** debounce для “лосс-триггера” (сек) */
    @Value("${ai.autotrain.lossDebounceSeconds:20}")
    private long lossDebounceSeconds;

    /** debounce для “каждые N закрытий” (сек) */
    @Value("${ai.autotrain.periodicDebounceSeconds:60}")
    private long periodicTrainDebounceSeconds;

    /**
     * Фазы, в которых авто-обучение выключаем:
     * например: BACKTEST,COLLECT
     */
    @Value("${ai.autotrain.skipPhases:BACKTEST,COLLECT}")
    private String trainSkipPhases;

    /**
     * Если true — авто-обучение требует ss.autoTuneEnabled=true (как у тюнинга).
     * Если false — обучаем в HYBRID/AI даже когда autoTune выключен.
     */
    @Value("${ai.autotrain.requireAutoTuneEnabled:false}")
    private boolean requireAutoTuneEnabled;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ai-autotune");
                t.setDaemon(true);
                return t;
            });

    /** key(chatId,type,exchange,network) -> periodic job */
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    /** key(chatId,type,exchange,network) сейчас выполняется */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** debounce для ручных/авто триггеров */
    private final Map<String, Long> lastTriggerAtMs = new ConcurrentHashMap<>();

    /** cache для skipPhases */
    private volatile String skipPhasesRawCache = null;

    private static final Set<String> DEFAULT_SKIP_PHASES = Set.of(PHASE_BACKTEST);

    private volatile Set<String> skipPhasesCache = DEFAULT_SKIP_PHASES;

    // ===== TRAIN state =====
    private final ConcurrentMap<String, Long> lastTrainTriggerAtMs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> closedSinceTrain = new ConcurrentHashMap<>();
    private final Set<String> trainingRunning = ConcurrentHashMap.newKeySet();

    private volatile String trainSkipPhasesRawCache = null;
    private volatile Set<String> trainSkipPhasesCache = Set.of(PHASE_BACKTEST, PHASE_COLLECT);

    @PreDestroy
    public void shutdown() {
        try {
            for (ScheduledFuture<?> f : jobs.values()) {
                try { f.cancel(false); } catch (Exception ignore) {}
            }
            jobs.clear();
        } catch (Exception ignore) {}

        try {
            scheduler.shutdownNow();
        } catch (Exception ignore) {}
    }

    // =====================================================
    // PUBLIC API (hooks from orchestrator)
    // =====================================================

    public void onStrategyStarted(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        if (!tuningGate(ss).allowed) {
            cancelAllForKey(key(chatId, type, env.exchange, env.network));
            return;
        }

        String k = key(chatId, type, env.exchange, env.network);

        long initialDelayMs = Duration.ofMinutes(Math.max(0, periodicInitialDelayMinutes)).toMillis();
        long everyMs = Duration.ofHours(Math.max(1, periodicEveryHours)).toMillis();

        jobs.computeIfAbsent(k, __ ->
                scheduler.scheduleWithFixedDelay(
                        () -> safeTune(chatId, type, env.exchange, env.network, "periodic"),
                        initialDelayMs,
                        everyMs,
                        TimeUnit.MILLISECONDS
                )
        );

        // ✅ быстрый стартовый прогон (если можно)
        scheduler.submit(() -> safeTune(chatId, type, env.exchange, env.network, "startup"));
    }

    public void onStrategyStopped(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        cancelAllForKey(key(chatId, type, env.exchange, env.network));
    }

    public void onPositionClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        if (!tuningGate(ss).allowed) return;

        scheduler.submit(() -> safeTune(chatId, type, env.exchange, env.network, "after-close"));
    }

    // =====================================================
    // ✅ AUTO-TRAIN via TradeClosedEvent (без правок в стратегиях)
    // =====================================================

    @EventListener
    public void onTradeClosedEvent(TradeClosedEvent e) {
        if (!autoTrainEnabled) return;
        if (trainingService == null) return;
        if (e == null) return;

        Long chatId = e.chatId();
        StrategyType type = e.strategyType();
        if (chatId == null || chatId <= 0 || type == null) return;

        String ex = normalizeExchangeOrNull(e.exchange());
        NetworkType net = e.network();

        // если event без env — попробуем резолвнуть
        ResolvedEnv env = resolveEnvSafe(chatId, type, ex, net);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        TrainGate tg = trainingGate(ss);
        if (!tg.allowed) return;

        String k = key(chatId, type, env.exchange, env.network);

        // считаем закрытия (для everyNClosedTrades)
        closedSinceTrain.merge(k, 1, Integer::sum);

        boolean loss = isLossEvent(e);
        boolean slHit = isSlHitEvent(e);

        // 1) на лоссе/SL
        if (triggerOnLoss && (loss || slHit)) {
            String reason = (slHit ? "auto_train:sl" : "auto_train:loss");
            triggerTrainDebounced(chatId, type, env.exchange, env.network, reason, Duration.ofSeconds(Math.max(5, lossDebounceSeconds)));
            return;
        }

        // 2) каждые N закрытий
        int n = everyNClosedTrades;
        if (n > 0) {
            Integer c = closedSinceTrain.get(k);
            if (c != null && c >= n) {
                String reason = "auto_train:every_" + n + "_closed";
                triggerTrainDebounced(chatId, type, env.exchange, env.network, reason, Duration.ofSeconds(Math.max(10, periodicTrainDebounceSeconds)));
            }
        }
    }

    public void triggerTrainDebounced(Long chatId,
                                      StrategyType type,
                                      String exchange,
                                      NetworkType network,
                                      String reason,
                                      Duration debounce) {
        if (!autoTrainEnabled) return;
        if (trainingService == null) return;
        if (chatId == null || type == null) return;

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        TrainGate tg = trainingGate(ss);
        if (!tg.allowed) return;

        String k = key(chatId, type, env.exchange, env.network);

        long now = System.currentTimeMillis();
        long d = Math.max(
                Math.max(5_000L, minDebounceMillis), // используем общий minDebounceMillis как нижнюю границу
                debounce != null ? debounce.toMillis() : 30_000L
        );

        Long last = lastTrainTriggerAtMs.get(k);
        if (last != null && (now - last) < d) return;
        lastTrainTriggerAtMs.put(k, now);

        String r = (reason != null && !reason.isBlank()) ? reason : "auto_train_trigger";

        // небольшой delay, чтобы если TradeClosedEvent прилетел до clearPosition — не мешать
        long delayMs = Math.min(10_000L, Math.max(0L, d));
        scheduler.schedule(() -> safeTrain(chatId, type, r), delayMs, TimeUnit.MILLISECONDS);
    }

    private void safeTrain(Long chatId, StrategyType type, String reason) {
        if (!autoTrainEnabled) return;
        if (trainingService == null) return;
        if (chatId == null || type == null) return;

        String k = chatId + ":" + type.name();
        if (!trainingRunning.add(k)) return;

        try {
            MlTrainingResult res = trainingService.trainNow(chatId, type, reason);

            boolean ok = resultOk(res);
            String err = resultError(res);

            if (ok) {
                // сброс счётчика “каждые N закрытий”
                try {
                    // сбрасываем все env-ключи для (chatId,type) — безопасно
                    String prefix = chatId + ":" + type.name() + ":";
                    for (String kk : closedSinceTrain.keySet()) {
                        if (kk != null && kk.startsWith(prefix)) closedSinceTrain.put(kk, 0);
                    }
                } catch (Exception ignore) {}

                log.info("🧠 AUTO-TRAIN OK chatId={} type={} reason={} res={}", chatId, type, safe(reason), String.valueOf(res));
            } else {
                log.info("🧠 AUTO-TRAIN SKIP chatId={} type={} reason={} err={} res={}",
                        chatId, type, safe(reason), safe(err), String.valueOf(res));
            }
        } catch (Exception e) {
            log.error("🧠 AUTO-TRAIN FAILED chatId={} type={} reason={} err={}",
                    chatId, type, safe(reason), safeMsg(e), e);
        } finally {
            trainingRunning.remove(k);
        }
    }

    private static boolean resultOk(Object res) {
        if (res == null) return false;

        // record: ok()
        Boolean v = reflectBool(res, "ok");
        if (v != null) return v;

        // bean: isOk()/getOk()
        v = reflectBool(res, "isOk");
        if (v != null) return v;
        v = reflectBool(res, "getOk");
        if (v != null) return v;

        // fallback
        return false;
    }

    private static String resultError(Object res) {
        if (res == null) return "null_result";
        Object v = reflectAny(res, "error");
        if (v == null) v = reflectAny(res, "getError");
        return v != null ? String.valueOf(v) : null;
    }

    private static Boolean reflectBool(Object obj, String method) {
        Object v = reflectAny(obj, method);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) return false;
        return null;
    }

    private static Object reflectAny(Object obj, String method) {
        if (obj == null || method == null || method.isBlank()) return null;
        try {
            Method m = obj.getClass().getMethod(method);
            if (m.getParameterCount() != 0) return null;
            return m.invoke(obj);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean isLossEvent(TradeClosedEvent e) {
        try {
            if (e.pnlPct() != null && e.pnlPct().signum() < 0) return true;
        } catch (Exception ignore) {}
        return false;
    }

    private static boolean isSlHitEvent(TradeClosedEvent e) {
        try {
            String r = e.exitReason();
            if (r != null) {
                String x = r.trim().toUpperCase(Locale.ROOT);
                if (x.equals("SL") || x.contains("STOP") || x.contains("STOP_LOSS")) return true;
            }
        } catch (Exception ignore) {}
        return false;
    }

    // =====================================================
    // HOLD hooks (optional)
    // =====================================================

    public void onHold(Long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol,
                       String reason) {

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        if (!tuningGate(ss).allowed) return;

        Duration debounce = Duration.ofSeconds(90);

        String r = "hold:" + safe(reason);
        if (symbol != null && !symbol.isBlank()) {
            r += ":sym=" + symbol.trim().toUpperCase(Locale.ROOT);
        }

        triggerTuneDebounced(chatId, type, env.exchange, env.network, r, debounce);
    }

    public void onHoldReason(Long chatId,
                             StrategyType type,
                             String exchange,
                             NetworkType network,
                             String reason) {

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        if (!tuningGate(ss).allowed) return;

        triggerTuneDebounced(chatId, type, env.exchange, env.network, "hold:" + safe(reason), Duration.ofSeconds(90));
    }

    public void triggerTuneDebounced(Long chatId,
                                     StrategyType type,
                                     String exchange,
                                     NetworkType network,
                                     String reason,
                                     Duration debounce) {

        if (chatId == null || type == null) return;
        if (shouldSkipReactiveTrigger(reason)) {
            if (log.isDebugEnabled()) {
                log.debug("🧠 AUTO-TUNE reactive trigger skipped chatId={} type={} ex={} net={} reason={}",
                        chatId, type, exchange, network, safe(reason));
            }
            return;
        }

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        boolean starvationBypass = isStarvationReason(reason);
        if (!tuningGate(ss, starvationBypass).allowed) return;

        String k = key(chatId, type, env.exchange, env.network);

        long now = System.currentTimeMillis();
        long d = Math.max(
                minDebounceMillis,
                debounce != null
                        ? debounce.toMillis()
                        : Duration.ofSeconds(Math.max(30, defaultDebounceSeconds)).toMillis()
        );

        Long last = lastTriggerAtMs.get(k);
        if (last != null && (now - last) < d) return;

        lastTriggerAtMs.put(k, now);

        String r = (reason != null && !reason.isBlank()) ? reason : "external-trigger";
        scheduler.submit(() -> safeTune(chatId, type, env.exchange, env.network, r));
    }

    // =====================================================
    // INTERNAL
    // =====================================================

    private void cancelAllForKey(String k) {
        ScheduledFuture<?> f = jobs.remove(k);
        if (f != null) {
            try { f.cancel(false); } catch (Exception ignore) {}
        }
        lastTriggerAtMs.remove(k);
        lastTrainTriggerAtMs.remove(k);
        running.remove(k);
    }

    private record Gate(boolean allowed, AdvancedControlMode mode, boolean autoTuneEnabled, String phase, String reason) {}

    private Gate tuningGate(StrategySettings ss) {
        return tuningGate(ss, false);
    }

    private Gate tuningGate(StrategySettings ss, boolean starvationBypassAutoTuneDisabled) {
        if (ss == null) {
            return new Gate(false, AdvancedControlMode.MANUAL, false, null, "no_settings");
        }

        AdvancedControlMode mode = (ss.getAdvancedControlMode() != null)
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        String phase = normalizeUpperNullable(ss.getRunPhase());
        boolean autoTuneEnabled = ss.isAutoTuneEnabled();

        if (mode == AdvancedControlMode.MANUAL) {
            return new Gate(false, mode, autoTuneEnabled, phase, "manual_mode");
        }

        if (phase != null && parsedSkipPhases().contains(phase)) {
            return new Gate(false, mode, autoTuneEnabled, phase, "skipPhase:" + phase);
        }

        if (!autoTuneEnabled && !starvationBypassAutoTuneDisabled) {
            return new Gate(false, mode, false, phase, "autoTuneDisabled");
        }

        if (!autoTuneEnabled) {
            return new Gate(true, mode, false, phase, "starvation_bypass_autoTuneDisabled");
        }

        return new Gate(true, mode, true, phase, "ok");
    }

    private record TrainGate(boolean allowed, AdvancedControlMode mode, boolean requireAutoTuneEnabled, boolean autoTuneEnabled, String phase, String reason) {}

    private TrainGate trainingGate(StrategySettings ss) {
        if (ss == null) {
            return new TrainGate(false, AdvancedControlMode.MANUAL, requireAutoTuneEnabled, false, null, "no_settings");
        }

        AdvancedControlMode mode = (ss.getAdvancedControlMode() != null)
                ? ss.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        String phase = normalizeUpperNullable(ss.getRunPhase());
        boolean autoTuneEnabled = ss.isAutoTuneEnabled();

        if (mode == AdvancedControlMode.MANUAL) {
            return new TrainGate(false, mode, requireAutoTuneEnabled, autoTuneEnabled, phase, "manual_mode");
        }

        if (requireAutoTuneEnabled && !autoTuneEnabled) {
            return new TrainGate(false, mode, true, false, phase, "autoTuneDisabled_required");
        }

        if (phase != null && parsedTrainSkipPhases().contains(phase)) {
            return new TrainGate(false, mode, requireAutoTuneEnabled, autoTuneEnabled, phase, "skipPhase:" + phase);
        }

        return new TrainGate(true, mode, requireAutoTuneEnabled, autoTuneEnabled, phase, "ok");
    }

    private void safeTune(Long chatId, StrategyType type, String exchange, NetworkType network, String reason) {
        if (chatId == null || type == null) return;
        if (shouldSkipReactiveTrigger(reason)) return;

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        String k = key(chatId, type, env.exchange, env.network);
        if (!running.add(k)) return;

        try {
            StrategySettings ss = loadSettingsSoft(chatId, type);
            boolean starvationBypass = isStarvationReason(reason);
            Gate gate = tuningGate(ss, starvationBypass);
            if (!gate.allowed) {
                if (log.isDebugEnabled()) {
                    log.debug("🧠 AUTO-TUNE skip chatId={} type={} ex={} net={} gate={}",
                            chatId, type, env.exchange, env.network, gate.reason);
                }
                return;
            }

            String symbol = safeUpperNullable(ss.getSymbol());
            String timeframe = safeLowerNullable(ss.getTimeframe());
            Integer candlesLimit = ss.getCachedCandlesLimit();

            if (symbol == null || timeframe == null || candlesLimit == null || candlesLimit <= 0) {
                log.warn("🧠 AUTO-TUNE skip (bad settings) chatId={} type={} ex={} net={} symbol={} tf={} limit={}",
                        chatId, type, env.exchange, env.network, symbol, timeframe, candlesLimit);
                return;
            }

            // ✅ НЕ тюним, если стратегия в позиции
            if (positionStore != null && positionStore.isInPosition(chatId, type, env.exchange, env.network, symbol)) {
                if (log.isDebugEnabled()) {
                    log.debug("🧠 AUTO-TUNE skip (in position) chatId={} type={} ex={} net={} sym={}",
                            chatId, type, env.exchange, env.network, symbol);
                }
                return;
            }

            TuningRequest req = TuningRequest.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .exchange(env.exchange)
                    .network(env.network)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .candlesLimit(candlesLimit)
                    .reason((reason != null && !reason.isBlank()) ? reason : "trigger")
                    .build();

            TuningResult res = autoTuner.tune(req);

            if (res == null) {
                log.warn("🧠 AUTO-TUNE result is null chatId={} type={} ex={} net={}", chatId, type, env.exchange, env.network);
                return;
            }

            if (res.applied()) {
                log.info("🧠 AUTO-TUNE APPLIED chatId={} type={} ex={} net={} score {} -> {} model={} reason={}",
                        chatId, type, env.exchange, env.network,
                        nz(res.scoreBefore()), nz(res.scoreAfter()),
                        safe(res.modelVersion()),
                        safe(res.reason()));
            } else {
                log.info("🧠 AUTO-TUNE SKIP chatId={} type={} ex={} net={} score {} -> {} model={} reason={}",
                        chatId, type, env.exchange, env.network,
                        nz(res.scoreBefore()), nz(res.scoreAfter()),
                        safe(res.modelVersion()),
                        safe(res.reason()));
            }

        } catch (Exception e) {
            log.error("🧠 AUTO-TUNE FAILED chatId={} type={} ex={} net={}: {}",
                    chatId, type, env.exchange, env.network, safeMsg(e), e);
        } finally {
            running.remove(k);
        }
    }

    private boolean shouldSkipReactiveTrigger(String reason) {
        if (reason == null || reason.isBlank()) return false;
        if (allowReactiveTriggers) return false;

        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        for (String prefix : parsedReactiveReasonPrefixes()) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> parsedReactiveReasonPrefixes() {
        String raw = reactiveReasonPrefixes == null ? "" : reactiveReasonPrefixes.trim();
        if (raw.isEmpty()) {
            return Set.of("hold:", "low_confidence:");
        }

        Set<String> out = new HashSet<>();
        for (String part : raw.split(",")) {
            String v = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) out.add(v);
        }
        if (out.isEmpty()) {
            return Set.of("hold:", "low_confidence:");
        }
        return out;
    }

    private boolean isStarvationReason(String reason) {
        if (!allowStarvationBypassAutoTuneDisabled) return false;
        if (reason == null || reason.isBlank()) return false;

        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        String raw = starvationReasonPrefixes == null ? "" : starvationReasonPrefixes.trim();
        if (raw.isEmpty()) {
            return normalized.startsWith("starvation:") || normalized.startsWith("no_trade:");
        }

        for (String part : raw.split(",")) {
            String prefix = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty() && normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================
    // ENV RESOLVE (NO DEFAULTS)
    // =====================================================

    private record ResolvedEnv(boolean ok, String exchange, NetworkType network) {}

    private ResolvedEnv resolveEnvSafe(Long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId == null || type == null) return new ResolvedEnv(false, null, null);

        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        if (ex == null || net == null) {
            try {
                StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
                if (ex == null) ex = normalizeExchangeOrNull(env.exchangeName());
                if (net == null) net = env.networkType();
            } catch (Exception e) {
                log.warn("🧠 AUTO env resolve FAIL chatId={} type={} ex={} net={} err={}",
                        chatId, type, exchange, network, safeMsg(e));
                return new ResolvedEnv(false, null, null);
            }
        }

        if (ex == null || net == null) {
            log.warn("🧠 AUTO env unresolved chatId={} type={} ex={} net={}", chatId, type, exchange, network);
            return new ResolvedEnv(false, null, null);
        }

        return new ResolvedEnv(true, ex, net);
    }

    private static String key(Long chatId, StrategyType type, String exchange, NetworkType network) {
        return chatId + ":" + type + ":" + exchange + ":" + network;
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? null : ex;
    }

    private Set<String> parsedSkipPhases() {
        String raw = (skipPhases == null ? "" : skipPhases.trim());
        if (raw.equals(skipPhasesRawCache) && skipPhasesCache != null) {
            return skipPhasesCache;
        }

        Set<String> parsed;
        try {
            if (raw.isEmpty()) {
                parsed = Set.of();
            } else {
                Set<String> out = new HashSet<>();
                for (String p : raw.split(",")) {
                    String v = p.trim();
                    if (!v.isEmpty()) out.add(v.toUpperCase(Locale.ROOT));
                }
                parsed = Collections.unmodifiableSet(out);
            }
        } catch (Exception ignore) {
            parsed = DEFAULT_SKIP_PHASES;
        }

        skipPhasesRawCache = raw;
        skipPhasesCache = parsed;
        return parsed;
    }

    private Set<String> parsedTrainSkipPhases() {
        String raw = (trainSkipPhases == null ? "" : trainSkipPhases.trim());
        if (raw.equals(trainSkipPhasesRawCache) && trainSkipPhasesCache != null) {
            return trainSkipPhasesCache;
        }

        Set<String> parsed;
        try {
            if (raw.isEmpty()) {
                parsed = Set.of();
            } else {
                Set<String> out = new HashSet<>();
                for (String p : raw.split(",")) {
                    String v = p.trim();
                    if (!v.isEmpty()) out.add(v.toUpperCase(Locale.ROOT));
                }
                parsed = Collections.unmodifiableSet(out);
            }
        } catch (Exception ignore) {
            parsed = Set.of(PHASE_BACKTEST, PHASE_COLLECT);
        }

        trainSkipPhasesRawCache = raw;
        trainSkipPhasesCache = parsed;
        return parsed;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }

    private static String safeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }

    private static String safeLowerNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }

    private static String nz(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? "null" : s;
    }

    // =====================================================
    // SETTINGS LOAD (soft)
    // =====================================================

    private StrategySettings loadSettingsSoft(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0 || type == null) return null;

        StrategySettings s = null;
        try {
            s = strategySettingsService.getSettings(chatId, type);
        } catch (Exception ignored) {}

        if (s == null) {
            try {
                s = strategySettingsService.getOrCreate(chatId, type);
            } catch (Exception ignored) {
                return null;
            }
        }

        return s;
    }
}


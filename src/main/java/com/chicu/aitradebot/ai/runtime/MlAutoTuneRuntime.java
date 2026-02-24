package com.chicu.aitradebot.ai.runtime;

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
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // =====================================================
    // CONFIG
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

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        StrategySettings ss = loadSettingsSoft(chatId, type);
        if (!tuningGate(ss).allowed) return;

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
        running.remove(k);
    }

    private record Gate(boolean allowed, AdvancedControlMode mode, boolean autoTuneEnabled, String phase, String reason) {}

    private Gate tuningGate(StrategySettings ss) {
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

        if (!autoTuneEnabled) {
            return new Gate(false, mode, false, phase, "autoTuneDisabled");
        }

        if (phase != null && parsedSkipPhases().contains(phase)) {
            return new Gate(false, mode, true, phase, "skipPhase:" + phase);
        }

        return new Gate(true, mode, true, phase, "ok");
    }

    private void safeTune(Long chatId, StrategyType type, String exchange, NetworkType network, String reason) {
        if (chatId == null || type == null) return;

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        String k = key(chatId, type, env.exchange, env.network);
        if (!running.add(k)) return;

        try {
            StrategySettings ss = loadSettingsSoft(chatId, type);
            Gate gate = tuningGate(ss);
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
                log.warn("🧠 AUTO-TUNE env resolve FAIL chatId={} type={} ex={} net={} err={}",
                        chatId, type, exchange, network, safeMsg(e));
                return new ResolvedEnv(false, null, null);
            }
        }

        if (ex == null || net == null) {
            log.warn("🧠 AUTO-TUNE env unresolved chatId={} type={} ex={} net={}", chatId, type, exchange, network);
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
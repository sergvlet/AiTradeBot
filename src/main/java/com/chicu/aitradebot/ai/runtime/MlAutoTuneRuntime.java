package com.chicu.aitradebot.ai.runtime;

import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettings;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import com.chicu.aitradebot.trade.PositionStore;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlAutoTuneRuntime {

    /**
     * ВАЖНО:
     * - AutoTunerOrchestrator может вернуть "лучшие параметры", но НЕ применить их в БД (applied=false).
     * - Этот рантайм теперь умеет:
     *   1) триггерить тюнинг,
     *   2) пытаться применить патч в БД сам (минимум для WINDOW_SCALPING),
     *   3) после применения перечитать настройки и залогировать, что реально поменялось.
     */

    private static final String PHASE_COLLECT  = "COLLECT";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_PAPER    = "PAPER";

    private final AutoTunerOrchestrator autoTuner;
    private final StrategySettingsService strategySettingsService;
    private final StrategyEnvResolver envResolver;
    private final PositionStore positionStore;

    /**
     * ✅ Writer для WINDOW_SCALPING: чтобы тюнер реально менял БД.
     */
    private final WindowScalpingStrategySettingsService windowSettingsService;

    // =====================================================
    // CONFIG
    // =====================================================

    @Value("${ai.autotune.periodicInitialDelayMinutes:30}")
    private long periodicInitialDelayMinutes;

    @Value("${ai.autotune.periodicEveryHours:6}")
    private long periodicEveryHours;

    /**
     * Какие фазы надо пропускать (верхний регистр, через запятую).
     * Пример: COLLECT,BACKTEST
     * По умолчанию пропускаем COLLECT и BACKTEST, а PAPER и LIVE — разрешаем.
     */
    @Value("${ai.autotune.skipPhases:COLLECT,BACKTEST}")
    private String skipPhases;

    /**
     * Дебаунс по умолчанию для триггеров (если не передали явно).
     */
    @Value("${ai.autotune.defaultDebounceSeconds:120}")
    private long defaultDebounceSeconds;

    /**
     * Минимальная пауза дебаунса (защита от 1-2 сек).
     */
    @Value("${ai.autotune.minDebounceMillis:30000}")
    private long minDebounceMillis;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ai-autotune");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    // ✅ debounce для ручных/авто триггеров
    private final Map<String, Long> lastTriggerAtMs = new ConcurrentHashMap<>();

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
    // PUBLIC API
    // =====================================================

    public void onStrategyStarted(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

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

        scheduler.submit(() -> safeTune(chatId, type, env.exchange, env.network, "warmup"));
    }

    public void onStrategyStopped(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        String k = key(chatId, type, env.exchange, env.network);

        ScheduledFuture<?> f = jobs.remove(k);
        if (f != null) {
            try { f.cancel(false); } catch (Exception ignore) {}
        }

        lastTriggerAtMs.remove(k);
        running.remove(k);
    }

    public void onPositionClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        scheduler.submit(() -> safeTune(chatId, type, env.exchange, env.network, "after-close"));
    }

    // =====================================================
    // HOLD-хуки
    // =====================================================

    public void onHold(Long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol,
                       String reason) {

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

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

        Duration debounce = Duration.ofSeconds(90);
        String r = "hold:" + safe(reason);

        triggerTuneDebounced(chatId, type, env.exchange, env.network, r, debounce);
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

    private void safeTune(Long chatId, StrategyType type, String exchange, NetworkType network, String reason) {
        if (chatId == null || type == null) return;

        ResolvedEnv env = resolveEnvSafe(chatId, type, exchange, network);
        if (!env.ok) return;

        String k = key(chatId, type, env.exchange, env.network);

        if (!running.add(k)) return;

        try {
            // 1) НЕ тюним, если стратегия в позиции
            if (positionStore.isInPosition(chatId, type, env.exchange, env.network)) {
                log.debug("🧠 ML skip tune (in position) chatId={} type={} ex={} net={}", chatId, type, env.exchange, env.network);
                return;
            }

            // 2) Берём настройки (строго по env)
            StrategySettings ss = strategySettingsService.getOrCreate(chatId, type, env.exchange, env.network);
            if (ss == null) return;

            if (!ss.isAutoTuneEnabled()) {
                log.debug("🧠 ML skip tune (autoTuneEnabled=false) chatId={} type={} ex={} net={}", chatId, type, env.exchange, env.network);
                return;
            }

            // 3) Фазы, которые нужно пропустить (конфигом)
            String phase = normalizeUpperNullable(ss.getRunPhase());
            if (phase != null && parsedSkipPhases().contains(phase)) {
                log.debug("🧠 ML skip tune (phase={}) chatId={} type={} ex={} net={}", phase, chatId, type, env.exchange, env.network);
                return;
            }

            // 4) Минимальные параметры для тюнинга
            String symbol = safeUpperNullable(ss.getSymbol());
            String timeframe = safeLowerNullable(ss.getTimeframe());
            Integer candlesLimit = ss.getCachedCandlesLimit();

            if (symbol == null || timeframe == null || candlesLimit == null || candlesLimit <= 0) {
                log.warn("🧠 ML skip tune (bad settings) chatId={} type={} ex={} net={} symbol={} tf={} limit={}",
                        chatId, type, env.exchange, env.network, symbol, timeframe, candlesLimit);
                return;
            }

            String beforeFp = fingerprintBefore(type, chatId, env.exchange, env.network);

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

            Object result = autoTuner.tune(req);

            boolean appliedFlag = readBool(result, "applied", "isApplied", "getApplied");
            String resultReason = readString(result, "reason", "getReason", "message", "getMessage");

            // ✅ если AutoTuner сам НЕ применил — пробуем применить патч в БД здесь
            boolean appliedByRuntime = false;
            if (!appliedFlag) {
                appliedByRuntime = tryApplyResultToDb(type, chatId, env.exchange, env.network, result);
                if (appliedByRuntime) appliedFlag = true;
            }

            String afterFp = fingerprintAfter(type, chatId, env.exchange, env.network);

            if (appliedFlag) {
                log.info("🧠 ML tune done chatId={} type={} ex={} net={} applied={} appliedByRuntime={} reason={} changed={}",
                        chatId, type, env.exchange, env.network,
                        true, appliedByRuntime,
                        (resultReason != null ? resultReason : "ok"),
                        (!Objects.equals(beforeFp, afterFp))
                );
            } else {
                log.info("🧠 ML tune done chatId={} type={} ex={} net={} applied=false reason={} (no db changes)",
                        chatId, type, env.exchange, env.network,
                        (resultReason != null ? resultReason : "not_applied")
                );
            }

        } catch (Exception e) {
            log.error("🧠 ML tune FAILED chatId={} type={} ex={} net={}: {}",
                    chatId, type, env.exchange, env.network, e.getMessage(), e);
        } finally {
            running.remove(k);
        }
    }

    // =====================================================
    // APPLY RESULT → DB (WINDOW_SCALPING)
    // =====================================================

    private boolean tryApplyResultToDb(StrategyType type, long chatId, String exchange, NetworkType network, Object result) {
        if (result == null) return false;

        try {
            if (type == StrategyType.WINDOW_SCALPING) {

                // 1) Иногда AutoTuner возвращает прям объект настроек
                Object maybeCfg = readAny(result,
                        "windowSettings", "getWindowSettings",
                        "bestWindowSettings", "getBestWindowSettings",
                        "bestSettings", "getBestSettings",
                        "settings", "getSettings",
                        "cfg", "getCfg"
                );

                if (maybeCfg instanceof WindowScalpingStrategySettings ws) {
                    WindowScalpingStrategySettings patch = sanitizeWindowPatch(chatId, ws);
                    windowSettingsService.update(chatId, patch);
                    return true;
                }

                // 2) Или Map с параметрами
                Object maybeMap = readAny(result,
                        "params", "getParams",
                        "bestParams", "getBestParams",
                        "patch", "getPatch",
                        "settingsPatch", "getSettingsPatch",
                        "appliedPatch", "getAppliedPatch"
                );

                if (maybeMap instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) (Map<?, ?>) raw;

                    WindowScalpingStrategySettings patch = mapToWindowPatch(chatId, map);
                    if (patch != null) {
                        windowSettingsService.update(chatId, patch);
                        return true;
                    }


                }
            }

        } catch (Exception e) {
            log.warn("🧠 ML apply patch failed type={} chatId={} ex={} net={} err={}",
                    type, chatId, exchange, network, safeMsg(e));
        }

        return false;
    }

    private WindowScalpingStrategySettings sanitizeWindowPatch(long chatId, WindowScalpingStrategySettings ws) {
        WindowScalpingStrategySettings.WindowScalpingStrategySettingsBuilder b =
                WindowScalpingStrategySettings.builder().chatId(chatId);

        if (ws.getWindowSize() != null && ws.getWindowSize() > 0) b.windowSize(ws.getWindowSize());
        if (ws.getEntryFromLowPct() != null) b.entryFromLowPct(ws.getEntryFromLowPct());
        if (ws.getEntryFromHighPct() != null) b.entryFromHighPct(ws.getEntryFromHighPct());
        if (ws.getMinRangePct() != null) b.minRangePct(ws.getMinRangePct());

        if (ws.getTakeProfitPct() != null && ws.getTakeProfitPct().signum() > 0) b.takeProfitPct(ws.getTakeProfitPct());
        if (ws.getStopLossPct() != null && ws.getStopLossPct().signum() > 0) b.stopLossPct(ws.getStopLossPct());

        return b.build();
    }

    private WindowScalpingStrategySettings mapToWindowPatch(long chatId, Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;

        Integer windowSize = toInt(map.get("windowSize"));
        Double entryLow = toDouble(map.get("entryFromLowPct"));
        Double entryHigh = toDouble(map.get("entryFromHighPct"));
        Double minRange = toDouble(map.get("minRangePct"));

        BigDecimal tp = toBigDecimal(map.get("takeProfitPct"));
        BigDecimal sl = toBigDecimal(map.get("stopLossPct"));

        boolean any = windowSize != null || entryLow != null || entryHigh != null || minRange != null || tp != null || sl != null;
        if (!any) return null;

        WindowScalpingStrategySettings.WindowScalpingStrategySettingsBuilder b =
                WindowScalpingStrategySettings.builder().chatId(chatId);

        if (windowSize != null && windowSize > 0) b.windowSize(windowSize);
        if (entryLow != null) b.entryFromLowPct(entryLow);
        if (entryHigh != null) b.entryFromHighPct(entryHigh);
        if (minRange != null) b.minRangePct(minRange);

        if (tp != null && tp.signum() > 0) b.takeProfitPct(tp);
        if (sl != null && sl.signum() > 0) b.stopLossPct(sl);

        return b.build();
    }

    private String fingerprintBefore(StrategyType type, long chatId, String exchange, NetworkType network) {
        try {
            if (type == StrategyType.WINDOW_SCALPING) {
                WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);
                return "WS:" + fp(cfg);
            }
        } catch (Exception ignore) {}
        return null;
    }

    private String fingerprintAfter(StrategyType type, long chatId, String exchange, NetworkType network) {
        try {
            if (type == StrategyType.WINDOW_SCALPING) {
                WindowScalpingStrategySettings cfg = windowSettingsService.getOrCreate(chatId);
                return "WS:" + fp(cfg);
            }
        } catch (Exception ignore) {}
        return null;
    }

    private String fp(WindowScalpingStrategySettings cfg) {
        if (cfg == null) return "null";
        return String.valueOf(cfg.getWindowSize()) + "|" +
               String.valueOf(cfg.getEntryFromLowPct()) + "|" +
               String.valueOf(cfg.getEntryFromHighPct()) + "|" +
               String.valueOf(cfg.getMinRangePct()) + "|" +
               (cfg.getTakeProfitPct() != null ? cfg.getTakeProfitPct().stripTrailingZeros().toPlainString() : "null") + "|" +
               (cfg.getStopLossPct() != null ? cfg.getStopLossPct().stripTrailingZeros().toPlainString() : "null");
    }

    // =====================================================
    // ENV RESOLVE (NO DEFAULT MAINNET!)
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
                log.warn("🧠 ML env resolve FAIL chatId={} type={} ex={} net={} err={}",
                        chatId, type, exchange, network, safeMsg(e));
                return new ResolvedEnv(false, null, null);
            }
        }

        if (ex == null || net == null) {
            log.warn("🧠 ML env unresolved chatId={} type={} ex={} net={}", chatId, type, exchange, network);
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
        try {
            String s = skipPhases == null ? "" : skipPhases.trim();
            if (s.isEmpty()) return Set.of();

            Set<String> out = new HashSet<>();
            for (String p : s.split(",")) {
                String v = p.trim();
                if (!v.isEmpty()) out.add(v.toUpperCase(Locale.ROOT));
            }
            return out;
        } catch (Exception ignore) {
            return Set.of(PHASE_COLLECT, PHASE_BACKTEST);
        }
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

    // =====================================================
    // REFLECTION HELPERS (result)
    // =====================================================

    private static Object readAny(Object obj, String... gettersOrFields) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            for (String n : gettersOrFields) {
                Method m = findNoArgMethod(c, n);
                if (m != null) return m.invoke(obj);

                try {
                    var f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean readBool(Object obj, String... names) {
        Object v = readAny(obj, names);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static String readString(Object obj, String... names) {
        Object v = readAny(obj, names);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s.trim();
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignored) {}
        return null;
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception ignored) {}
        return null;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception ignored) {}
        return null;
    }

    private static Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}
        String cap = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}
        return null;
    }
}

package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final OrderService orderService;
    private final StrategySettingsService settingsService;
    private final StrategyRegistry strategyRegistry;

    /**
     * ✅ ML autotune runtime (оркестратор — главный lifecycle хаб)
     * ✅ сделано через ObjectProvider, чтобы:
     *  - не ловить циклические зависимости при старте контекста
     *  - переживать временное отсутствие ML-бина во время пересборки ML-слоя
     */
    private final ObjectProvider<MlAutoTuneRuntime> mlAutoTuneRuntime;

    private MlAutoTuneRuntime ml() {
        return mlAutoTuneRuntime != null ? mlAutoTuneRuntime.getIfAvailable() : null;
    }

    // =====================================================================
    // ✅ RUN CONTEXT (источник истины)
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

    // 🔒 атомарность операций на (chatId,type)
    private final ConcurrentMap<RunKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(RunKey key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    // =====================================================================
    // ✅ RUNTIME PHASE CACHE (чтобы не лезть в БД на каждый тик)
    // =====================================================================

    private record RuntimePhase(AdvancedControlMode mode, String runPhase) {}
    private final ConcurrentMap<RunKey, RuntimePhase> runtimePhases = new ConcurrentHashMap<>();

    private static String sanitizePhase(String p) {
        if (p == null) return "LIVE";
        String x = p.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? "LIVE" : x;
    }

    private RuntimePhase phaseOf(StrategySettings s) {
        AdvancedControlMode m = (s != null && s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;
        String rp = sanitizePhase(s != null ? s.getRunPhase() : null);
        return new RuntimePhase(m, rp);
    }

    /** ✅ блокируем торговые события для фаз COLLECT/BACKTEST */
    private boolean isMarketEventsBlocked(RunKey key) {
        RuntimePhase rp = runtimePhases.get(key);
        if (rp == null) return false;
        String phase = rp.runPhase();
        return "COLLECT".equalsIgnoreCase(phase) || "BACKTEST".equalsIgnoreCase(phase);
    }

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4 initialized | mlRuntime={}", (ml() != null ? "ON" : "OFF"));
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
     * ✅ Проверка “запущено ли в конкретном контексте” (exchange/network).
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
    // ✅ RUNTIME POLICY (единые правила режима/фазы)
    // =====================================================================

    /**
     * Единая точка принятия решения:
     * - обновляет runtimePhases cache
     * - включает/выключает MlAutoTuneRuntime в зависимости от mode/flags
     */
    private void applyRuntimePolicy(long chatId,
                                    StrategyType type,
                                    String exchange,
                                    NetworkType network,
                                    StrategySettings s) {
        if (type == null) return;

        RunKey key = new RunKey(chatId, type);

        // 1) обновить кеш фаз/режима
        RuntimePhase rp = phaseOf(s);
        runtimePhases.put(key, rp);

        AdvancedControlMode mode = rp.mode() != null ? rp.mode() : AdvancedControlMode.MANUAL;

        // 2) MANUAL -> autotune всегда стоп
        if (mode == AdvancedControlMode.MANUAL) {
            safeAutotuneStop(chatId, type, exchange, network);
            return;
        }

        // 3) HYBRID/AI -> autotune старт только если включен флаг autoTuneEnabled
        // ✅ НЕ используем прямой вызов s.isAutoTuneEnabled() (может отличаться геттер)
        boolean autoTuneEnabled = readBool(s,
                "autoTuneEnabled", "isAutoTuneEnabled", "getAutoTuneEnabled",
                "mlAutoTuneEnabled", "isMlAutoTuneEnabled", "getMlAutoTuneEnabled"
        );

        if (autoTuneEnabled) safeAutotuneStart(chatId, type, exchange, network);
        else safeAutotuneStop(chatId, type, exchange, network);
    }

    /**
     * ✅ Обновить runtime фазу/режим без рестарта (важно при переключении MANUAL/HYBRID/AI).
     * Вызывается из UI после сохранения.
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

    // =====================================================================
    // 🔄 ATOMIC RESTART (главный метод)
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
            String newTf  = sanitizeTf(s.getTimeframe());

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
                // контекст тот же — обновляем runtime-policy (режим/фаза/автотюн)
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

            // 1) STOP старого binding
            try {
                strategy.stop(chatId, current.symbol(), current.exchange(), current.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] stop(old) failed chatId={} type={} : {}", chatId, type, e.getMessage());
            }
            safeAutotuneStop(chatId, type, current.exchange(), current.network());

            // 2) UPDATE binding (до старта!)
            running.put(key, desired);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);

            // 3) START нового binding
            try {
                strategy.start(chatId, desired.symbol(), desired.exchange(), desired.network());
            } catch (Exception e) {
                // откат binding если старт не удался
                running.put(key, current);
                runtimePhases.put(key, phaseOf(s)); // пусть будет хотя бы актуально по БД
                log.error("❌ [ORCH] start(new) failed chatId={} type={} ex={} net={} sym={} tf={}",
                        chatId, type, desired.exchange(), desired.network(), desired.symbol(), desired.timeframe(), e);
                return buildRunInfoFromBinding(s, current, true, "Ошибка рестарта: не удалось запустить новый контекст");
            }

            // ✅ фиксируем контекст и active=true в БД (косметика/консистентность)
            syncSettingsContextIfNeeded(s, desired.exchange(), desired.network());
            if (!s.isActive()) {
                s.setActive(true);
                s.setStartedAt(LocalDateTime.now());
                s.setStoppedAt(null);
                settingsService.save(s);
            }

            // ✅ применяем policy уже в новом контексте
            applyRuntimePolicy(chatId, type, desired.exchange(), desired.network(), s);

            return buildRunInfoFromBinding(s, desired, true, "Контекст изменён — стратегия перезапущена");

        } finally {
            lock.unlock();
        }
    }

    // =====================================================================
    // ▶️ START
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
            String tf  = sanitizeTf(s.getTimeframe());

            String ex = sanitizeExchange(exchange);
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = (network != null ? network : s.getNetworkType());
            if (net == null) net = NetworkType.TESTNET;

            if (sym == null) return buildRunInfo(s, false, "Ошибка: не выбран символ");

            TradingStrategy strategy = strategyRegistry.get(type);
            if (strategy == null) return buildRunInfo(s, false, "Стратегия не найдена");

            RunBinding newBinding = new RunBinding(ex, net, sym, tf, Instant.now());
            RunBinding existing = running.get(key);

            if (existing != null
                && eq(existing.exchange(), newBinding.exchange())
                && existing.network() == newBinding.network()
                && eq(existing.symbol(), newBinding.symbol())
                && eq(existing.timeframe(), newBinding.timeframe())) {

                log.info("⏭ [ORCH] Уже запущено: {} chatId={} ex={} net={} {} {}",
                        type, chatId, ex, net, sym, tf);

                syncSettingsContextIfNeeded(s, ex, net);
                if (!s.isActive()) {
                    s.setActive(true);
                    s.setStartedAt(LocalDateTime.now());
                    s.setStoppedAt(null);
                    settingsService.save(s);
                }

                // ✅ policy + phase cache
                applyRuntimePolicy(chatId, type, ex, net, s);

                return buildRunInfo(s, true, "Стратегия уже запущена");
            }

            // если был другой binding — аккуратно стопаем старый
            if (existing != null) {
                log.warn("⚠️ [ORCH] Перезапуск binding: {} chatId={} было ex={} net={} {} {} -> стало ex={} net={} {} {}",
                        type, chatId,
                        existing.exchange(), existing.network(), existing.symbol(), existing.timeframe(),
                        ex, net, sym, tf);

                try {
                    strategy.stop(chatId, existing.symbol(), existing.exchange(), existing.network());
                } catch (Exception e) {
                    log.warn("⚠️ [ORCH] Не удалось корректно остановить старый runtime: {}", e.getMessage());
                }

                safeAutotuneStop(chatId, type, existing.exchange(), existing.network());
            }

            // ✅ фиксируем binding ДО старта
            running.put(key, newBinding);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);

            try {
                strategy.start(chatId, sym, ex, net);
            } catch (Exception e) {
                running.remove(key, newBinding);
                runtimePhases.remove(key);
                log.error("❌ [ORCH] startStrategy failed type={} chatId={} ex={} net={} sym={} tf={}",
                        type, chatId, ex, net, sym, tf, e);
                return buildRunInfo(s, false, "Ошибка запуска стратегии");
            }

            syncSettingsContextIfNeeded(s, ex, net);

            s.setActive(true);
            s.setStartedAt(LocalDateTime.now());
            s.setStoppedAt(null);
            settingsService.save(s);

            log.info("▶️ [ORCH] START {} chatId={} ex={} net={} symbol={} tf={}",
                    type, chatId, ex, net, sym, tf);

            // ✅ policy + phase cache + autotune on/off
            applyRuntimePolicy(chatId, type, ex, net, s);

            return buildRunInfo(s, true, "Стратегия запущена");

        } finally {
            lock.unlock();
        }
    }

    // =====================================================================
    // ⏹ STOP
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

            RunBinding removed = running.remove(key);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            runtimePhases.remove(key);

            String ex = removed != null ? removed.exchange() : sanitizeExchange(exchange);
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = removed != null ? removed.network() : (network != null ? network : s.getNetworkType());
            if (net == null) net = NetworkType.TESTNET;

            TradingStrategy strategy = strategyRegistry.get(type);

            if (strategy != null) {
                try {
                    if (removed != null) {
                        strategy.stop(chatId, removed.symbol(), removed.exchange(), removed.network());
                    } else {
                        strategy.stop(chatId, sym, ex, net);
                    }
                } catch (Exception e) {
                    log.error("❌ [ORCH] stopStrategy failed type={} chatId={} ex={} net={} sym={}",
                            type, chatId, ex, net, sym, e);
                }
            }

            syncSettingsContextIfNeeded(s, ex, net);

            s.setActive(false);
            s.setStoppedAt(LocalDateTime.now());
            settingsService.save(s);

            safeAutotuneStop(chatId, type, ex, net);

            log.info("⏹ [ORCH] STOP {} chatId={} | bindingRemoved={}", type, chatId, removed != null);
            return buildRunInfo(s, false, "Стратегия остановлена");

        } finally {
            lock.unlock();
        }
    }

    /** ✅ Совместимость со старым вызовом из UI. Теперь это просто рестарт под локом. */
    public StrategyRunInfo onSettingsChanged(Long chatId,
                                             StrategyType type,
                                             String exchange,
                                             NetworkType network) {
        return restartStrategyAtomic(chatId, type, exchange, network, "onSettingsChanged");
    }

    // =====================================================================
    // ℹ STATUS (✅ read-only, без записи в БД!)
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

        // ✅ только читаем настройки (без sync/save)
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
            StrategyRunInfo info = buildRunInfo(s, false, "Стратегия остановлена");

            String ex = sanitizeExchange(exchange);
            if (ex == null) ex = sanitizeExchange(s.getExchangeName());
            if (ex == null) ex = "BINANCE";

            NetworkType net = (network != null ? network : s.getNetworkType());
            if (net == null) net = NetworkType.TESTNET;

            patchRunInfoContext(info, ex, net, null, null);
            return info;
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
    // ✅ ML AUTO-TUNE HOOKS
    // =====================================================================

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

    // =====================================================================
    // ✅ MARKET STREAM входы (строгий контекст)
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

        // ✅ если exchange/network не передали (старый/частичный вызов) — берём из binding
        String ex = sanitizeExchange(exchange);
        if (ex == null) ex = b.exchange();
        NetworkType net = (network != null ? network : b.network());

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);

        if (!eq(ex, b.exchange()) || net != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "TICK_IGNORED",
                    "пришло ex=" + ex + " net=" + net + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        // ✅ COLLECT/BACKTEST: не отдаём событие стратегии → исключаем торговлю
        if (isMarketEventsBlocked(key)) return;

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return;

        if (strategy instanceof PriceUpdateAware aware) {
            try {
                aware.onPriceUpdate(chatId, type, sym, tf, price, tradeTsMs, b.exchange(), b.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] TICK_HANDLER_FAILED chatId={} type={} | {}", chatId, type, e.getMessage());
            }
            return;
        }

        try {
            strategy.onPriceUpdate(chatId, sym, price, Instant.ofEpochMilli(tradeTsMs));
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
        String tf  = sanitizeTf(timeframe);

        if (!eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "TICK_IGNORED_NOCTX",
                    "пришло " + sym + " " + tf + " | ожидаю " + b.symbol() + " " + b.timeframe());
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

        // ✅ если exchange/network не передали — берём из binding
        String ex = sanitizeExchange(exchange);
        if (ex == null) ex = b.exchange();
        NetworkType net = (network != null ? network : b.network());

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);

        if (!eq(ex, b.exchange()) || net != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "CANDLE_IGNORED",
                    "пришло ex=" + ex + " net=" + net + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        if (isMarketEventsBlocked(key)) return;

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
        String tf  = sanitizeTf(timeframe);

        if (!eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "CANDLE_IGNORED_NOCTX",
                    "пришло " + sym + " " + tf + " | ожидаю " + b.symbol() + " " + b.timeframe());
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

    // =====================================================================
    // 🌍 GLOBAL DASHBOARD (✅ source of truth = running)
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
    // 🔑 LOAD SETTINGS
    // =====================================================================

    /** ✅ Read-only загрузка (без sync/save, НЕ портит контекст). */
    private StrategySettings loadSettingsReadOnly(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0 || type == null) return null;

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {}

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
     * ✅ Strict-load для START/STOP/RESTART: можно синхронизировать контекст (и сохранить).
     * Важно: НЕ форсим TESTNET, если network == null.
     */
    private StrategySettings loadSettingsStrict(Long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId == null || chatId <= 0 || type == null) return null;

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {}

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
                settingsService.save(s);
            } catch (Exception e) {
                log.debug("⚠ [ORCH] syncSettingsContextIfNeeded failed: {}", e.getMessage());
            }
        }
    }

    // =====================================================================
    // 🧱 RUN INFO (DTO)
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
        StrategyRunInfo info = buildRunInfo(s, active, msg);
        if (b != null) {
            patchRunInfoContext(info, b.exchange(), b.network(), b.symbol(), b.timeframe());
        }
        return info;
    }

    /** ✅ Аккуратная подстановка контекста в DTO (если DTO immutable — просто пропускаем). */
    private void patchRunInfoContext(StrategyRunInfo info,
                                     String exchange,
                                     NetworkType network,
                                     String symbol,
                                     String timeframe) {
        if (info == null) return;

        trySet(info, "setExchangeName", String.class, exchange);
        trySet(info, "setNetworkType", NetworkType.class, network);
        trySet(info, "setSymbol", String.class, symbol);
        trySet(info, "setTimeframe", String.class, timeframe);
    }

    private void trySet(Object target, String method, Class<?> argType, Object value) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod(method, argType);
            m.invoke(target, value);
        } catch (Exception ignored) {
            // DTO может быть immutable — тогда просто пропускаем
        }
    }

    private Instant toInstant(LocalDateTime time) {
        return time != null
                ? time.atZone(ZoneId.systemDefault()).toInstant()
                : null;
    }

    // =====================================================================
    // 💰 ORDER API
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

    public OrderResult marketBuy(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId, symbol, "BUY", qty, BigDecimal.ZERO, "WEB_UI"
            );
            return new OrderResult(true, "BUY OK", order != null ? order.getId() : null);
        } catch (Exception e) {
            log.error("❌ marketBuy error", e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    public OrderResult marketSell(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId, symbol, "SELL", qty, BigDecimal.ZERO, "WEB_UI"
            );
            return new OrderResult(true, "SELL OK", order != null ? order.getId() : null);
        } catch (Exception e) {
            log.error("❌ marketSell error", e);
            return new OrderResult(false, e.getMessage(), null);
        }
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

        Long ms = tryLong(o, "getTimestampMs")
                .or(() -> tryLong(o, "getTimeMs"))
                .or(() -> tryLong(o, "getTs"))
                .or(() -> tryLong(o, "getTime"))
                .orElse(null);
        if (ms != null && ms > 0) return ms;

        Instant inst = tryInstant(o, "getCreatedAt")
                .or(() -> tryInstant(o, "getUpdatedAt"))
                .or(() -> tryInstant(o, "getExecutedAt"))
                .orElse(null);
        if (inst != null) return inst.toEpochMilli();

        LocalDateTime ldt = tryLocalDateTime(o, "getCreatedAt")
                .or(() -> tryLocalDateTime(o, "getUpdatedAt"))
                .orElse(null);
        if (ldt != null) return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        return null;
    }

    private Optional<Long> tryLong(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v == null) return Optional.empty();
            if (v instanceof Long l) return Optional.of(l);
            if (v instanceof Integer i) return Optional.of(i.longValue());
            if (v instanceof BigDecimal bd) return Optional.of(bd.longValue());
            if (v instanceof String s) return Optional.of(Long.parseLong(s.trim()));
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Instant> tryInstant(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v instanceof Instant inst) return Optional.of(inst);
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<LocalDateTime> tryLocalDateTime(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v instanceof LocalDateTime ldt) return Optional.of(ldt);
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    // =====================================================================
    // ✅ ТИПОБЕЗОПАСНЫЕ ХУКИ ДЛЯ РЫНКА
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
    // 🧩 small utils
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

    // =====================================================================
    // ✅ SAFE READ BOOL (settings)
    // =====================================================================

    private static Object readAnyNonNull(Object obj, String... gettersOrFields) {
        if (obj == null) return null;

        Class<?> c = obj.getClass();

        for (String n : gettersOrFields) {
            if (n == null || n.isBlank()) continue;

            try {
                Method m = findNoArgMethod(c, n);
                if (m != null) {
                    Object v = m.invoke(obj);
                    if (v != null) return v;
                }
            } catch (Exception ignore) {}

            try {
                var f = c.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return v;
            } catch (Exception ignore) {}
        }

        return null;
    }

    private static boolean readBool(Object obj, String... names) {
        Object v = readAnyNonNull(obj, names);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static Method findNoArgMethod(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (Exception ignored) {}
        String cap = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        try { return c.getMethod("get" + cap); } catch (Exception ignored) {}
        try { return c.getMethod("is" + cap); } catch (Exception ignored) {}
        return null;
    }
}
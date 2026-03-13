package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.MarketStreamService;
import com.chicu.aitradebot.market.model.UnifiedKline;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private final StrategySettingsService settingsService;
    private final StrategyRegistry strategyRegistry;
    private final MarketStreamService marketStreamService;
    private final MarketDataStreamService marketDataStreamService;

    /**
     * ML autotune runtime (оркестратор — главный lifecycle хаб)
     * ObjectProvider — защита от циклов и от временного отсутствия ML-слоя
     */
    private final ObjectProvider<MlAutoTuneRuntime> mlAutoTuneRuntime;

    @Value("${orch.market-events.listener-enabled:false}")
    private boolean eventBridgeEnabled;

    @Value("${orch.market-events.block-when-degraded:true}")
    private boolean blockWhenDegraded;

    @Value("${orch.market-events.degraded-log-cooldown-ms:30000}")
    private long degradedLogCooldownMs;

    private MlAutoTuneRuntime ml() {
        return mlAutoTuneRuntime != null ? mlAutoTuneRuntime.getIfAvailable() : null;
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

    private RuntimePolicy policyOf(StrategySettings s) {
        AdvancedControlMode m = (s != null && s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        String rp = sanitizePhase(s != null ? s.getRunPhase() : null);

        boolean autoTune = (s != null) && s.isAutoTuneEnabled();
        boolean gate = (s != null) && s.isMlGateEnabled();
        BigDecimal thr = (s != null) ? s.getGateMinProb() : null;
        String modelVer = (s != null) ? s.getMlModelVersion() : null;

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
            desiredAutoTune = false;
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
                settingsService.save(s);
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] applyRuntimePolicy save failed chatId={} type={} : {}", chatId, type, e.getMessage());
            }
        }

        BigDecimal effThr = desiredGateEnabled ? desiredGateMinProb : null;
        RuntimePolicy rp = new RuntimePolicy(
                mode,
                phase,
                desiredAutoTune,
                desiredGateEnabled,
                effThr,
                s.getMlModelVersion()
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
                (s.getMlModelVersion() != null ? s.getMlModelVersion() : "null")
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

            try {
                strategy.stop(chatId, current.symbol(), current.exchange(), current.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] stop(old) failed chatId={} type={} : {}", chatId, type, e.getMessage());
            }
            safeAutotuneStop(chatId, type, current.exchange(), current.network());

            running.put(key, desired);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            lastDegradedLogAtMs.remove(key);

            try {
                strategy.start(chatId, desired.symbol(), desired.exchange(), desired.network());

                marketStreamService.ensureSubscribed(
                        chatId,
                        type,
                        desired.symbol(),
                        desired.timeframe(),
                        desired.exchange(),
                        desired.network()
                );
            } catch (Exception e) {
                running.put(key, current);
                runtimePolicyCache.put(key, policyOf(s));

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
                settingsService.save(s);
            }

            applyRuntimePolicy(chatId, type, desired.exchange(), desired.network(), s);

            log.info("▶️ [ORCH] RUN {} chatId={} ex={} net={} symbol={} tf={} mode={}",
                    type, chatId, desired.exchange(), desired.network(), desired.symbol(), desired.timeframe(), safeMode(s));

            return buildRunInfoFromBinding(s, desired, true, "Контекст изменён — стратегия перезапущена");

        } finally {
            lock.unlock();
        }
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
                    settingsService.save(s);
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
            lastDegradedLogAtMs.remove(key);

            try {
                strategy.start(chatId, sym, ex, net);

                marketStreamService.ensureSubscribed(chatId, type, sym, tf, ex, net);
            } catch (Exception e) {
                running.remove(key, newBinding);
                runtimePolicyCache.remove(key);

                try {
                    marketStreamService.unsubscribe(chatId, type);
                } catch (Exception ignored) {
                }
                log.error("❌ [ORCH] startStrategy failed type={} chatId={} ex={} net={} sym={} tf={}",
                        type, chatId, ex, net, sym, tf, e);
                return buildRunInfo(s, false, "Ошибка запуска стратегии");
            }

            syncSettingsContextIfNeeded(s, ex, net);

            s.setActive(true);
            s.setStartedAt(LocalDateTime.now());
            s.setStoppedAt(null);
            settingsService.save(s);

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

            try {
                marketStreamService.unsubscribe(chatId, type);
            } catch (Exception ignored) {
            }

            RunBinding removed = running.remove(key);
            ignoreCounters.remove(key);
            lastTuneTriggerAtMs.remove(key);
            runtimePolicyCache.remove(key);
            lastDegradedLogAtMs.remove(key);

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
                logDegradedThrottled(key, sym, tf, health);
                return true;
            }
        } catch (Exception e) {
            log.debug("⚠ [ORCH] degraded check failed chatId={} type={} err={}",
                    key.chatId(), key.type(), e.toString());
        }

        return false;
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
                settingsService.save(s);
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
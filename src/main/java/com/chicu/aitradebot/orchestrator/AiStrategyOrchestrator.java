package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.ai.runtime.MlAutoTuneRuntime;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
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
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.math.BigDecimal;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final OrderService orderService;
    private final StrategySettingsService settingsService;
    private final StrategyRegistry strategyRegistry;

    // ✅ ML autotune runtime (оркестратор — главный lifecycle хаб)
    private final MlAutoTuneRuntime mlAutoTuneRuntime;

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4 initialized");
    }

    // =====================================================================
    // ✅ RUN CONTEXT (источник истины)
    // =====================================================================

    /**
     * Жёстко фиксируем один запуск на (chatId,type).
     * Если позже разрешишь несколько символов/сетей одновременно — поменяем ключ на:
     * (chatId,type,exchange,network,symbol,tf)
     */
    private record RunKey(long chatId, StrategyType type) {}

    public record RunBinding(
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe,
            Instant startedAt
    ) {}

    private final ConcurrentMap<RunKey, RunBinding> running = new ConcurrentHashMap<>();

    /**
     * Счётчик “игноров”, чтобы логировать предсказуемо (первые 3 + каждый 200-й).
     */
    private final ConcurrentMap<RunKey, AtomicLong> ignoreCounters = new ConcurrentHashMap<>();

    /**
     * Лёгкая защита от “слишком частых” ручных триггеров тюнинга с оркестратора.
     * (MlAutoTuneRuntime внутри тоже имеет свою защиту, но тут удобно отсечь шум раньше)
     */
    private final ConcurrentMap<RunKey, Long> lastTuneTriggerAtMs = new ConcurrentHashMap<>();

    public Optional<RunBinding> getBinding(long chatId, StrategyType type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(running.get(new RunKey(chatId, type)));
    }

    public boolean isRunning(long chatId, StrategyType type) {
        if (type == null) return false;
        return running.containsKey(new RunKey(chatId, type));
    }

    // =====================================================================
    // ▶️ START (КОНТЕКСТНЫЙ)
    // =====================================================================
    public StrategyRunInfo startStrategy(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);

        String sym = sanitizeSymbol(s.getSymbol());
        String tf  = sanitizeTf(s.getTimeframe());
        String ex  = sanitizeExchange(s.getExchangeName());
        NetworkType net = s.getNetworkType();

        if (sym == null) return buildRunInfo(s, false, "Ошибка: не выбран символ");
        if (ex == null || net == null) return buildRunInfo(s, false, "Ошибка: не выбрана биржа/сеть");

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return buildRunInfo(s, false, "Стратегия не найдена");

        RunKey key = new RunKey(chatId, type);
        RunBinding newBinding = new RunBinding(ex, net, sym, tf, Instant.now());

        // Если уже запущено — и это ТОТ ЖЕ binding -> ничего не делаем
        RunBinding existing = running.get(key);
        if (existing != null
            && eq(existing.exchange(), newBinding.exchange())
            && existing.network() == newBinding.network()
            && eq(existing.symbol(), newBinding.symbol())
            && eq(existing.timeframe(), newBinding.timeframe())) {

            log.info("⏭ [ORCH] Уже запущено: {} chatId={} ex={} net={} {} {}",
                    type, chatId, ex, net, sym, tf);

            // подчистим рассинхрон в БД, если нужно
            if (!s.isActive()) {
                s.setActive(true);
                s.setStartedAt(LocalDateTime.now());
                s.setStoppedAt(null);
                settingsService.save(s);
            }

            // ✅ гарантируем, что autotune jobs подняты (например после рестарта)
            safeAutotuneStart(chatId, type, ex, net);

            return buildRunInfo(s, true, "Стратегия уже запущена");
        }

        // Если запущено, но binding другой — жёстко останавливаем старый runtime (чтобы не было смешений)
        if (existing != null) {
            log.warn("⚠️ [ORCH] Перезапуск binding: {} chatId={} было ex={} net={} {} {} -> стало ex={} net={} {} {}",
                    type, chatId,
                    existing.exchange(), existing.network(), existing.symbol(), existing.timeframe(),
                    ex, net, sym, tf);

            try {
                strategy.stop(chatId, existing.symbol(), existing.exchange(), existing.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] Не удалось корректно остановить старый runtime перед перезапуском: {}", e.getMessage());
            }

            // ✅ старые джобы autotune гасим (и поднять заново)
            safeAutotuneStop(chatId, type, existing.exchange(), existing.network());
        }

        // ✅ фиксируем binding ДО старта — теперь любой левый тик/свеча будет отрезан
        running.put(key, newBinding);
        ignoreCounters.remove(key);

        try {
            strategy.start(chatId, sym, ex, net);
        } catch (Exception e) {
            running.remove(key, newBinding);
            log.error("❌ [ORCH] startStrategy failed type={} chatId={} ex={} net={} sym={} tf={}",
                    type, chatId, ex, net, sym, tf, e);
            return buildRunInfo(s, false, "Ошибка запуска стратегии");
        }

        // ✅ фиксируем реальный старт в БД
        s.setActive(true);
        s.setStartedAt(LocalDateTime.now());
        s.setStoppedAt(null);
        settingsService.save(s);

        log.info("▶️ [ORCH] START {} chatId={} ex={} net={} symbol={} tf={}",
                type, chatId, ex, net, sym, tf);

        // ✅ стартуем autotune (периодический + warmup)
        safeAutotuneStart(chatId, type, ex, net);

        return buildRunInfo(s, true, "Стратегия запущена");
    }

    // =====================================================================
    // ⏹ STOP (КОНТЕКСТНЫЙ)
    // =====================================================================
    public StrategyRunInfo stopStrategy(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);

        String sym = sanitizeSymbol(s.getSymbol());
        String ex  = sanitizeExchange(s.getExchangeName());
        NetworkType net = s.getNetworkType();

        TradingStrategy strategy = strategyRegistry.get(type);

        // ✅ сначала снимаем runtime binding
        RunKey key = new RunKey(chatId, type);
        RunBinding removed = running.remove(key);
        ignoreCounters.remove(key);
        lastTuneTriggerAtMs.remove(key);

        // ✅ и останавливаем стратегию в том контексте, который был в runtime (если он был)
        if (strategy != null) {
            try {
                if (removed != null) {
                    strategy.stop(chatId, removed.symbol(), removed.exchange(), removed.network());
                } else {
                    // fallback: по настройкам
                    strategy.stop(chatId, sym, ex, net);
                }
            } catch (Exception e) {
                log.error("❌ [ORCH] stopStrategy failed type={} chatId={} ex={} net={} sym={}",
                        type, chatId, ex, net, sym, e);
            }
        }

        // ✅ фиксируем остановку в БД
        s.setActive(false);
        s.setStoppedAt(LocalDateTime.now());
        settingsService.save(s);

        // ✅ гасим autotune jobs
        if (removed != null) {
            safeAutotuneStop(chatId, type, removed.exchange(), removed.network());
        } else {
            safeAutotuneStop(chatId, type, ex, net);
        }

        log.info("⏹ [ORCH] STOP {} chatId={} | bindingRemoved={}",
                type, chatId, removed != null);

        return buildRunInfo(s, false, "Стратегия остановлена");
    }

    // =====================================================================
    // ℹ STATUS (КОНТЕКСТНЫЙ)
    // =====================================================================
    public StrategyRunInfo getStatus(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        StrategySettings s = loadSettingsStrict(chatId, type, exchange, network);

        boolean active = isRunning(chatId, type);

        // ✅ если БД active=true, а runtime пустой — после рестарта НЕ угадываем.
        if (s.isActive() && !active) {
            s.setActive(false);
            if (s.getStoppedAt() == null) s.setStoppedAt(LocalDateTime.now());
            settingsService.save(s);
        }

        return buildRunInfo(
                s,
                active,
                active ? "Стратегия запущена" : "Стратегия остановлена"
        );
    }

    // =====================================================================
    // ✅ ML AUTO-TUNE HOOKS (жизненный цикл + триггеры)
    // =====================================================================

    private void safeAutotuneStart(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            if (chatId == null || type == null || exchange == null || network == null) return;
            mlAutoTuneRuntime.onStrategyStarted(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] autotune start failed: {}", e.getMessage());
        }
    }

    private void safeAutotuneStop(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            if (chatId == null || type == null || exchange == null || network == null) return;
            mlAutoTuneRuntime.onStrategyStopped(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] autotune stop failed: {}", e.getMessage());
        }
    }

    /**
     * ✅ ЭТО ДОЛЖЕН ВЫЗЫВАТЬ ТОРГОВЫЙ СЛОЙ, когда позиция РЕАЛЬНО закрылась (TP/SL/Manual close).
     * Тогда autotuner получит событие "after-close" и сможет обновить параметры.
     */
    public void onPositionClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            if (chatId == null || type == null || exchange == null || network == null) return;
            mlAutoTuneRuntime.onPositionClosed(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("🧠 [ORCH] onPositionClosed hook failed: {}", e.getMessage());
        }
    }

    /**
     * Универсальный ручной/автоматический триггер тюнинга с дебаунсом.
     */
    public void triggerTuneDebounced(Long chatId,
                                     StrategyType type,
                                     String exchange,
                                     NetworkType network,
                                     String reason,
                                     Duration debounce) {

        if (chatId == null || type == null || exchange == null || network == null) return;

        RunKey key = new RunKey(chatId, type);
        long now = System.currentTimeMillis();
        long d = Math.max(30_000, debounce != null ? debounce.toMillis() : 120_000);

        Long last = lastTuneTriggerAtMs.get(key);
        if (last != null && (now - last) < d) return;

        lastTuneTriggerAtMs.put(key, now);

        try {
            mlAutoTuneRuntime.triggerTuneDebounced(chatId, type, exchange, network,
                    reason != null ? reason : "orch-trigger", Duration.ofMillis(d));
        } catch (Exception e) {
            log.warn("🧠 [ORCH] triggerTuneDebounced failed: {}", e.getMessage());
        }
    }

    // =====================================================================
    // ✅ ВХОД ДЛЯ МАРКЕТ-СТРИМА (СТРОГИЙ КОНТЕКСТ)
    // =====================================================================

    /**
     * НОВЫЙ строгий метод: обязательно передавать exchange + network.
     * Любая несостыковка -> IGNORE (предсказуемый throttled-log).
     */
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

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);
        String ex  = sanitizeExchange(exchange);

        // ✅ строгая защита от смешений
        if (!eq(ex, b.exchange()) || network != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "TICK_IGNORED",
                    "пришло ex=" + ex + " net=" + network + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return;

        // ✅ 1) новый путь (если стратегия хочет получать exchange/network)
        if (strategy instanceof PriceUpdateAware aware) {
            try {
                aware.onPriceUpdate(chatId, type, sym, tf, price, tradeTsMs, b.exchange(), b.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] TICK_HANDLER_FAILED chatId={} type={} | {}",
                        chatId, type, e.getMessage());
            }
            return;
        }

        // ✅ 2) fallback путь: обычные стратегии, которые уже умеют onPriceUpdate(chatId, symbol, price, Instant)
        try {
            strategy.onPriceUpdate(chatId, sym, price, Instant.ofEpochMilli(tradeTsMs));
        } catch (Exception e) {
            log.warn("⚠️ [ORCH] TICK_HANDLER_FAILED_LEGACY chatId={} type={} | {}",
                    chatId, type, e.getMessage());
        }
    }

    /**
     * BACKWARD-COMPAT: старый вызов без exchange/network.
     */
    public void onPriceUpdate(long chatId,
                              StrategyType type,
                              String symbol,
                              String timeframe,
                              BigDecimal price,
                              long tradeTsMs) {

        if (type == null || price == null || price.signum() <= 0) return;

        RunBinding b = running.get(new RunKey(chatId, type));
        if (b == null) return;

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);

        if (!eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(new RunKey(chatId, type), "TICK_IGNORED_NOCTX",
                    "пришло " + sym + " " + tf + " | ожидаю " + b.symbol() + " " + b.timeframe());
            return;
        }

        onPriceUpdate(chatId, type, b.exchange(), b.network(), b.symbol(), b.timeframe(), price, tradeTsMs);
    }

    /**
     * ✅ БЕЗ РЕФЛЕКСИИ: стратегия должна реализовать CandleCloseAware.
     */
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

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);
        String ex  = sanitizeExchange(exchange);

        if (!eq(ex, b.exchange()) || network != b.network() || !eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(key, "CANDLE_IGNORED",
                    "пришло ex=" + ex + " net=" + network + " " + sym + " " + tf
                    + " | ожидаю ex=" + b.exchange() + " net=" + b.network() + " " + b.symbol() + " " + b.timeframe());
            return;
        }

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) return;

        if (strategy instanceof CandleCloseAware aware) {
            try {
                aware.onCandleClosed(chatId, type, sym, tf, kline, b.exchange(), b.network());
            } catch (Exception e) {
                log.warn("⚠️ [ORCH] CANDLE_HANDLER_FAILED chatId={} type={} | {}",
                        chatId, type, e.getMessage());
            }
            return;
        }

        logIgnore(key, "CANDLE_UNSUPPORTED",
                "стратегия " + strategy.getClass().getSimpleName()
                + " не реализует CandleCloseAware (свеча пропущена)");
    }

    /**
     * BACKWARD-COMPAT: старый вызов без exchange/network.
     */
    public void onCandleClosed(long chatId,
                               StrategyType type,
                               String symbol,
                               String timeframe,
                               UnifiedKline kline) {

        if (type == null || kline == null) return;

        RunBinding b = running.get(new RunKey(chatId, type));
        if (b == null) return;

        String sym = sanitizeSymbol(symbol);
        String tf  = sanitizeTf(timeframe);

        if (!eq(sym, b.symbol()) || !eq(tf, b.timeframe())) {
            logIgnore(new RunKey(chatId, type), "CANDLE_IGNORED_NOCTX",
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
                    code, key.chatId, key.type, n, details);
        }
    }

    // =====================================================================
    // 🌍 GLOBAL DASHBOARD
    // =====================================================================
    public record GlobalState(
            BigDecimal totalBalance,
            BigDecimal totalProfitPct,
            int activeStrategies
    ) {}

    public GlobalState getGlobalState(Long chatId) {
        int active = 0;

        for (StrategyType t : StrategyType.values()) {

            if (isActiveSafe(chatId, t, "BINANCE", NetworkType.MAINNET)) active++;
            if (isActiveSafe(chatId, t, "BINANCE", NetworkType.TESTNET)) active++;

            if (isActiveSafe(chatId, t, "BYBIT", NetworkType.MAINNET)) active++;
            if (isActiveSafe(chatId, t, "BYBIT", NetworkType.TESTNET)) active++;

            if (isActiveSafe(chatId, t, "OKX", NetworkType.MAINNET)) active++;
            if (isActiveSafe(chatId, t, "OKX", NetworkType.TESTNET)) active++;
        }

        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }

    private boolean isActiveSafe(Long chatId, StrategyType type, String exchange, NetworkType network) {
        try {
            StrategySettings s = settingsService.getSettings(chatId, type, exchange, network);
            return s != null && s.isActive();
        } catch (Exception ignored) {
            return false;
        }
    }

    // =====================================================================
    // 🔑 STRICT LOAD
    // =====================================================================
    private StrategySettings loadSettingsStrict(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        return settingsService.getOrCreate(chatId, type, exchange, network);
    }

    // =====================================================================
    // 🧱 RUN INFO (DTO)
    // =====================================================================
    private StrategyRunInfo buildRunInfo(StrategySettings s, boolean active, String msg) {

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
            return new OrderResult(true, "BUY OK", order.getId());
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
            return new OrderResult(true, "SELL OK", order.getId());
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

    /**
     * Здесь рефлексия не критична для торговли (только UI-время),
     * оставляем пока, чтобы не поломать совместимость с разными моделями Order.
     */
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
}

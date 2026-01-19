package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.stream.StreamConnectionManager;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final OrderService orderService;
    private final StrategySettingsService settingsService;
    private final StreamConnectionManager streamManager;
    private final StrategyRegistry strategyRegistry;

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4 initialized");
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

        if (s.getSymbol() == null || s.getSymbol().isBlank()) {
            return buildRunInfo(s, false, "Ошибка: не выбран символ");
        }

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) {
            return buildRunInfo(s, false, "Стратегия не найдена");
        }

        streamManager.subscribeSymbol(exchange, s.getSymbol());

        try {
            strategy.start(chatId, s.getSymbol());
        } catch (Exception e) {
            log.error("❌ startStrategy failed", e);
            return buildRunInfo(s, false, "Ошибка запуска стратегии");
        }

        // ✅ фиксируем реальный старт
        s.setActive(true);
        s.setStartedAt(LocalDateTime.now());
        s.setStoppedAt(null);
        settingsService.save(s);

        log.info("▶️ START {} chatId={} {} {}", type, chatId, exchange, s.getSymbol());
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
        TradingStrategy strategy = strategyRegistry.get(type);

        if (strategy != null) {
            try {
                strategy.stop(chatId, s.getSymbol());
            } catch (Exception e) {
                log.error("❌ stopStrategy failed", e);
            }
        }

        // ✅ фиксируем реальную остановку
        s.setActive(false);
        s.setStoppedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("⏹ STOP {} chatId={} {} {}", type, chatId, exchange, s.getSymbol());
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

        TradingStrategy strategy = strategyRegistry.get(type);

        // ✅ реальный runtime-статус, а не флаг из БД
        boolean runtimeActive = strategy != null && strategy.isActive(chatId);

        // самовосстановление рассинхрона после рестарта
        if (s.isActive() != runtimeActive) {
            s.setActive(runtimeActive);
            if (!runtimeActive && s.getStoppedAt() == null) {
                s.setStoppedAt(LocalDateTime.now());
            }
            settingsService.save(s);
        }

        return buildRunInfo(
                s,
                runtimeActive,
                runtimeActive ? "Стратегия запущена" : "Стратегия остановлена"
        );
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

        // ✅ больше НЕ используем findLatest(...). У нас есть строгий ключ.
        // Для глобального счётчика нам достаточно пройтись по сетям/биржам,
        // которые реально используются в UI/проекте.
        for (StrategyType t : StrategyType.values()) {

            // BINANCE MAINNET
            if (isActiveSafe(chatId, t, "BINANCE", NetworkType.MAINNET)) active++;

            // BINANCE TESTNET
            if (isActiveSafe(chatId, t, "BINANCE", NetworkType.TESTNET)) active++;

            // BYBIT MAINNET
            if (isActiveSafe(chatId, t, "BYBIT", NetworkType.MAINNET)) active++;

            // BYBIT TESTNET
            if (isActiveSafe(chatId, t, "BYBIT", NetworkType.TESTNET)) active++;

            // OKX MAINNET (если у тебя OKX без тестнета — оставь только MAINNET)
            if (isActiveSafe(chatId, t, "OKX", NetworkType.MAINNET)) active++;

            // OKX TESTNET (если есть — оставь; если нет — можно удалить)
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
        // ✅ раньше было findLatest(...) — теперь строго ключ.
        // И ВАЖНО: orchestrator должен уметь стартовать даже если строки ещё нет
        // (после твоего DROP таблицы это прям критично), поэтому getOrCreate().
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

                .riskPerTradePct(s.getRiskPerTradePct())
                .reinvestProfit(s.isReinvestProfit())
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
     * Никаких прямых вызовов deprecated getTimestamp().
     * Достаём время максимально совместимо: timestampMs/timeMs/createdAt/updatedAt и т.д.
     */
    private Long extractOrderTimestamp(Order o) {
        if (o == null) return null;

        // 1) millis
        Long ms = tryLong(o, "getTimestampMs")
                .or(() -> tryLong(o, "getTimeMs"))
                .or(() -> tryLong(o, "getTs"))
                .or(() -> tryLong(o, "getTime"))
                .orElse(null);
        if (ms != null && ms > 0) return ms;

        // 2) Instant / LocalDateTime
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

    private java.util.Optional<Long> tryLong(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v == null) return java.util.Optional.empty();
            if (v instanceof Long l) return java.util.Optional.of(l);
            if (v instanceof Integer i) return java.util.Optional.of(i.longValue());
            if (v instanceof BigDecimal bd) return java.util.Optional.of(bd.longValue());
            if (v instanceof String s) return java.util.Optional.of(Long.parseLong(s.trim()));
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<Instant> tryInstant(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v instanceof Instant inst) return java.util.Optional.of(inst);
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<LocalDateTime> tryLocalDateTime(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object v = m.invoke(target);
            if (v instanceof LocalDateTime ldt) return java.util.Optional.of(ldt);
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }
}

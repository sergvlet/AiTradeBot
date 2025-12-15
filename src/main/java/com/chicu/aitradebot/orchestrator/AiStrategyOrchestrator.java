package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.engine.StrategyEngine;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiStrategyOrchestrator {

    private final OrderService orderService;
    private final StrategyEngine strategyEngine;
    private final StrategySettingsService settingsService;
    private final StreamConnectionManager streamManager;

    // ✅ ОБЯЗАТЕЛЬНО: нужен для replayLayers
    private final StrategyRegistry strategyRegistry;

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4.5 initialized");
    }

    // =====================================================================
    // ▶️ START STRATEGY
    // =====================================================================

    public StrategyRunInfo startStrategy(Long chatId, StrategyType type) {
        StrategySettings s = settingsService.getOrCreate(chatId, type);

        String symbol    = s.getSymbol();
        String exchange  = s.getExchangeName();
        String timeframe = s.getTimeframe();
        int tick         = resolveTickIntervalSec(type);

        if (symbol == null || symbol.isBlank()) {
            log.error("❌ No symbol chatId={} type={}", chatId, type);
            return buildRunInfo(s, false, "Ошибка: не выбран символ");
        }

        if (exchange == null || exchange.isBlank()) {
            log.error("❌ No exchange chatId={} type={}", chatId, type);
            return buildRunInfo(s, false, "Ошибка: не выбрана биржа");
        }

        streamManager.subscribeSymbol(exchange, symbol);
        strategyEngine.start(chatId, type, symbol, tick);

        if (!strategyEngine.isRunning(chatId, type)) {
            log.error("❌ Strategy {} not started chatId={}", type, chatId);
            return buildRunInfo(s, false, "Ошибка запуска стратегии");
        }

        s.setActive(true);
        s.setUpdatedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("▶️ START {} chatId={} {} {}", type, chatId, exchange, symbol);

        return buildRunInfo(s, true, "Стратегия запущена");
    }

    // =====================================================================
    // ⏹ STOP STRATEGY
    // =====================================================================

    public StrategyRunInfo stopStrategy(Long chatId, StrategyType type) {
        strategyEngine.stop(chatId, type);

        StrategySettings s = settingsService.getOrCreate(chatId, type);
        s.setActive(false);
        s.setUpdatedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("⏹ STOP {} chatId={}", type, chatId);

        return buildRunInfo(s, false, "Стратегия остановлена");
    }

    // =====================================================================
    // 📋 STRATEGY LIST
    // =====================================================================

    public record StrategyInfo(StrategyType type, boolean active) {}

    public List<StrategyInfo> getStrategies(Long chatId) {
        Set<StrategyType> running = strategyEngine.getRunningStrategies(chatId);

        List<StrategyInfo> list = new ArrayList<>();
        for (StrategyType t : StrategyType.values()) {
            list.add(new StrategyInfo(t, running.contains(t)));
        }
        return list;
    }

    // =====================================================================
    // 💰 ORDERS
    // =====================================================================

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

    public List<OrderView> listOrders(Long chatId, String symbol) {
        try {
            List<Order> orders = orderService.getOrdersByChatIdAndSymbol(chatId, symbol);

            return orders.stream()
                    .map(o -> new OrderView(
                            o.getId(),
                            o.getSymbol(),
                            o.getSide(),
                            o.getStatus(),
                            o.getPrice(),
                            o.getQuantity(),
                            o.isFilled(),
                            o.getTimestamp()
                    ))
                    .toList();

        } catch (Exception e) {
            log.error("❌ listOrders error", e);
            return List.of();
        }
    }

    // =====================================================================
    // 🔁 REPLAY STRATEGY LAYERS (🔥 ДЛЯ ГРАФИКА)
    // =====================================================================

    public void replayStrategyLayers(Long chatId, StrategyType type) {
        TradingStrategy strategy;
        try {
            strategy = strategyRegistry.get(type);
        } catch (Exception e) {
            // если StrategyRegistry внутри кидает исключение — не даём 500
            log.error("❌ replayLayers: StrategyRegistry.get failed type={} chatId={}", type, chatId, e);
            return;
        }

        if (strategy == null) {
            log.warn("⚠ replayLayers: strategy not found type={} chatId={}", type, chatId);
            return;
        }

        log.info("🔁 replayLayers START type={} chatId={} strategyClass={}", type, chatId, strategy.getClass().getName());

        try {
            strategy.replayLayers(chatId);
            log.info("✅ replayLayers OK type={} chatId={}", type, chatId);
        } catch (Exception e) {
            // ВОТ ЭТО и было причиной 500: исключение улетало в контроллер
            log.error("❌ replayLayers FAILED type={} chatId={} strategyClass={}",
                    type, chatId, strategy.getClass().getName(), e);
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private int resolveTickIntervalSec(StrategyType type) {
        return switch (type) {
            case SMART_FUSION -> 10;
            case SCALPING -> 3;
            case FIBONACCI_GRID -> 15;
            case RSI_EMA -> 5;
            case ML_INVEST -> 30;
            default -> 10;
        };
    }

    private StrategyRunInfo buildRunInfo(StrategySettings s, boolean active, String msg) {
        return StrategyRunInfo.builder()
                .chatId(s.getChatId())
                .type(s.getType())
                .symbol(s.getSymbol())
                .active(active)
                .timeframe(s.getTimeframe())
                .exchangeName(s.getExchangeName())
                .networkType(s.getNetworkType())
                .capitalUsd(s.getCapitalUsd())
                .equityUsd(s.getCapitalUsd())
                .totalProfitPct(s.getTotalProfitPct())
                .commissionPct(s.getCommissionPct())
                .takeProfitPct(s.getTakeProfitPct())
                .stopLossPct(s.getStopLossPct())
                .riskPerTradePct(s.getRiskPerTradePct())
                .mlConfidence(s.getMlConfidence())
                .reinvestProfit(s.isReinvestProfit())
                .totalTrades(0L)
                .startedAt(active ? Instant.now() : null)
                .stoppedAt(active ? null : Instant.now())
                .message(msg)
                .build();
    }

    // =====================================================================
    // ❓ STATUS (для Dashboard)
    // =====================================================================

    public StrategyRunInfo getStatus(Long chatId, StrategyType type) {
        StrategySettings s = settingsService.getOrCreate(chatId, type);
        boolean active = strategyEngine.isRunning(chatId, type);

        return buildRunInfo(
                s,
                active,
                active ? "Стратегия запущена" : "Стратегия остановлена"
        );
    }

    // =====================================================================
    // GLOBAL
    // =====================================================================

    public record GlobalState(BigDecimal totalBalance, BigDecimal totalProfitPct, int activeStrategies) {}

    public GlobalState getGlobalState(Long chatId) {
        int active = strategyEngine.getRunningStrategies(chatId).size();
        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }

    // =====================================================================
    // 💰 ORDER MANAGEMENT (for Web UI)
    // =====================================================================

    public record OrderResult(boolean success, String message, Long orderId) {}

    public OrderResult marketBuy(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId,
                    symbol,
                    "BUY",
                    qty,
                    BigDecimal.ZERO,
                    "WEB_UI"
            );
            return new OrderResult(true, "BUY OK", order.getId());
        } catch (Exception e) {
            log.error("❌ marketBuy error: {}", e.getMessage(), e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    public OrderResult marketSell(Long chatId, String symbol, BigDecimal qty) {
        try {
            Order order = orderService.placeMarket(
                    chatId,
                    symbol,
                    "SELL",
                    qty,
                    BigDecimal.ZERO,
                    "WEB_UI"
            );
            return new OrderResult(true, "SELL OK", order.getId());
        } catch (Exception e) {
            log.error("❌ marketSell error: {}", e.getMessage(), e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    public boolean cancelOrder(Long chatId, long orderId) {
        try {
            return orderService.cancelOrder(chatId, orderId);
        } catch (Exception e) {
            log.error("❌ cancelOrder error: {}", e.getMessage(), e);
            return false;
        }
    }
}

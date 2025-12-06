package com.chicu.aitradebot.orchestrator;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.engine.StrategyEngine;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.stream.StreamConnectionManager;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
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

    @PostConstruct
    public void init() {
        log.info("🧠 AiStrategyOrchestrator v4.5 initialized (dynamic WS subscription, multi-exchange).");
    }

    // =====================================================================
    // ▶️ START STRATEGY
    // =====================================================================

    public StrategyRunInfo startStrategy(Long chatId, StrategyType type) {
        StrategySettings s = settingsService.getOrCreate(chatId, type);

        String symbol   = s.getSymbol();
        String exchange = s.getExchangeName();
        String timeframe = s.getTimeframe();
        int tick = resolveTickIntervalSec(type);

        if (symbol == null || symbol.isBlank()) {
            log.error("❌ Нельзя запустить стратегию без символа! chatId={} type={}", chatId, type);
            return buildRunInfo(s, false, "Ошибка: не выбран символ");
        }

        // 🔥 Подписываем только нужную биржу
        streamManager.subscribeSymbol(symbol, exchange);

        strategyEngine.start(chatId, type, symbol, tick);

        s.setActive(true);
        s.setUpdatedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("▶️ START {} | chatId={} exchange={} symbol={} tf={} tick={}s",
                type, chatId, exchange, symbol, timeframe, tick);

        return buildRunInfo(s, true, "Стратегия запущена");
    }

    public StrategyRunInfo startStrategy(Long chatId,
                                         StrategyType type,
                                         String symbol,
                                         int tick) {

        StrategySettings s = settingsService.getOrCreate(chatId, type);
        String exchange = s.getExchangeName();

        if (symbol == null || symbol.isBlank()) {
            log.error("❌ Пустой символ при ручном запуске!");
            return buildRunInfo(s, false, "Ошибка: символ пустой");
        }

        streamManager.subscribeSymbol(symbol, exchange);

        strategyEngine.start(chatId, type, symbol, tick);

        s.setSymbol(symbol);
        s.setActive(true);
        s.setUpdatedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("▶️ START {} (manual) | chatId={} exchange={} symbol={} tick={}",
                type, chatId, exchange, symbol, tick);

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

        log.info("⏹ STOP {} | chatId={}", type, chatId);

        return buildRunInfo(s, false, "Стратегия остановлена");
    }

    // =====================================================================
    // ❓ STATUS
    // =====================================================================

    public boolean isActive(Long chatId, StrategyType type) {
        return strategyEngine.isRunning(chatId, type);
    }

    // =====================================================================
    // 📋 STRATEGY LIST (UI)
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
    // 💰 ORDER MANAGEMENT
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
            log.error("❌ listOrders error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public OrderResult marketBuy(Long chatId, String symbol, BigDecimal qty) {
        try {
            var order = orderService.placeMarket(chatId, symbol, "BUY", qty, BigDecimal.ZERO, "ORCHESTRATOR");
            return new OrderResult(true, "BUY OK", order.getId());
        } catch (Exception e) {
            log.error("❌ marketBuy error: {}", e.getMessage(), e);
            return new OrderResult(false, e.getMessage(), null);
        }
    }

    public OrderResult marketSell(Long chatId, String symbol, BigDecimal qty) {
        try {
            var order = orderService.placeMarket(chatId, symbol, "SELL", qty, BigDecimal.ZERO, "ORCHESTRATOR");
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

    // =====================================================================
    // GLOBAL
    // =====================================================================

    public record GlobalState(BigDecimal totalBalance, BigDecimal totalProfitPct, int activeStrategies) {}

    public GlobalState getGlobalState(Long chatId) {
        int active = strategyEngine.getRunningStrategies(chatId).size();
        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }

    // =====================================================================
    // BALANCE (заглушка пока)
    // =====================================================================

    public record BalanceView(BigDecimal total, BigDecimal free, BigDecimal locked) {}

    public BalanceView getBalance(Long chatId) {
        return new BalanceView(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public record AssetBalanceView(String asset, BigDecimal free, BigDecimal locked) {}

    public List<AssetBalanceView> getAssets(Long chatId) {
        return List.of(
                new AssetBalanceView("USDT", BigDecimal.ZERO, BigDecimal.ZERO),
                new AssetBalanceView("BTC", BigDecimal.ZERO, BigDecimal.ZERO)
        );
    }

    // =====================================================================
    // 🚀 BUILD StrategyRunInfo
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
}

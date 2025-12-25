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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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
    // ▶️ START STRATEGY
    // =====================================================================

    public StrategyRunInfo startStrategy(Long chatId, StrategyType type) {

        StrategySettings s = loadSettings(chatId, type);

        String symbol = s.getSymbol();
        String exchange = s.getExchangeName();

        if (symbol == null || symbol.isBlank()) {
            return buildRunInfo(s, false, "Ошибка: не выбран символ");
        }
        if (exchange == null || exchange.isBlank()) {
            return buildRunInfo(s, false, "Ошибка: не выбрана биржа");
        }

        TradingStrategy strategy = strategyRegistry.get(type);
        if (strategy == null) {
            return buildRunInfo(s, false, "Стратегия не найдена");
        }

        streamManager.subscribeSymbol(exchange, symbol);

        try {
            strategy.start(chatId, symbol);
        } catch (Exception e) {
            log.error("❌ startStrategy failed", e);
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

        StrategySettings s = loadSettings(chatId, type);
        TradingStrategy strategy = strategyRegistry.get(type);

        if (strategy != null) {
            try {
                strategy.stop(chatId, s.getSymbol());
            } catch (Exception e) {
                log.error("❌ stopStrategy failed", e);
            }
        }

        s.setActive(false);
        s.setUpdatedAt(LocalDateTime.now());
        settingsService.save(s);

        log.info("⏹ STOP {} chatId={}", type, chatId);
        return buildRunInfo(s, false, "Стратегия остановлена");
    }

    // =====================================================================
    // ❓ STATUS
    // =====================================================================

    public StrategyRunInfo getStatus(Long chatId, StrategyType type) {

        StrategySettings s = loadSettings(chatId, type);

        return buildRunInfo(
                s,
                s.isActive(),
                s.isActive() ? "Стратегия запущена" : "Стратегия остановлена"
        );
    }

    // =====================================================================
    // 🌍 GLOBAL DASHBOARD STATE
    // =====================================================================

    public record GlobalState(
            BigDecimal totalBalance,
            BigDecimal totalProfitPct,
            int activeStrategies
    ) {}

    public GlobalState getGlobalState(Long chatId) {
        int active = 0;

        for (StrategyType t : StrategyType.values()) {
            StrategySettings s = loadSettings(chatId, t);
            if (s.isActive()) {
                active++;
            }
        }

        return new GlobalState(BigDecimal.ZERO, BigDecimal.ZERO, active);
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private StrategyRunInfo buildRunInfo(StrategySettings s, boolean active, String msg) {

        return StrategyRunInfo.builder()
                // === ИДЕНТИФИКАЦИЯ ===
                .chatId(s.getChatId())
                .type(s.getType())
                .symbol(s.getSymbol())
                .active(active)

                // === МАРКЕТ ===
                .timeframe(s.getTimeframe())
                .exchangeName(s.getExchangeName())
                .networkType(s.getNetworkType())

                // === ФИНАНСЫ / РИСК ===
                .capitalUsd(s.getCapitalUsd())
                .totalProfitPct(s.getTotalProfitPct())
                .commissionPct(s.getCommissionPct())
                .takeProfitPct(s.getTakeProfitPct())
                .stopLossPct(s.getStopLossPct())
                .riskPerTradePct(s.getRiskPerTradePct())
                .mlConfidence(s.getMlConfidence())

                // === СЛУЖЕБНОЕ ===
                .reinvestProfit(s.isReinvestProfit())
                .version(s.getVersion())

                // === ВРЕМЯ ===
                .startedAt(active ? Instant.now() : null)
                .stoppedAt(active ? null : Instant.now())

                // === СТАТУС ===
                .message(msg)

                .build();
    }

    // =====================================================================
    // 💰 ORDER MANAGEMENT (Web UI)
    // =====================================================================

    public record OrderResult(
            boolean success,
            String message,
            Long orderId
    ) {}

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
                    chatId,
                    symbol,
                    "BUY",
                    qty,
                    BigDecimal.ZERO,
                    "WEB_UI"
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
                    chatId,
                    symbol,
                    "SELL",
                    qty,
                    BigDecimal.ZERO,
                    "WEB_UI"
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
                            o.getTimestamp()
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("❌ listOrders error", e);
            return List.of();
        }
    }

    // =====================================================================
    // 🔑 CORE: загрузка настроек с учётом exchange + network
    // =====================================================================

    private StrategySettings loadSettings(Long chatId, StrategyType type) {

        // 1️⃣ Берём последнюю активную связку exchange+network из БД
        StrategySettings base =
                settingsService
                        .findAllByChatId(chatId, null, null)
                        .stream()
                        .filter(s -> s.getType() == type)
                        .findFirst()
                        .orElse(null);

        String exchange =
                base != null && base.getExchangeName() != null
                        ? base.getExchangeName()
                        : "BINANCE";

        NetworkType network =
                base != null && base.getNetworkType() != null
                        ? base.getNetworkType()
                        : NetworkType.TESTNET;

        return settingsService.getOrCreate(chatId, type, exchange, network);
    }
}

package com.chicu.aitradebot.strategy.smartfusion.components;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Исполнитель ордеров SmartFusion — без заглушек.
 * Работает через ExchangeClientFactory и реальные ключи из ExchangeSettingsService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartFusionOrderExecutor {

    private final ExchangeSettingsService exchangeSettingsService;
    private final ExchangeClientFactory exchangeClientFactory;

    /** Память последних сделок, чтобы быстро отдать на график */
    private final Deque<Order> recentTrades = new ArrayDeque<>(200);

    /**
     * Маркет ордер (BUY/SELL).
     *
     * @param chatId пользователь
     * @param symbol пара, например BTCUSDT
     * @param side   "BUY" или "SELL"
     * @param qty    количество (в базовой валюте)
     */
    public void placeMarket(long chatId, String symbol, String side, double qty) throws Exception {
        // 1) Берём активные настройки для этой биржи
        ExchangeSettings settings = exchangeSettingsService
                .findAllByChatId(chatId).stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(s -> "BINANCE".equalsIgnoreCase(s.getExchange()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найдены активные ключи BINANCE для chatId=" + chatId));

        // 2) Получаем подходящий клиент
        ExchangeClient client = exchangeClientFactory.getByChat(chatId);

        // 3) Размещаем РЕАЛЬНЫЙ ордер
        ExchangeClient.OrderResult result =
                client.placeOrder(chatId, symbol, side, "MARKET", qty, null);

        // 4) Маппим в нашу сущность Order
        Order order = Order.builder()
                .orderId(result.orderId())
                .symbol(result.symbol())
                .side(result.side())
                .type(result.type())
                .qty(BigDecimal.valueOf(result.qty()))           // <— используем qty как исполненное количество
                .price(BigDecimal.valueOf(result.price()))
                .status(result.status())
                .timestamp(result.timestamp())
                .filled("FILLED".equalsIgnoreCase(result.status()))
                .build();

        // 5) Кладём в локальную историю (для графика/дашборда)
        pushRecent(order);

        log.info("✅ MARKET {} {} qty={} @{} [{}] (chatId={})",
                order.getSide(), order.getSymbol(), order.getQty(), order.getPrice(),
                order.getStatus(), chatId);

    }

    /**
     * Лимитный ордер.
     */
    public Order placeLimit(long chatId, String symbol, String side, double qty, double price) throws Exception {
        ExchangeSettings settings = exchangeSettingsService
                .findAllByChatId(chatId).stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(s -> "BINANCE".equalsIgnoreCase(s.getExchange()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найдены активные ключи BINANCE для chatId=" + chatId));

        ExchangeClient client = exchangeClientFactory.getByChat(chatId);

        ExchangeClient.OrderResult result =
                client.placeOrder(chatId, symbol, side, "LIMIT", qty, price);

        Order order = Order.builder()
                .orderId(result.orderId())
                .symbol(result.symbol())
                .side(result.side())
                .type(result.type())
                .qty(BigDecimal.valueOf(result.qty()))
                .price(BigDecimal.valueOf(result.price()))
                .status(result.status())
                .timestamp(result.timestamp())
                .filled("FILLED".equalsIgnoreCase(result.status()))
                .build();

        pushRecent(order);

        log.info("✅ LIMIT {} {} qty={} @{} [{}] (chatId={})",
                order.getSide(), order.getSymbol(), order.getQty(), order.getPrice(),
                order.getStatus(), chatId);

        return order;
    }

    /**
     * Отмена ордера.
     */
    public boolean cancel(long chatId, String symbol, String orderId) throws Exception {
        ExchangeSettings settings = exchangeSettingsService
                .findAllByChatId(chatId).stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(s -> "BINANCE".equalsIgnoreCase(s.getExchange()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найдены активные ключи BINANCE для chatId=" + chatId));

        ExchangeClient client = exchangeClientFactory.getByChat(chatId);
        boolean ok = client.cancelOrder(chatId, symbol, orderId);
        log.info("🛑 Cancel {} {} -> {}", symbol, orderId, ok ? "OK" : "FAIL");
        return ok;
    }

    /**
     * Последние сделки для дашборда (реальные, из памяти процесса).
     */
    public List<Order> getRecentTrades(long chatId, String symbol) {
        // Возвращаем копию, отфильтрованную по символу (chatId тут не проверяем, т.к. ордера уже «его»)
        List<Order> list = new ArrayList<>();
        for (Order o : recentTrades) {
            if (symbol.equalsIgnoreCase(o.getSymbol())) {
                list.add(o);
            }
        }
        return list;
    }

    // ===== внутреннее =====
    private void pushRecent(Order order) {
        if (recentTrades.size() >= 200) {
            recentTrades.pollFirst();
        }
        recentTrades.addLast(order);
    }
    /**
     * Универсальный метод, совместимый с вызовами из SmartFusionStrategy.
     */
    public void placeMarketOrder(
            long chatId,
            String symbol,
            NetworkType network,
            String exchange,
            OrderSide side,
            BigDecimal qty
    ) throws Exception {
        log.debug("⚙️ placeMarketOrder({}, {}, {}, {}, {}, {})",
                chatId, symbol, network, exchange, side, qty);

        // Просто делегируем в placeMarket(...)
        placeMarket(chatId, symbol, side.name(), qty.doubleValue());
    }

}

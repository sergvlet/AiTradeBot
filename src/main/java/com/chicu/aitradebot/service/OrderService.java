package com.chicu.aitradebot.service;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.model.Order;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {

    // ====== MARKET ======
    Order placeMarket(Long chatId,
                      String symbol,
                      String side,
                      BigDecimal quantity,
                      BigDecimal executionPrice,
                      String strategyType);

    // ====== LIMIT ======
    Order placeLimit(Long chatId,
                     String symbol,
                     String side,
                     BigDecimal quantity,
                     BigDecimal limitPrice,
                     String timeInForce,
                     String strategyType);

    // ====== OCO ======
    Order placeOco(Long chatId,
                   String symbol,
                   BigDecimal quantity,
                   BigDecimal takeProfitPrice,
                   BigDecimal stopPrice,
                   BigDecimal stopLimitPrice,
                   String strategyType);

    // ====== CANCEL ======
    boolean cancelOrder(Long chatId, Long orderId);

    int cancelAllOpen(Long chatId, String symbol);

    // ====== OPEN ORDERS ======
    List<Order> getOpenOrders(Long chatId, String symbol);

    // ====== CREATE (generic) ======
    Order createOrder(Order order);

    // ====== ИСТОРИЯ ДЛЯ СТРАТЕГИЙ (DTO) ======
    /**
     * Старый метод: возвращает DTO-ордеры (exchange.model.Order),
     * используется стратегиями / логикой торговли.
     */
    List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol);

    // ====== ИСТОРИЯ ДЛЯ ДАШБОРДА / ГРАФИКА (ENTITY) ======
    /**
     * Новый метод: возвращает JPA-сущности OrderEntity
     * для ULTRA-графика (PNL, TP/SL, причины входа/выхода, ML и т.д.)
     */
    List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol);
}

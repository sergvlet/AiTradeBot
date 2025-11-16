package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    // =====================================================================
    // MARKET
    // =====================================================================
    @Override
    public Order placeMarket(Long chatId, String symbol, String side, BigDecimal quantity,
                             BigDecimal executionPrice, String strategyType) {

        OrderEntity e = new OrderEntity();
        e.setChatId(chatId);
        e.setUserId(chatId);
        e.setSymbol(symbol);
        e.setSide(side);
        e.setPrice(executionPrice);
        e.setQuantity(quantity);
        e.setTotal(executionPrice.multiply(quantity));
        e.setStrategyType(strategyType);
        e.setStatus("FILLED");
        e.setFilled(true);
        e.setTimestamp(System.currentTimeMillis());
        e.setCreatedAt(LocalDateTime.now());
        orderRepository.save(e);

        Order order = new Order();
        order.setId(e.getId());
        order.setChatId(chatId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setPrice(executionPrice);
        order.setQuantity(quantity);
        order.setStatus("FILLED");
        order.setTime(e.getTimestamp());
        return order;
    }

    // =====================================================================
    // LIMIT
    // =====================================================================
    @Override
    public Order placeLimit(Long chatId, String symbol, String side, BigDecimal quantity,
                            BigDecimal limitPrice, String timeInForce, String strategyType) {

        OrderEntity e = new OrderEntity();
        e.setChatId(chatId);
        e.setUserId(chatId);
        e.setSymbol(symbol);
        e.setSide(side);
        e.setPrice(limitPrice);
        e.setQuantity(quantity);
        e.setTotal(limitPrice.multiply(quantity));
        e.setStatus("OPEN");
        e.setFilled(false);
        e.setStrategyType(strategyType);
        e.setTimestamp(System.currentTimeMillis());
        e.setCreatedAt(LocalDateTime.now());
        orderRepository.save(e);

        Order order = new Order();
        order.setId(e.getId());
        order.setChatId(chatId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setPrice(limitPrice);
        order.setQuantity(quantity);
        order.setStatus("OPEN");
        order.setTime(e.getTimestamp());

        return order;
    }

    // =====================================================================
    // OCO
    // =====================================================================
    @Override
    public Order placeOco(Long chatId, String symbol, BigDecimal quantity,
                          BigDecimal takeProfitPrice, BigDecimal stopPrice,
                          BigDecimal stopLimitPrice, String strategyType) {

        OrderEntity e = new OrderEntity();
        e.setChatId(chatId);
        e.setUserId(chatId);
        e.setSymbol(symbol);
        e.setSide("SELL");
        e.setQuantity(quantity);
        e.setTakeProfitPrice(takeProfitPrice);
        e.setStopLossPrice(stopPrice);
        e.setStrategyType(strategyType);
        e.setStatus("OPEN");
        e.setFilled(false);
        e.setTimestamp(System.currentTimeMillis());
        e.setCreatedAt(LocalDateTime.now());
        orderRepository.save(e);

        Order order = new Order();
        order.setId(e.getId());
        order.setChatId(chatId);
        order.setSymbol(symbol);
        order.setSide("SELL");
        order.setQuantity(quantity);
        order.setStatus("OPEN");
        order.setTime(e.getTimestamp());

        return order;
    }

    // =====================================================================
    @Override
    public boolean cancelOrder(Long chatId, Long orderId) {
        if (!orderRepository.existsById(orderId)) return false;
        var e = orderRepository.findById(orderId).orElse(null);
        if (e == null) return false;
        e.setStatus("CANCELED");
        e.setFilled(false);
        e.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(e);
        return true;
    }

    @Override
    public int cancelAllOpen(Long chatId, String symbol) {
        List<OrderEntity> open = orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
        int count = 0;
        for (OrderEntity e : open) {
            if ("OPEN".equals(e.getStatus())) {
                e.setStatus("CANCELED");
                e.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(e);
                count++;
            }
        }
        return count;
    }

    // =====================================================================
    // OPEN ORDERS (DTO)
    // =====================================================================
    @Override
    public List<Order> getOpenOrders(Long chatId, String symbol) {
        return getOrdersByChatIdAndSymbol(chatId, symbol)
                .stream()
                .filter(o -> "OPEN".equals(o.getStatus()))
                .toList();
    }

    // =====================================================================
    // CREATE (generic)
    // =====================================================================
    @Override
    public Order createOrder(Order order) {
        OrderEntity e = new OrderEntity();
        e.setChatId(order.getChatId());
        e.setSymbol(order.getSymbol());
        e.setSide(order.getSide());
        e.setPrice(order.getPrice());
        e.setQuantity(order.getQuantity());
        e.setTotal(order.getPrice().multiply(order.getQuantity()));
        e.setFilled("FILLED".equals(order.getStatus()));
        e.setStatus(order.getStatus());
        e.setStrategyType(String.valueOf(order.getStrategyType()));
        e.setTimestamp(order.getTime());
        e.setCreatedAt(LocalDateTime.now());
        orderRepository.save(e);

        order.setId(e.getId());
        return order;
    }

    // =====================================================================
    // DTO (exchange.model.Order)
    // =====================================================================
    @Override
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol)
                .stream()
                .map(e -> {
                    Order o = new Order();
                    o.setId(e.getId());
                    o.setChatId(e.getChatId());
                    o.setSymbol(e.getSymbol());
                    o.setSide(e.getSide());
                    o.setPrice(e.getPrice());
                    o.setQuantity(e.getQuantity());
                    o.setStatus(e.getStatus());
                    o.setTime(e.getTimestamp());
                    return o;
                })
                .toList();
    }

    // =====================================================================
    // ENTITY (для графика)
    // =====================================================================
    @Override
    public List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
    }
}

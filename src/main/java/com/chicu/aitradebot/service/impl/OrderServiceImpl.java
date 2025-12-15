package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    // ==========================
    // MARKET
    // ==========================
    @Override
    @Transactional
    public Order placeMarket(Long chatId,
                             String symbol,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice,
                             String strategyType) {

        log.info("📥 [MARKET] chatId={}, symbol={}, side={}, qty={}, price={}, strategy={}",
                chatId, symbol, side, quantity, executionPrice, strategyType);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId); // ✅ ИСПРАВЛЕНИЕ

        entity.setSymbol(symbol);
        entity.setSide(side);
        entity.setPrice(executionPrice);
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType);
        entity.setStatus("FILLED");
        entity.setFilled(true);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (executionPrice != null && quantity != null) {
            entity.setTotal(executionPrice.multiply(quantity));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // ==========================
    // LIMIT
    // ==========================
    @Override
    @Transactional
    public Order placeLimit(Long chatId,
                            String symbol,
                            String side,
                            BigDecimal quantity,
                            BigDecimal limitPrice,
                            String timeInForce,
                            String strategyType) {

        log.info("📥 [LIMIT] chatId={}, symbol={}, side={}, qty={}, limitPrice={}, tif={}, strategy={}",
                chatId, symbol, side, quantity, limitPrice, timeInForce, strategyType);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId); // ✅

        entity.setSymbol(symbol);
        entity.setSide(side);
        entity.setPrice(limitPrice);
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType);
        entity.setStatus("NEW");
        entity.setFilled(false);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (limitPrice != null && quantity != null) {
            entity.setTotal(limitPrice.multiply(quantity));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // ==========================
    // OCO
    // ==========================
    @Override
    @Transactional
    public Order placeOco(Long chatId,
                          String symbol,
                          BigDecimal quantity,
                          BigDecimal takeProfitPrice,
                          BigDecimal stopPrice,
                          BigDecimal stopLimitPrice,
                          String strategyType) {

        log.info("📥 [OCO] chatId={}, symbol={}, qty={}, tp={}, stop={}, stopLimit={}, strategy={}",
                chatId, symbol, quantity, takeProfitPrice, stopPrice, stopLimitPrice, strategyType);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId); // ✅

        entity.setSymbol(symbol);
        entity.setSide("SELL");
        entity.setPrice(takeProfitPrice != null ? takeProfitPrice : stopLimitPrice);
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType);
        entity.setStatus("NEW");
        entity.setFilled(false);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setStopLossPrice(stopLimitPrice != null ? stopLimitPrice : stopPrice);

        if (entity.getPrice() != null && quantity != null) {
            entity.setTotal(entity.getPrice().multiply(quantity));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // ==========================
    // CANCEL
    // ==========================
    @Override
    @Transactional
    public boolean cancelOrder(Long chatId, Long orderId) {
        return orderRepository.findById(orderId)
                .filter(o -> chatId != null && chatId.equals(o.getChatId()))
                .map(o -> {
                    log.info("❌ [CANCEL] chatId={}, orderId={}", chatId, orderId);
                    o.setStatus("CANCELED");
                    o.setFilled(false);
                    o.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(o);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public int cancelAllOpen(Long chatId, String symbol) {
        List<String> openStatuses = Arrays.asList("NEW", "OPEN", "PARTIALLY_FILLED");

        List<OrderEntity> openOrders =
                orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, symbol, openStatuses);

        openOrders.forEach(o -> {
            o.setStatus("CANCELED");
            o.setFilled(false);
            o.setUpdatedAt(LocalDateTime.now());
        });

        orderRepository.saveAll(openOrders);

        log.info("❌ [CANCEL_ALL] chatId={}, symbol={}, count={}", chatId, symbol, openOrders.size());
        return openOrders.size();
    }

    // ==========================
    // OPEN ORDERS
    // ==========================
    @Override
    @Transactional(readOnly = true)
    public List<Order> getOpenOrders(Long chatId, String symbol) {
        List<String> openStatuses = Arrays.asList("NEW", "OPEN", "PARTIALLY_FILLED");

        List<OrderEntity> openOrders =
                orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, symbol, openStatuses);

        return openOrders.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ==========================
    // CREATE (generic)
    // ==========================
    @Override
    @Transactional
    public Order createOrder(Order order) {
        if (order == null) return null;

        log.info("📥 [CREATE] order DTO = {}", order);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(order.getChatId());
        entity.setUserId(order.getChatId()); // ✅

        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setPrice(order.getPrice());
        entity.setQuantity(order.getQuantity());
        entity.setStatus(order.getStatus() != null ? order.getStatus() : "NEW");
        entity.setFilled(Boolean.TRUE.equals(order.isFilled()));
        entity.setTimestamp(order.getTimestamp() != null
                ? order.getTimestamp()
                : System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (order.getStrategyType() != null) {
            entity.setStrategyType(String.valueOf(order.getStrategyType()));
        } else {
            entity.setStrategyType("UNKNOWN");
        }

        if (entity.getPrice() != null && entity.getQuantity() != null) {
            entity.setTotal(entity.getPrice().multiply(entity.getQuantity()));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // ==========================
    // HISTORY (DTO)
    // ==========================
    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        List<OrderEntity> entities =
                orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);

        return entities.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ==========================
    // HISTORY (ENTITY)
    // ==========================
    @Override
    @Transactional(readOnly = true)
    public List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
    }

    // ==========================
    // МАППЕР
    // ==========================
    private Order mapToDto(OrderEntity e) {
        if (e == null) return null;

        Order o = new Order();
        o.setId(e.getId());
        o.setChatId(e.getChatId());
        o.setSymbol(e.getSymbol());
        o.setSide(e.getSide());
        o.setPrice(e.getPrice());
        o.setQuantity(e.getQuantity());
        o.setStatus(e.getStatus());
        o.setFilled(e.getFilled());
        o.setTimestamp(e.getTimestamp());

        try {
            if (e.getStrategyType() != null) {
                o.setStrategyType(StrategyType.valueOf(e.getStrategyType()));
            }
        } catch (Exception ignore) {}

        return o;
    }
}

package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final StrategyLivePublisher livePublisher;

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

        String sideNorm = side != null ? side.toUpperCase() : "BUY";
        String strategyNorm = strategyType != null ? strategyType.toUpperCase() : "UNKNOWN";

        log.info("📥 [MARKET] chatId={}, symbol={}, side={}, qty={}, price={}, strategy={}",
                chatId, symbol, sideNorm, quantity, executionPrice, strategyNorm);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(executionPrice);
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyNorm);
        entity.setStatus("FILLED");
        entity.setFilled(true);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (executionPrice != null && quantity != null) {
            entity.setTotal(executionPrice.multiply(quantity));
        }

        orderRepository.save(entity);

        // 🔥 LIVE TRADE В ГРАФИК
        try {
            livePublisher.pushTrade(
                    chatId,
                    StrategyType.valueOf(strategyNorm),
                    symbol,
                    sideNorm,
                    executionPrice,
                    quantity,
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("⚠️ Live trade publish skipped: {}", e.getMessage());
        }

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

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(side.toUpperCase());
        entity.setPrice(limitPrice);
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType.toUpperCase());
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

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide("SELL");
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType.toUpperCase());
        entity.setStatus("NEW");
        entity.setFilled(false);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setStopLossPrice(stopLimitPrice != null ? stopLimitPrice : stopPrice);

        BigDecimal refPrice = takeProfitPrice != null ? takeProfitPrice : stopLimitPrice;
        if (refPrice != null && quantity != null) {
            entity.setPrice(refPrice);
            entity.setTotal(refPrice.multiply(quantity));
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
        return orderRepository
                .findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ==========================
    // HISTORY (ENTITY) ❗ ОБЯЗАТЕЛЬНО
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
        o.setTime(e.getTimestamp());

        try {
            if (e.getStrategyType() != null) {
                o.setStrategyType(StrategyType.valueOf(e.getStrategyType()));
            }
        } catch (Exception ignore) {}

        return o;
    }
    public Order createOrder(com.chicu.aitradebot.exchange.model.Order order) {
        if (order == null) return null;

        log.info("📥 [CREATE] order DTO = {}", order);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(order.getChatId());
        entity.setUserId(order.getChatId());

        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setPrice(order.getPrice());
        entity.setQuantity(order.getQuantity());
        entity.setStatus(order.getStatus() != null ? order.getStatus() : "NEW");
        entity.setFilled(order.isFilled());
        entity.setTimestamp(order.getTime() != null ? order.getTime() : System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (order.getStrategyType() != null) {
            entity.setStrategyType(order.getStrategyType().name());
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

        log.info("❌ [CANCEL_ALL] chatId={}, symbol={}, count={}",
                chatId, symbol, openOrders.size());

        return openOrders.size();
    }

    // ==========================
// OPEN ORDERS
// ==========================
    @Override
    @Transactional(readOnly = true)
    public List<Order> getOpenOrders(Long chatId, String symbol) {
        List<String> openStatuses = Arrays.asList("NEW", "OPEN", "PARTIALLY_FILLED");

        return orderRepository
                .findByChatIdAndSymbolAndStatusIn(chatId, symbol, openStatuses)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

}


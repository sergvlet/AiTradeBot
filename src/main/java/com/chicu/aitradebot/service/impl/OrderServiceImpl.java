package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.market.guard.ExchangeAIGuard;
import com.chicu.aitradebot.market.guard.GuardResult;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final StrategyLivePublisher livePublisher;
    private final ExchangeClientFactory exchangeClientFactory;
    private final ExchangeSettingsService exchangeSettingsService;

    // 🔥 AI-GUARD
    private final ExchangeAIGuard aiGuard;
    private final MarketSymbolService marketSymbolService;

    // =====================================================
    // MARKET
    // =====================================================
    @Override
    @Transactional
    public Order placeMarket(
            Long chatId,
            String symbol,
            String side,
            BigDecimal quantity,
            BigDecimal executionPrice,
            String strategyType
    ) {

        String sideNorm = side != null ? side.toUpperCase() : "BUY";
        String strategyNorm = strategyType != null ? strategyType.toUpperCase() : "UNKNOWN";

        SymbolDescriptor descriptor = resolveSymbolDescriptor(chatId, symbol);

        GuardResult guard = aiGuard.validateAndAdjust(
                symbol,
                descriptor,
                quantity,
                executionPrice,
                true
        );

        if (!guard.ok()) {
            throw new IllegalArgumentException(
                    "AI-GUARD BLOCKED MARKET ORDER: " +
                    String.join("; ", guard.errors())
            );
        }

        BigDecimal finalQty   = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        log.info("📥 [MARKET] chatId={}, symbol={}, side={}, qty={}, price={}, strategy={}",
                chatId, symbol, sideNorm, finalQty, finalPrice, strategyNorm);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(strategyNorm);
        entity.setStatus("FILLED");
        entity.setFilled(true);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (finalPrice != null && finalQty != null) {
            entity.setTotal(finalPrice.multiply(finalQty));
        }

        orderRepository.save(entity);

        publishTradeSafe(chatId, strategyNorm, symbol, sideNorm, finalPrice, finalQty);

        return mapToDto(entity);
    }

    // =====================================================
    // LIMIT
    // =====================================================
    @Override
    @Transactional
    public Order placeLimit(
            Long chatId,
            String symbol,
            String side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            String timeInForce,
            String strategyType
    ) {

        String sideNorm = side.toUpperCase();
        String strategyNorm = strategyType.toUpperCase();

        SymbolDescriptor descriptor = resolveSymbolDescriptor(chatId, symbol);

        GuardResult guard = aiGuard.validateAndAdjust(
                symbol,
                descriptor,
                quantity,
                limitPrice,
                false
        );

        if (!guard.ok()) {
            throw new IllegalArgumentException(
                    "AI-GUARD BLOCKED LIMIT ORDER: " +
                    String.join("; ", guard.errors())
            );
        }

        BigDecimal finalQty   = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(strategyNorm);
        entity.setStatus("NEW");
        entity.setFilled(false);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (finalPrice != null && finalQty != null) {
            entity.setTotal(finalPrice.multiply(finalQty));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // =====================================================
    // OCO
    // =====================================================
    @Override
    @Transactional
    public Order placeOco(
            Long chatId,
            String symbol,
            BigDecimal quantity,
            BigDecimal takeProfitPrice,
            BigDecimal stopPrice,
            BigDecimal stopLimitPrice,
            String strategyType
    ) {

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

        BigDecimal ref = takeProfitPrice != null ? takeProfitPrice : stopLimitPrice;
        if (ref != null && quantity != null) {
            entity.setPrice(ref);
            entity.setTotal(ref.multiply(quantity));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // =====================================================
    // CANCEL
    // =====================================================
    @Override
    @Transactional
    public boolean cancelOrder(Long chatId, Long orderId) {
        return orderRepository.findById(orderId)
                .filter(o -> chatId.equals(o.getChatId()))
                .map(o -> {
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

        List<String> openStatuses = List.of("NEW", "OPEN", "PARTIALLY_FILLED");

        List<OrderEntity> list =
                orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, symbol, openStatuses);

        list.forEach(o -> {
            o.setStatus("CANCELED");
            o.setFilled(false);
            o.setUpdatedAt(LocalDateTime.now());
        });

        orderRepository.saveAll(list);
        return list.size();
    }

    // =====================================================
    // OPEN ORDERS
    // =====================================================
    @Override
    @Transactional(readOnly = true)
    public List<Order> getOpenOrders(Long chatId, String symbol) {
        return orderRepository
                .findByChatIdAndSymbolAndStatusIn(
                        chatId,
                        symbol,
                        List.of("NEW", "OPEN", "PARTIALLY_FILLED")
                )
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // =====================================================
    // HISTORY
    // =====================================================
    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository
                .findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
    }

    // =====================================================
    // CREATE (generic)
    // =====================================================
    @Override
    @Transactional
    public Order createOrder(Order order) {

        OrderEntity e = new OrderEntity();
        e.setChatId(order.getChatId());
        e.setUserId(order.getChatId());
        e.setSymbol(order.getSymbol());
        e.setSide(order.getSide());
        e.setPrice(order.getPrice());
        e.setQuantity(order.getQuantity());
        e.setStatus(order.getStatus());
        e.setFilled(order.isFilled());
        e.setTimestamp(order.getTime());
        e.setCreatedAt(LocalDateTime.now());

        if (order.getStrategyType() != null) {
            e.setStrategyType(order.getStrategyType().name());
        }

        if (e.getPrice() != null && e.getQuantity() != null) {
            e.setTotal(e.getPrice().multiply(e.getQuantity()));
        }

        orderRepository.save(e);
        return mapToDto(e);
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private SymbolDescriptor resolveSymbolDescriptor(Long chatId, String symbol) {

        if (chatId == null || symbol == null) {
            return null;
        }

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);
            String exchange = client.getExchangeName();

            NetworkType network = exchangeSettingsService
                    .findAllByChatId(chatId)
                    .stream()
                    .filter(ExchangeSettings::isEnabled)
                    .filter(s -> exchange.equalsIgnoreCase(s.getExchange()))
                    .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                    .map(ExchangeSettings::getNetwork)
                    .findFirst()
                    .orElse(NetworkType.MAINNET);

            return marketSymbolService.getSymbolInfo(
                    exchange,
                    network,
                    "USDT",
                    symbol
            );

        } catch (Exception e) {
            log.warn("⚠️ Cannot resolve SymbolDescriptor chatId={} symbol={}", chatId, symbol, e);
            return null;
        }
    }

    private void publishTradeSafe(
            Long chatId,
            String strategyNorm,
            String symbol,
            String side,
            BigDecimal price,
            BigDecimal qty
    ) {
        try {
            StrategyType type = StrategyType.valueOf(strategyNorm);
            livePublisher.pushTrade(chatId, type, symbol, side, price, qty, Instant.now());
        } catch (Exception e) {
            log.warn("⚠️ Live trade publish skipped: {}", e.getMessage());
        }
    }

    private Order mapToDto(OrderEntity e) {
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
            o.setStrategyType(StrategyType.valueOf(e.getStrategyType()));
        } catch (Exception ignored) {}

        return o;
    }
}

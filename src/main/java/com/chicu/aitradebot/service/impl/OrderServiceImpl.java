package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.journal.OrderCorrelation;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import com.chicu.aitradebot.market.guard.ExchangeAIGuard;
import com.chicu.aitradebot.market.guard.GuardResult;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.TradeJournalGateway;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    // ✅ journal gateway (NOOP или DB-реализация)
    private final TradeJournalGateway tradeJournalGateway;

    // =====================================================
    // ✅ НОВОЕ API (с OrderContext)
    // =====================================================

    @Override
    @Transactional
    public Order placeMarket(OrderContext ctx,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice) {

        if (ctx == null) throw new IllegalArgumentException("OrderContext is null");

        Long chatId = ctx.chatId();
        String symbol = ctx.symbol();
        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is blank");

        String sideNorm = normalizeSide(side);
        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        ExchangeClient client = exchangeClientFactory.getByChat(chatId);
        String exchangeName = safeUpper(client.getExchangeName());
        NetworkType networkType = resolveNetworkType(chatId, exchangeName);

        String timeframe = (ctx.timeframe() == null || ctx.timeframe().isBlank()) ? "1m" : ctx.timeframe().trim();
        String role = (ctx.role() == null || ctx.role().isBlank()) ? "ENTRY" : ctx.role().trim().toUpperCase(Locale.ROOT);

        SymbolDescriptor descriptor = resolveSymbolDescriptor(chatId, symbol);

        // ✅ ВАЖНО: первым параметром AI-GUARD должен быть EXCHANGE, не SYMBOL
        GuardResult guard = aiGuard.validateAndAdjust(
                exchangeName,
                descriptor,
                quantity,
                executionPrice, // для MARKET можно передавать оценку/последний тик
                true
        );

        String correlationId = ensureCorrelationId(ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard);

        if (!guard.ok()) {
            throw new IllegalArgumentException("AI-GUARD BLOCKED MARKET ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, safeUpper(symbol), timeframe, correlationId, clientOrderId, role);

        log.info("📥 [MARKET] chatId={}, ex={}, net={}, symbol={}, side={}, qty={}, price={}, st={}, cid={}, role={}",
                chatId, exchangeName, networkType, safeUpper(symbol), sideNorm, strip(finalQty), strip(finalPrice), st, correlationId, role);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(safeUpper(symbol));
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(st.name());
        entity.setStatus("FILLED");
        entity.setFilled(true);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (finalPrice != null && finalQty != null) {
            entity.setTotal(finalPrice.multiply(finalQty));
        }

        orderRepository.save(entity);

        publishTradeSafe(chatId, st, symbol, sideNorm, finalPrice, finalQty);
        return mapToDto(entity);
    }

    @Override
    @Transactional
    public Order placeLimit(OrderContext ctx,
                            String side,
                            BigDecimal quantity,
                            BigDecimal limitPrice,
                            String timeInForce) {

        if (ctx == null) throw new IllegalArgumentException("OrderContext is null");

        Long chatId = ctx.chatId();
        String symbol = ctx.symbol();
        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is blank");

        String sideNorm = normalizeSide(side);
        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        ExchangeClient client = exchangeClientFactory.getByChat(chatId);
        String exchangeName = safeUpper(client.getExchangeName());
        NetworkType networkType = resolveNetworkType(chatId, exchangeName);

        String timeframe = (ctx.timeframe() == null || ctx.timeframe().isBlank()) ? "1m" : ctx.timeframe().trim();
        String role = (ctx.role() == null || ctx.role().isBlank()) ? "ENTRY" : ctx.role().trim().toUpperCase(Locale.ROOT);

        SymbolDescriptor descriptor = resolveSymbolDescriptor(chatId, symbol);

        GuardResult guard = aiGuard.validateAndAdjust(
                exchangeName,
                descriptor,
                quantity,
                limitPrice,
                false
        );

        String correlationId = ensureCorrelationId(ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard);

        if (!guard.ok()) {
            throw new IllegalArgumentException("AI-GUARD BLOCKED LIMIT ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, safeUpper(symbol), timeframe, correlationId, clientOrderId, role);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(safeUpper(symbol));
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(st.name());
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

    @Override
    @Transactional
    public Order placeOco(OrderContext ctx,
                          BigDecimal quantity,
                          BigDecimal takeProfitPrice,
                          BigDecimal stopPrice,
                          BigDecimal stopLimitPrice) {

        if (ctx == null) throw new IllegalArgumentException("OrderContext is null");

        Long chatId = ctx.chatId();
        String symbol = ctx.symbol();
        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is blank");

        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        ExchangeClient client = exchangeClientFactory.getByChat(chatId);
        String exchangeName = safeUpper(client.getExchangeName());
        NetworkType networkType = resolveNetworkType(chatId, exchangeName);

        String timeframe = (ctx.timeframe() == null || ctx.timeframe().isBlank()) ? "1m" : ctx.timeframe().trim();
        String role = (ctx.role() == null || ctx.role().isBlank()) ? "OCO" : ctx.role().trim().toUpperCase(Locale.ROOT);

        // Для OCO обычно SELL-логика закрытия
        GuardResult guard = GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(quantity)
                .finalPrice(takeProfitPrice) // просто референс
                .warnings(List.of())
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(ctx, chatId, st, exchangeName, networkType, symbol, timeframe, "SELL", guard);

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, safeUpper(symbol), timeframe, correlationId, clientOrderId, role);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(safeUpper(symbol));
        entity.setSide("SELL");
        entity.setQuantity(quantity);
        entity.setStrategyType(st.name());
        entity.setStatus("NEW");
        entity.setFilled(false);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setStopLossPrice(stopLimitPrice != null ? stopLimitPrice : stopPrice);

        BigDecimal ref = takeProfitPrice != null ? takeProfitPrice : (stopLimitPrice != null ? stopLimitPrice : stopPrice);
        if (ref != null && quantity != null) {
            entity.setPrice(ref);
            entity.setTotal(ref.multiply(quantity));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    // =====================================================
    // ⚠️ СТАРОЕ API — делегируем в НОВОЕ
    // =====================================================

    @Override
    @Transactional
    public Order placeMarket(Long chatId,
                             String symbol,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice,
                             String strategyType) {

        StrategyType st = normalizeStrategy(strategyType);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                symbol,
                "1m",
                null,
                "ENTRY"
        );

        return placeMarket(ctx, side, quantity, executionPrice);
    }

    @Override
    @Transactional
    public Order placeLimit(Long chatId,
                            String symbol,
                            String side,
                            BigDecimal quantity,
                            BigDecimal limitPrice,
                            String timeInForce,
                            String strategyType) {

        StrategyType st = normalizeStrategy(strategyType);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                symbol,
                "1m",
                null,
                "ENTRY"
        );

        return placeLimit(ctx, side, quantity, limitPrice, timeInForce);
    }

    @Override
    @Transactional
    public Order placeOco(Long chatId,
                          String symbol,
                          BigDecimal quantity,
                          BigDecimal takeProfitPrice,
                          BigDecimal stopPrice,
                          BigDecimal stopLimitPrice,
                          String strategyType) {

        StrategyType st = normalizeStrategy(strategyType);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                symbol,
                "1m",
                null,
                "OCO"
        );

        return placeOco(ctx, quantity, takeProfitPrice, stopPrice, stopLimitPrice);
    }

    // =====================================================
    // CANCEL / OPEN / HISTORY / CREATE
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

    private String ensureCorrelationId(OrderContext ctx,
                                       Long chatId,
                                       StrategyType st,
                                       String exchangeName,
                                       NetworkType networkType,
                                       String symbol,
                                       String timeframe,
                                       String sideNorm,
                                       GuardResult guard) {

        if (ctx.correlationId() != null && !ctx.correlationId().isBlank()) {
            return ctx.correlationId().trim();
        }

        return tradeJournalGateway.recordIntent(
                chatId,
                st,
                exchangeName,
                networkType,
                safeUpper(symbol),
                timeframe,
                "BUY".equals(sideNorm) ? TradeIntentEvent.Signal.BUY : TradeIntentEvent.Signal.SELL,
                guard.ok() ? TradeIntentEvent.Decision.ALLOW : TradeIntentEvent.Decision.REJECT,
                guard.ok() ? "OK" : "AI_GUARD_BLOCK",
                null, null, null,
                null,
                null,
                null
        );
    }

    private SymbolDescriptor resolveSymbolDescriptor(Long chatId, String symbol) {
        if (chatId == null || symbol == null) return null;

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);
            String exchange = client.getExchangeName();
            NetworkType network = resolveNetworkType(chatId, exchange);

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

    private NetworkType resolveNetworkType(Long chatId, String exchangeName) {
        try {

            return exchangeSettingsService
                    .findAllByChatId(chatId)
                    .stream()

                    .filter(s -> exchangeName != null && exchangeName.equalsIgnoreCase(s.getExchange()))

                    .map(ExchangeSettings::getNetwork)
                    .findFirst()
                    .orElse(NetworkType.MAINNET);
        } catch (Exception e) {
            return NetworkType.MAINNET;
        }
    }

    private void publishTradeSafe(Long chatId,
                                  StrategyType type,
                                  String symbol,
                                  String side,
                                  BigDecimal price,
                                  BigDecimal qty) {
        try {
            livePublisher.pushTrade(chatId, type, symbol, side, price, qty, Instant.now());
        } catch (Exception e) {
            log.debug("Live trade publish skipped: {}", e.getMessage());
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
        } catch (Exception ignored) { }

        return o;
    }

    private static String normalizeSide(String side) {
        String s = side == null ? "BUY" : side.trim().toUpperCase(Locale.ROOT);
        return ("SELL".equals(s)) ? "SELL" : "BUY";
    }

    private static StrategyType normalizeStrategy(String strategyType) {
        if (strategyType == null || strategyType.isBlank()) return StrategyType.values()[0];
        try {
            return StrategyType.valueOf(strategyType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return StrategyType.values()[0];
        }
    }

    private static String safeUpper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}

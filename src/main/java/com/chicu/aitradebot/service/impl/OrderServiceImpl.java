package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.enums.OrderSide;
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
import com.chicu.aitradebot.trade.math.QtyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private static final int QTY_SCALE = 8;
    private static final BigDecimal BUY_BUDGET_EPS = new BigDecimal("1.0005");

    private final OrderRepository orderRepository;
    private final StrategyLivePublisher livePublisher;
    private final ExchangeSettingsService exchangeSettingsService;
    private final List<ExchangeClient> exchangeClients;
    private final ExchangeAIGuard aiGuard;
    private final MarketSymbolService marketSymbolService;
    private final TradeJournalGateway tradeJournalGateway;

    @Override
    @Transactional
    public Order placeMarket(OrderContext ctx,
                             OrderSide side,
                             BigDecimal amount,
                             BigDecimal executionPrice) {
        if (side == null) {
            throw new IllegalArgumentException("side is null");
        }
        return doPlaceMarket(ctx, side.name(), amount, executionPrice);
    }

    @Override
    @Transactional
    public Order placeLimit(OrderContext ctx,
                            OrderSide side,
                            BigDecimal quantity,
                            BigDecimal limitPrice,
                            String timeInForce) {
        if (side == null) {
            throw new IllegalArgumentException("side is null");
        }
        return doPlaceLimit(ctx, side.name(), quantity, limitPrice, timeInForce);
    }

    @Override
    @Transactional
    public Order placeOco(OrderContext ctx,
                          BigDecimal quantity,
                          BigDecimal takeProfitPrice,
                          BigDecimal stopPrice,
                          BigDecimal stopLimitPrice) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType strategyType = requireStrategy(ctx.strategyType());
        String symbol = normalizeSymbolOrThrow(ctx.symbol());

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        ExchangeClient client = resolveClientOrThrow(exchangeName);
        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "OCO");

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity invalid");
        }
        if (takeProfitPrice != null && takeProfitPrice.signum() <= 0) {
            throw new IllegalArgumentException("takeProfitPrice invalid");
        }
        if (stopPrice != null && stopPrice.signum() <= 0) {
            throw new IllegalArgumentException("stopPrice invalid");
        }
        if (stopLimitPrice != null && stopLimitPrice.signum() <= 0) {
            throw new IllegalArgumentException("stopLimitPrice invalid");
        }
        if (takeProfitPrice == null && stopPrice == null && stopLimitPrice == null) {
            throw new IllegalArgumentException("OCO требует хотя бы одну цену");
        }

        BigDecimal stopLossPrice = stopLimitPrice != null ? stopLimitPrice : stopPrice;

        GuardResult guard = GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(quantity)
                .finalPrice(takeProfitPrice != null ? takeProfitPrice : stopLossPrice)
                .warnings(List.of())
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, strategyType, exchangeName, networkType, symbol, timeframe, "SELL", guard
        );

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, strategyType, symbol, role);
        attachJournalLinks(chatId, strategyType, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        ExchangeClient.OcoResult ocoResult = null;
        boolean placedOnExchange = false;

        try {
            log.info("📤 [OCO] Отправляю OCO на биржу | chatId={} ex={} net={} sym={} qty={} tp={} stop={} stopLimit={} strategy={} cid={} role={}",
                    chatId, exchangeName, networkType, symbol,
                    strip(quantity), strip(takeProfitPrice), strip(stopPrice), strip(stopLimitPrice),
                    strategyType, correlationId, role);

            ocoResult = client.placeOcoOrder(
                    chatId,
                    networkType,
                    symbol,
                    quantity,
                    takeProfitPrice,
                    stopPrice,
                    stopLimitPrice,
                    java.util.Map.of("clientOrderId", clientOrderId)
            );
            placedOnExchange = true;

            log.info("✅ [OCO] Биржа приняла OCO | chatId={} ex={} net={} sym={} listId={} status={} tpOrderId={} slOrderId={} cid={}",
                    chatId, exchangeName, networkType, symbol,
                    ocoResult != null ? ocoResult.orderListId() : "null",
                    ocoResult != null ? ocoResult.status() : "null",
                    ocoResult != null ? ocoResult.orderIdTp() : "null",
                    ocoResult != null ? ocoResult.orderIdSl() : "null",
                    correlationId);

        } catch (UnsupportedOperationException e) {
            log.warn("⚠️ [OCO] Биржа не поддерживает OCO, включаю локальный fallback | chatId={} ex={} net={} sym={} cid={} msg={}",
                    chatId, exchangeName, networkType, symbol, correlationId, e.getMessage());
        } catch (Exception e) {
            log.error("❌ [OCO] Ошибка отправки OCO на биржу | chatId={} ex={} net={} sym={} cid={} err={}",
                    chatId, exchangeName, networkType, symbol, correlationId, e.toString(), e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide("SELL");
        entity.setQuantity(quantity);
        entity.setStrategyType(strategyType.name());
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExchangeName(exchangeName);
        entity.setNetworkType(networkType.name());
        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setStopLossPrice(stopLossPrice);

        BigDecimal refPrice = takeProfitPrice != null ? takeProfitPrice : stopLossPrice;
        entity.setPrice(refPrice);
        if (refPrice != null) {
            entity.setTotal(refPrice.multiply(quantity));
        }

        String status = "NEW";
        if (placedOnExchange && ocoResult != null && ocoResult.status() != null && !ocoResult.status().isBlank()) {
            status = ocoResult.status().trim().toUpperCase(Locale.ROOT);
        }
        entity.setStatus(status);
        entity.setFilled(false);

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    @Override
    @Transactional
    public Order placeMarket(Long chatId,
                             String symbol,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice,
                             String strategyType) {

        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null");
        }

        StrategyType parsedStrategy = parseStrategyOrThrow(strategyType);
        String exchangeName = resolveDefaultExchange(chatId);
        NetworkType networkType = resolveDefaultNetwork(chatId, exchangeName);
        String normalizedSymbol = normalizeSymbolOrThrow(symbol);

        OrderContext ctx = new OrderContext(
                chatId,
                parsedStrategy,
                normalizedSymbol,
                "1m",
                null,
                "ENTRY",
                exchangeName,
                networkType
        );

        String sideNorm = normalizeSide(side);
        BigDecimal amount;
        if ("BUY".equals(sideNorm)) {
            if (executionPrice == null || executionPrice.signum() <= 0) {
                throw new IllegalArgumentException("executionPrice required for BUY (legacy)");
            }
            amount = safeMul(quantity, executionPrice);
        } else {
            amount = quantity;
        }

        return doPlaceMarket(ctx, sideNorm, amount, executionPrice);
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

        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null");
        }

        StrategyType parsedStrategy = parseStrategyOrThrow(strategyType);
        String exchangeName = resolveDefaultExchange(chatId);
        NetworkType networkType = resolveDefaultNetwork(chatId, exchangeName);

        OrderContext ctx = new OrderContext(
                chatId,
                parsedStrategy,
                normalizeSymbolOrThrow(symbol),
                "1m",
                null,
                "ENTRY",
                exchangeName,
                networkType
        );

        return doPlaceLimit(ctx, side, quantity, limitPrice, timeInForce);
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
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null");
        }

        StrategyType parsedStrategy = parseStrategyOrThrow(strategyType);
        String exchangeName = resolveDefaultExchange(chatId);
        NetworkType networkType = resolveDefaultNetwork(chatId, exchangeName);

        OrderContext ctx = new OrderContext(
                chatId,
                parsedStrategy,
                normalizeSymbolOrThrow(symbol),
                "1m",
                null,
                "OCO",
                exchangeName,
                networkType
        );

        return placeOco(ctx, quantity, takeProfitPrice, stopPrice, stopLimitPrice);
    }

    @Override
    @Transactional
    public boolean cancelOrder(Long chatId, Long orderId) {
        if (chatId == null || orderId == null) {
            return false;
        }

        return orderRepository.findById(orderId)
                .filter(order -> chatId.equals(order.getChatId()))
                .map(order -> {
                    order.setStatus("CANCELED");
                    order.setFilled(false);
                    order.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(order);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public int cancelAllOpen(Long chatId, String symbol) {
        if (chatId == null) {
            return 0;
        }

        String normalizedSymbol = normalizeSymbolOrThrow(symbol);
        List<OrderEntity> list = orderRepository.findByChatIdAndSymbolAndStatusIn(
                chatId,
                normalizedSymbol,
                List.of("NEW", "OPEN", "PARTIALLY_FILLED")
        );

        list.forEach(order -> {
            order.setStatus("CANCELED");
            order.setFilled(false);
            order.setUpdatedAt(LocalDateTime.now());
        });

        orderRepository.saveAll(list);
        return list.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOpenOrders(Long chatId, String symbol) {
        if (chatId == null) {
            return List.of();
        }

        String normalizedSymbol = normalizeSymbolOrThrow(symbol);
        return orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, normalizedSymbol, List.of("NEW", "OPEN", "PARTIALLY_FILLED"))
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        String normalizedSymbol = normalizeSymbolOrThrow(symbol);
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, normalizedSymbol)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, normalizeSymbolOrThrow(symbol));
    }

    @Override
    @Transactional
    public Order createOrder(Order order, String exchangeName, NetworkType networkType) {
        if (order == null) {
            throw new IllegalArgumentException("order is null");
        }

        OrderEntity entity = new OrderEntity();
        entity.setChatId(order.getChatId());
        entity.setUserId(order.getChatId());
        entity.setSymbol(normalizeSymbolOrThrow(order.getSymbol()));
        entity.setSide(normalizeSide(order.getSide()));
        entity.setPrice(order.getPrice());
        entity.setQuantity(order.getQuantity());
        entity.setStatus(order.getStatus());
        entity.setFilled(order.isFilled());
        entity.setTimestamp(order.getTime());
        entity.setCreatedAt(LocalDateTime.now());

        if (order.getStrategyType() != null) {
            entity.setStrategyType(order.getStrategyType().name());
        }

        String finalExchange = safeUpper(exchangeName);
        if (finalExchange.isBlank()) {
            finalExchange = safeUpper(order.getExchangeName());
        }
        if (!finalExchange.isBlank()) {
            entity.setExchangeName(finalExchange);
        }

        NetworkType finalNetwork = networkType != null ? networkType : order.getNetworkType();
        if (finalNetwork != null) {
            entity.setNetworkType(finalNetwork.name());
        }

        if (entity.getPrice() != null && entity.getQuantity() != null) {
            entity.setTotal(entity.getPrice().multiply(entity.getQuantity()));
        }

        orderRepository.save(entity);
        return mapToDto(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getStepSize(String exchangeName, NetworkType networkType, String symbol) {
        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);
        return descriptor != null ? descriptor.stepSize() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getMinNotional(String exchangeName, NetworkType networkType, String symbol) {
        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);
        return descriptor != null ? descriptor.minNotional() : null;
    }

    private Order doPlaceMarket(OrderContext ctx,
                                String side,
                                BigDecimal amount,
                                BigDecimal executionPrice) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType strategyType = requireStrategy(ctx.strategyType());
        String symbol = normalizeSymbolOrThrow(ctx.symbol());
        String sideNorm = normalizeSide(side);

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        BigDecimal priceHint = executionPrice != null && executionPrice.signum() > 0
                ? executionPrice
                : fetchPriceHintOrThrow(ctx, symbol);

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        ExchangeClient client = resolveClientOrThrow(exchangeName);
        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);
        BigDecimal quoteAmount = "BUY".equals(sideNorm) ? amount : null;
        BigDecimal requestedQty = "BUY".equals(sideNorm) ? calcQtyFromQuote(quoteAmount, priceHint) : amount;

        if (requestedQty == null || requestedQty.signum() <= 0) {
            throw new IllegalArgumentException("calculated qty <= 0");
        }

        GuardResult guard = descriptor != null
                ? aiGuard.validateAndAdjust(exchangeName, descriptor, requestedQty, priceHint, true, false)
                : GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(requestedQty)
                .finalPrice(priceHint)
                .warnings(List.of("SYMBOL_DESCRIPTOR_NULL"))
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, strategyType, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ [Ордер] AI-GUARD заблокировал MARKET ордер | chatId={} ex={} net={} sym={} side={} qty={} price={} errors={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(requestedQty), strip(priceHint), guard.errors(), correlationId);
            throw new IllegalArgumentException("AI-GUARD BLOCKED MARKET ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice() != null && guard.finalPrice().signum() > 0 ? guard.finalPrice() : priceHint;
        BigDecimal finalNotional = safeMul(finalQty, finalPrice);

        if (finalQty == null || finalQty.signum() <= 0) {
            throw new IllegalArgumentException("finalQty invalid after guard");
        }
        if (finalNotional == null || finalNotional.signum() <= 0) {
            throw new IllegalArgumentException("notional invalid after guard");
        }

        if ("BUY".equals(sideNorm)) {
            BigDecimal maxAllowed = quoteAmount.multiply(BUY_BUDGET_EPS);
            if (finalNotional.compareTo(maxAllowed) > 0) {
                log.warn("💥 [Ордер] BUY заблокирован: превышен бюджет | chatId={} ex={} net={} sym={} quote={} notional={} qty={} price={} cid={}",
                        chatId, exchangeName, networkType, symbol,
                        strip(quoteAmount), strip(finalNotional), strip(finalQty), strip(finalPrice), correlationId);
                throw new IllegalArgumentException("BUDGET_EXCEEDED: notional=" + strip(finalNotional) + " > quote=" + strip(quoteAmount));
            }
        }

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, strategyType, symbol, role);
        attachJournalLinks(chatId, strategyType, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        Order executed;
        try {
            OrderSide orderSide = "BUY".equals(sideNorm) ? OrderSide.BUY : OrderSide.SELL;

            BigDecimal amountToSend;
            ExchangeClient.OrderAmountType amountType;

            if (orderSide == OrderSide.BUY) {
                BigDecimal quoteToSend = finalNotional.setScale(QTY_SCALE, RoundingMode.DOWN);
                if (quoteToSend.signum() <= 0) {
                    throw new IllegalArgumentException("quoteToSend <= 0");
                }
                if (quoteToSend.compareTo(quoteAmount.multiply(BUY_BUDGET_EPS)) > 0) {
                    throw new IllegalArgumentException("BUDGET_EXCEEDED(after_round): quoteToSend=" + strip(quoteToSend) + " > quote=" + strip(quoteAmount));
                }
                amountToSend = quoteToSend;
                amountType = ExchangeClient.OrderAmountType.QUOTE_QTY;
            } else {
                amountToSend = finalQty;
                amountType = ExchangeClient.OrderAmountType.BASE_QTY;
            }

            log.info("📤 [MARKET] Отправляю ордер на биржу | chatId={} ex={} net={} sym={} side={} amount={} amountType={} priceHint={} strategy={} cid={} role={} minNotional={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(amountToSend), amountType, strip(finalPrice), strategyType, correlationId, role, strip(minNotionalSafe(guard)));

            executed = client.placeMarketOrder(
                    chatId,
                    networkType,
                    symbol,
                    orderSide,
                    amountToSend,
                    amountType,
                    finalPrice
            );

        } catch (Exception e) {
            log.error("❌ [MARKET] Ошибка отправки ордера на биржу | chatId={} ex={} net={} sym={} side={} cid={} err={}",
                    chatId, exchangeName, networkType, symbol, sideNorm, correlationId, e.toString(), e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }

        BigDecimal executedQtyRaw = executed != null && executed.getExecutedQty() != null && executed.getExecutedQty().signum() > 0
                ? executed.getExecutedQty()
                : (executed != null && executed.getQuantity() != null && executed.getQuantity().signum() > 0
                ? executed.getQuantity()
                : finalQty);
        BigDecimal executedPrice = executed != null && executed.getAvgPrice() != null && executed.getAvgPrice().signum() > 0
                ? executed.getAvgPrice()
                : (executed != null && executed.getPrice() != null && executed.getPrice().signum() > 0
                ? executed.getPrice()
                : finalPrice);

        BigDecimal executedQty = normalizeExecutedQtyForPersistence(sideNorm, executedQtyRaw, descriptor);
        String status = executed != null && executed.getStatus() != null && !executed.getStatus().isBlank()
                ? executed.getStatus().trim().toUpperCase(Locale.ROOT)
                : "FILLED";
        boolean filled = executed != null ? executed.isFilled() : "FILLED".equalsIgnoreCase(status);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(executedPrice);
        entity.setQuantity(executedQty);
        entity.setStrategyType(strategyType.name());
        entity.setStatus(status);
        entity.setFilled(filled);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExchangeName(exchangeName);
        entity.setNetworkType(networkType.name());

        if (executedPrice != null && executedQty != null) {
            entity.setTotal(executedPrice.multiply(executedQty));
        }

        orderRepository.save(entity);
        publishTradeSafe(chatId, strategyType, symbol, sideNorm, executedPrice, executedQty);

        log.info("✅ [MARKET] Ордер сохранён | chatId={} ex={} net={} sym={} side={} qty={} price={} status={} filled={} cid={}",
                chatId, exchangeName, networkType, symbol, sideNorm,
                strip(executedQty), strip(executedPrice), status, filled, correlationId);

        return mapToDto(entity);
    }

    private BigDecimal normalizeExecutedQtyForPersistence(String sideNorm, BigDecimal qty, SymbolDescriptor descriptor) {
        if (!QtyMath.isPositive(qty)) {
            return BigDecimal.ZERO;
        }
        return qty.stripTrailingZeros();
    }

    private Order doPlaceLimit(OrderContext ctx,
                               String side,
                               BigDecimal quantity,
                               BigDecimal limitPrice,
                               String timeInForce) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType strategyType = requireStrategy(ctx.strategyType());
        String symbol = normalizeSymbolOrThrow(ctx.symbol());
        String sideNorm = normalizeSide(side);

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity invalid");
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("limitPrice invalid");
        }

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        ExchangeClient client = resolveClientOrThrow(exchangeName);
        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);
        GuardResult guard = descriptor != null
                ? aiGuard.validateAndAdjust(exchangeName, descriptor, quantity, limitPrice, false, false)
                : GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(quantity)
                .finalPrice(limitPrice)
                .warnings(List.of("SYMBOL_DESCRIPTOR_NULL"))
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, strategyType, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ [Ордер] AI-GUARD заблокировал LIMIT ордер | chatId={} ex={} net={} sym={} side={} qty={} price={} errors={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(quantity), strip(limitPrice), guard.errors(), correlationId);
            throw new IllegalArgumentException("AI-GUARD BLOCKED LIMIT ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice() != null && guard.finalPrice().signum() > 0 ? guard.finalPrice() : limitPrice;
        String tif = timeInForce == null || timeInForce.isBlank()
                ? "GTC"
                : timeInForce.trim().toUpperCase(Locale.ROOT);

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, strategyType, symbol, role);
        attachJournalLinks(chatId, strategyType, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        ExchangeClient.OrderResult placed;
        try {
            log.info("📤 [LIMIT] Отправляю ордер на биржу | chatId={} ex={} net={} sym={} side={} qty={} price={} tif={} strategy={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(finalQty), strip(finalPrice), tif, strategyType, correlationId);

            placed = client.placeOrder(
                    chatId,
                    networkType,
                    symbol,
                    sideNorm,
                    "LIMIT",
                    finalQty,
                    finalPrice,
                    java.util.Map.of("timeInForce", tif, "clientOrderId", clientOrderId)
            );
        } catch (Exception e) {
            log.error("❌ [LIMIT] Ошибка отправки ордера на биржу | chatId={} ex={} net={} sym={} side={} cid={} err={}",
                    chatId, exchangeName, networkType, symbol, sideNorm, correlationId, e.toString(), e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }

        String status = placed != null && placed.status() != null && !placed.status().isBlank()
                ? placed.status().trim().toUpperCase(Locale.ROOT)
                : "NEW";
        boolean filled = "FILLED".equalsIgnoreCase(status);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(strategyType.name());
        entity.setStatus(status);
        entity.setFilled(filled);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExchangeName(exchangeName);
        entity.setNetworkType(networkType.name());
        entity.setTotal(finalPrice.multiply(finalQty));

        orderRepository.save(entity);

        log.info("✅ [LIMIT] Ордер сохранён | chatId={} ex={} net={} sym={} side={} qty={} price={} status={} filled={} cid={}",
                chatId, exchangeName, networkType, symbol, sideNorm,
                strip(finalQty), strip(finalPrice), status, filled, correlationId);

        return mapToDto(entity);
    }

    private void attachJournalLinks(Long chatId,
                                    StrategyType strategyType,
                                    String exchangeName,
                                    NetworkType networkType,
                                    String symbol,
                                    String timeframe,
                                    String correlationId,
                                    String clientOrderId,
                                    String role) {
        try {
            tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
            tradeJournalGateway.linkClientOrder(chatId, strategyType, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);
        } catch (Exception e) {
            log.debug("Пропускаю привязку к торговому журналу: {}", e.toString());
        }
    }

    private static void requireCtx(OrderContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("OrderContext is null");
        }
        if (ctx.chatId() == null) {
            throw new IllegalArgumentException("chatId is null");
        }
        if (ctx.symbol() == null || ctx.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is blank");
        }
    }

    private static StrategyType requireStrategy(StrategyType strategyType) {
        if (strategyType == null) {
            throw new IllegalArgumentException("strategyType is null in OrderContext");
        }
        return strategyType;
    }

    private ExchangeClient resolveClientOrThrow(String exchangeName) {
        String normalizedExchange = safeUpper(exchangeName);
        if (normalizedExchange.isBlank()) {
            throw new IllegalArgumentException("exchangeName is blank");
        }

        for (ExchangeClient client : exchangeClients) {
            if (client == null) {
                continue;
            }
            String name = client.getExchangeName();
            if (name != null && normalizedExchange.equalsIgnoreCase(name.trim())) {
                return client;
            }
        }

        String available = exchangeClients.stream()
                .filter(Objects::nonNull)
                .map(ExchangeClient::getExchangeName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(", "));

        throw new IllegalStateException("ExchangeClient not found for exchange=" + normalizedExchange + ". Available: " + available);
    }

    private BigDecimal fetchPriceHintOrThrow(OrderContext ctx, String symbol) {
        String exchangeName = safeUpper(ctx.exchangeName());
        if (exchangeName.isBlank() && ctx.chatId() != null) {
            exchangeName = resolveDefaultExchange(ctx.chatId());
        }

        ExchangeClient client = resolveClientOrThrow(exchangeName);
        try {
            double price = client.getPrice(symbol);
            if (price <= 0) {
                throw new IllegalStateException("exchange returned price<=0");
            }
            return BigDecimal.valueOf(price);
        } catch (Exception e) {
            throw new IllegalArgumentException("executionPrice is missing and cannot fetch price from exchange: " + e.getMessage(), e);
        }
    }

    private String ensureCorrelationId(OrderContext ctx,
                                       Long chatId,
                                       StrategyType strategyType,
                                       String exchangeName,
                                       NetworkType networkType,
                                       String symbol,
                                       String timeframe,
                                       String sideNorm,
                                       GuardResult guard) {
        if (ctx.correlationId() != null && !ctx.correlationId().isBlank()) {
            return ctx.correlationId().trim();
        }

        try {
            return tradeJournalGateway.recordIntent(
                    chatId,
                    strategyType,
                    exchangeName,
                    networkType,
                    symbol,
                    timeframe,
                    "BUY".equals(sideNorm) ? TradeIntentEvent.Signal.BUY : TradeIntentEvent.Signal.SELL,
                    guard.ok() ? TradeIntentEvent.Decision.ALLOW : TradeIntentEvent.Decision.REJECT,
                    guard.ok() ? "OK" : "AI_GUARD_BLOCK",
                    null, null, null,
                    null, null, null
            );
        } catch (Exception e) {
            return "cid-" + chatId + "-" + System.currentTimeMillis();
        }
    }

    private SymbolDescriptor resolveSymbolDescriptor(String exchangeName, NetworkType networkType, String symbol) {
        if (exchangeName == null || exchangeName.isBlank() || symbol == null || symbol.isBlank()) {
            return null;
        }

        NetworkType network = networkType != null ? networkType : NetworkType.MAINNET;
        String normalizedSymbol = normalizeSymbolOrThrow(symbol);

        LinkedHashSet<String> accountAssets = new LinkedHashSet<>();
        for (String quote : List.of("USDT", "USDC", "BUSD", "FDUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "USDP", "DAI")) {
            if (normalizedSymbol.endsWith(quote)) {
                accountAssets.add(quote);
            }
        }
        accountAssets.add("USDT");
        accountAssets.add("USDC");
        accountAssets.add("FDUSD");
        accountAssets.add("BUSD");
        accountAssets.add("BTC");
        accountAssets.add("ETH");
        accountAssets.add("BNB");

        for (String accountAsset : accountAssets) {
            try {
                SymbolDescriptor descriptor = marketSymbolService.getSymbolInfo(exchangeName, network, accountAsset, normalizedSymbol);
                if (descriptor != null) {
                    if (descriptor.minNotional() == null) {
                        log.debug("ℹ️ [Ордер] SymbolDescriptor найден, но minNotional пустой | ex={} net={} symbol={} accountAsset={}",
                                exchangeName, network, normalizedSymbol, accountAsset);
                    } else {
                        log.debug("✅ [Ордер] SymbolDescriptor найден | ex={} net={} symbol={} accountAsset={} minNotional={} stepSize={} tickSize={}",
                                exchangeName, network, normalizedSymbol, accountAsset,
                                strip(descriptor.minNotional()), strip(descriptor.stepSize()), strip(descriptor.tickSize()));
                    }
                    return descriptor;
                }
            } catch (Exception e) {
                log.debug("⚠️ [Ордер] Ошибка получения SymbolDescriptor | ex={} net={} symbol={} accountAsset={} err={}",
                        exchangeName, network, normalizedSymbol, accountAsset, e.toString());
            }
        }

        log.warn("⚠️ [Ордер] Не удалось определить SymbolDescriptor | ex={} net={} symbol={}",
                exchangeName, network, normalizedSymbol);
        return null;
    }

    private String resolveDefaultExchange(Long chatId) {
        try {
            return exchangeSettingsService.findAllByChatId(chatId)
                    .stream()
                    .map(ExchangeSettings::getExchange)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .map(this::safeUpper)
                    .orElse("BINANCE");
        } catch (Exception e) {
            return "BINANCE";
        }
    }

    private NetworkType resolveDefaultNetwork(Long chatId, String exchangeName) {
        try {
            return exchangeSettingsService.findAllByChatId(chatId)
                    .stream()
                    .filter(settings -> exchangeName != null && exchangeName.equalsIgnoreCase(settings.getExchange()))
                    .map(ExchangeSettings::getNetwork)
                    .findFirst()
                    .orElse(NetworkType.MAINNET);
        } catch (Exception e) {
            return NetworkType.MAINNET;
        }
    }

    private void publishTradeSafe(Long chatId,
                                  StrategyType strategyType,
                                  String symbol,
                                  String side,
                                  BigDecimal price,
                                  BigDecimal qty) {
        try {
            livePublisher.pushTrade(chatId, strategyType, symbol, side, price, qty, Instant.now());
        } catch (Exception e) {
            log.debug("Пропускаю публикацию сделки в live-канал: {}", e.getMessage());
        }
    }

    private Order mapToDto(OrderEntity entity) {
        Order order = new Order();
        order.setId(entity.getId());
        order.setChatId(entity.getChatId());
        order.setSymbol(entity.getSymbol());
        order.setSide(entity.getSide());
        order.setPrice(entity.getPrice());
        order.setQuantity(entity.getQuantity());
        order.setStatus(entity.getStatus());
        order.setFilled(Boolean.TRUE.equals(entity.getFilled()));
        order.setTime(entity.getTimestamp());
        order.setExchangeName(entity.getExchangeName());

        if (entity.getNetworkType() != null && !entity.getNetworkType().isBlank()) {
            try {
                order.setNetworkType(NetworkType.valueOf(entity.getNetworkType().trim().toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {
            }
        }

        String strategy = entity.getStrategyType();
        if (strategy != null && !strategy.isBlank()) {
            try {
                order.setStrategyType(StrategyType.valueOf(strategy.trim().toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {
            }
        }
        return order;
    }

    private static String normalizeSide(String side) {
        String normalized = side == null ? "BUY" : side.trim().toUpperCase(Locale.ROOT);
        return "SELL".equals(normalized) ? "SELL" : "BUY";
    }

    private static StrategyType parseStrategyOrThrow(String strategyType) {
        if (strategyType == null || strategyType.isBlank()) {
            throw new IllegalArgumentException("strategyType is blank (legacy call)");
        }
        try {
            return StrategyType.valueOf(strategyType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown strategyType=" + strategyType, e);
        }
    }

    private String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String strip(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static String normalizeSymbolOrThrow(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is null");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("symbol is blank");
        }
        return normalized;
    }

    private static String defaultTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return "1m";
        }
        return timeframe.trim();
    }

    private static String defaultRole(String role, String def) {
        if (role == null || role.isBlank()) {
            return def;
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal calcQtyFromQuote(BigDecimal quoteAmount, BigDecimal price) {
        if (quoteAmount == null || quoteAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (price == null || price.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal safeMul(BigDecimal a, BigDecimal b) {
        return QtyMath.mul(a, b);
    }

    private static BigDecimal minNotionalSafe(GuardResult guard) {
        return guard != null ? guard.minNotional() : null;
    }
}



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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private static final int QTY_SCALE = 8;

    /** микродопуск, чтобы BUY не блокировался из-за округления */
    private static final BigDecimal BUY_BUDGET_EPS = new BigDecimal("1.0005"); // +0.05%

    private final OrderRepository orderRepository;
    private final StrategyLivePublisher livePublisher;

    private final ExchangeSettingsService exchangeSettingsService;
    private final List<ExchangeClient> exchangeClients;

    private final ExchangeAIGuard aiGuard;
    private final MarketSymbolService marketSymbolService;

    private final TradeJournalGateway tradeJournalGateway;

    // =====================================================
    // ✅ НОВОЕ API (строгое, из интерфейса)
    // =====================================================

    @Override
    @Transactional
    public Order placeMarket(OrderContext ctx,
                             OrderSide side,
                             BigDecimal amount,
                             BigDecimal executionPrice) {

        if (side == null) throw new IllegalArgumentException("side is null");
        return doPlaceMarket(ctx, side.name(), amount, executionPrice);
    }

    @Override
    @Transactional
    public Order placeLimit(OrderContext ctx,
                            OrderSide side,
                            BigDecimal quantity,
                            BigDecimal limitPrice,
                            String timeInForce) {

        if (side == null) throw new IllegalArgumentException("side is null");
        return doPlaceLimit(ctx, side.name(), quantity, limitPrice, timeInForce);
    }

    /**
     * ✅ OCO:
     * 1) Если биржа поддерживает OCO — реально ставим на биржу.
     * 2) Если не поддерживает — сохраняем локально как "виртуальный OCO" (под твой fallback/монитор).
     */
    @Override
    @Transactional
    public Order placeOco(OrderContext ctx,
                          BigDecimal quantity,
                          BigDecimal takeProfitPrice,
                          BigDecimal stopPrice,
                          BigDecimal stopLimitPrice) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType st = requireStrategy(ctx.strategyType());
        String symbol = normalizeSymbolOrThrow(ctx.symbol());

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) exchangeName = resolveDefaultExchange(chatId);

        ExchangeClient client = resolveClientOrThrow(exchangeName);

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "OCO");

        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity invalid");
        if (takeProfitPrice != null && takeProfitPrice.signum() <= 0) throw new IllegalArgumentException("takeProfitPrice invalid");
        if (stopPrice != null && stopPrice.signum() <= 0) throw new IllegalArgumentException("stopPrice invalid");
        if (stopLimitPrice != null && stopLimitPrice.signum() <= 0) throw new IllegalArgumentException("stopLimitPrice invalid");

        if (takeProfitPrice == null && stopPrice == null && stopLimitPrice == null) {
            throw new IllegalArgumentException("OCO requires at least one price (tp/stop/stopLimit)");
        }

        // если stopLimit не задан, часто достаточно stopPrice
        BigDecimal slPrice = (stopLimitPrice != null ? stopLimitPrice : stopPrice);

        GuardResult guard = GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(quantity)
                .finalPrice(takeProfitPrice != null ? takeProfitPrice : slPrice)
                .warnings(List.of())
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, "SELL", guard
        );

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        try {
            tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
            tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);
        } catch (Exception e) {
            log.debug("TradeJournal link skipped: {}", e.toString());
        }

        // =====================================================
        // ✅ Пытаемся поставить OCO на бирже
        // =====================================================
        ExchangeClient.OcoResult ocoResult = null;
        boolean ocoPlacedOnExchange = false;

        try {
            Map<String, String> extra = new LinkedHashMap<>();
            // ключ универсальный; в реализации биржи маппишь на newClientOrderId/orderLinkId и т.д.
            extra.put("clientOrderId", clientOrderId);

            log.info("📤 [OCO->EXCHANGE] chatId={} ex={} net={} sym={} qty={} tp={} stop={} stopLimit={} st={} cid={} role={}",
                    chatId, exchangeName, networkType, symbol,
                    strip(quantity), strip(takeProfitPrice), strip(stopPrice), strip(stopLimitPrice),
                    st, correlationId, role);

            ocoResult = client.placeOcoOrder(
                    chatId,
                    networkType,
                    symbol,
                    quantity,
                    takeProfitPrice,
                    stopPrice,
                    stopLimitPrice,
                    extra
            );
            ocoPlacedOnExchange = true;

            log.info("✅ [OCO PLACED] chatId={} ex={} net={} sym={} listId={} status={} tpOrderId={} slOrderId={} cid={}",
                    chatId, exchangeName, networkType, symbol,
                    (ocoResult != null ? ocoResult.orderListId() : "null"),
                    (ocoResult != null ? ocoResult.status() : "null"),
                    (ocoResult != null ? ocoResult.orderIdTp() : "null"),
                    (ocoResult != null ? ocoResult.orderIdSl() : "null"),
                    correlationId);

        } catch (UnsupportedOperationException uoe) {
            // Нормально: не все биржи имеют OCO (Bybit spot часто нет, OKX зависит)
            log.warn("⚠️ OCO NOT SUPPORTED -> fallback-local chatId={} ex={} net={} sym={} msg={} cid={}",
                    chatId, exchangeName, networkType, symbol, uoe.getMessage(), correlationId);
        } catch (Exception e) {
            // Если биржа “умеет”, но упала — это уже ошибка (чтобы не думать что риск закрыт)
            log.error("❌ [OCO EXCHANGE FAILED] chatId={} ex={} net={} sym={} err={} cid={}",
                    chatId, exchangeName, networkType, symbol, e.toString(), correlationId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }

        // =====================================================
        // ✅ Сохраняем в БД (и для биржевого OCO, и для fallback)
        // =====================================================

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide("SELL");
        entity.setQuantity(quantity);
        entity.setStrategyType(st.name());
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setStopLossPrice(slPrice);

        BigDecimal ref = (takeProfitPrice != null) ? takeProfitPrice : slPrice;
        entity.setPrice(ref);
        if (ref != null) entity.setTotal(ref.multiply(quantity));

        // статус: если реально поставили на биржу — NEW/OPEN, если fallback — NEW (виртуальный)
        String status = "NEW";
        if (ocoPlacedOnExchange && ocoResult != null && ocoResult.status() != null && !ocoResult.status().isBlank()) {
            status = ocoResult.status().trim().toUpperCase(Locale.ROOT);
        }
        entity.setStatus(status);
        entity.setFilled(false);

        orderRepository.save(entity);

        return mapToDto(entity);
    }

    // =====================================================
    // ⚠️ Старое API — делегируем в новое
    // =====================================================

    @Override
    @Transactional
    public Order placeMarket(Long chatId,
                             String symbol,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice,
                             String strategyType) {

        if (chatId == null) throw new IllegalArgumentException("chatId is null");

        StrategyType st = parseStrategyOrThrow(strategyType);

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        String sym = normalizeSymbolOrThrow(symbol);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                sym,
                "1m",
                null,
                "ENTRY",
                ex,
                net
        );

        // Legacy: раньше BUY.quantity означал baseQty
        // Новый API: BUY.amount = quoteAmount -> baseQty*price
        String sideNorm = normalizeSide(side);

        BigDecimal amount;
        if ("BUY".equals(sideNorm)) {
            BigDecimal px = (executionPrice != null && executionPrice.signum() > 0) ? executionPrice : null;
            if (px == null) throw new IllegalArgumentException("executionPrice required for BUY (legacy)");
            amount = safeMul(quantity, px);
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

        if (chatId == null) throw new IllegalArgumentException("chatId is null");

        StrategyType st = parseStrategyOrThrow(strategyType);

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                normalizeSymbolOrThrow(symbol),
                "1m",
                null,
                "ENTRY",
                ex,
                net
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

        if (chatId == null) throw new IllegalArgumentException("chatId is null");

        StrategyType st = parseStrategyOrThrow(strategyType);

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                normalizeSymbolOrThrow(symbol),
                "1m",
                null,
                "OCO",
                ex,
                net
        );

        return placeOco(ctx, quantity, takeProfitPrice, stopPrice, stopLimitPrice);
    }

    // =====================================================
    // CANCEL / OPEN / HISTORY / CREATE
    // =====================================================

    @Override
    @Transactional
    public boolean cancelOrder(Long chatId, Long orderId) {
        if (chatId == null || orderId == null) return false;

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
        if (chatId == null) return 0;

        String sym = normalizeSymbolOrThrow(symbol);
        List<String> openStatuses = List.of("NEW", "OPEN", "PARTIALLY_FILLED");

        List<OrderEntity> list = orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, sym, openStatuses);

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
        if (chatId == null) return List.of();

        String sym = normalizeSymbolOrThrow(symbol);

        return orderRepository
                .findByChatIdAndSymbolAndStatusIn(chatId, sym, List.of("NEW", "OPEN", "PARTIALLY_FILLED"))
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        String sym = normalizeSymbolOrThrow(symbol);

        return orderRepository
                .findByChatIdAndSymbolOrderByTimestampAsc(chatId, sym)
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
    public Order createOrder(Order order) {
        if (order == null) throw new IllegalArgumentException("order is null");

        OrderEntity e = new OrderEntity();
        e.setChatId(order.getChatId());
        e.setUserId(order.getChatId());
        e.setSymbol(normalizeSymbolOrThrow(order.getSymbol()));
        e.setSide(normalizeSide(order.getSide()));
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
    // INTERNAL: MARKET / LIMIT (строгая логика)
    // =====================================================

    private Order doPlaceMarket(OrderContext ctx,
                                String side,
                                BigDecimal amount,
                                BigDecimal executionPrice) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType st = requireStrategy(ctx.strategyType());

        String symbol = normalizeSymbolOrThrow(ctx.symbol());
        String sideNorm = normalizeSide(side);

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        BigDecimal priceHint = (executionPrice != null && executionPrice.signum() > 0)
                ? executionPrice
                : fetchPriceHintOrThrow(ctx, symbol);

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) exchangeName = resolveDefaultExchange(chatId);

        ExchangeClient client = resolveClientOrThrow(exchangeName);

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);

        // BUY: amount=quote, requestedQty=quote/price
        // SELL: amount=baseQty
        final BigDecimal quoteAmount = "BUY".equals(sideNorm) ? amount : null;

        final BigDecimal requestedQty = "BUY".equals(sideNorm)
                ? calcQtyFromQuote(quoteAmount, priceHint)
                : amount;

        if (requestedQty == null || requestedQty.signum() <= 0) {
            throw new IllegalArgumentException("calculated qty <= 0");
        }

        GuardResult guard = (descriptor != null)
                ? aiGuard.validateAndAdjust(exchangeName, descriptor, requestedQty, priceHint, true, false)
                : GuardResult.builder()
                .ok(true).adjusted(false)
                .finalQty(requestedQty)
                .finalPrice(priceHint)
                .warnings(List.of("SYMBOL_DESCRIPTOR_NULL"))
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK MARKET chatId={} ex={} net={} sym={} side={} reqQty={} price={} errors={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(requestedQty), strip(priceHint), guard.errors(), correlationId);
            throw new IllegalArgumentException("AI-GUARD BLOCKED MARKET ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        if (finalQty == null || finalQty.signum() <= 0) throw new IllegalArgumentException("finalQty invalid after guard");
        if (finalPrice == null || finalPrice.signum() <= 0) finalPrice = priceHint;

        // BUY budget pre-check
        if ("BUY".equals(sideNorm)) {
            BigDecimal notional = safeMul(finalQty, finalPrice);
            if (notional == null || notional.signum() <= 0) throw new IllegalArgumentException("notional invalid after guard");

            BigDecimal maxAllowed = quoteAmount.multiply(BUY_BUDGET_EPS);
            if (notional.compareTo(maxAllowed) > 0) {
                log.warn("💥 BUY BLOCKED: budget exceeded chatId={} ex={} net={} sym={} quote={} notional={} qty={} price={} cid={}",
                        chatId, exchangeName, networkType, symbol,
                        strip(quoteAmount), strip(notional), strip(finalQty), strip(finalPrice), correlationId);
                throw new IllegalArgumentException("BUDGET_EXCEEDED: notional=" + strip(notional) + " > quote=" + strip(quoteAmount));
            }
        }

        // clientOrderId + journal link
        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        try {
            tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
            tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);
        } catch (Exception e) {
            log.debug("TradeJournal link skipped: {}", e.toString());
        }

        // Реальный MARKET на биржу
        Order exec;
        try {
            OrderSide os = "BUY".equals(sideNorm) ? OrderSide.BUY : OrderSide.SELL;

            BigDecimal amountToSend;
            ExchangeClient.OrderAmountType amountType;

            if (os == OrderSide.BUY) {
                amountToSend = quoteAmount;
                amountType = ExchangeClient.OrderAmountType.QUOTE_QTY;
            } else {
                amountToSend = finalQty;
                amountType = ExchangeClient.OrderAmountType.BASE_QTY;
            }

            log.info("📤 [MARKET->EXCHANGE] chatId={} ex={} net={} sym={} side={} amount={} amountType={} priceHint={} st={} cid={} role={} minNotional={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(amountToSend), amountType, strip(finalPrice), st, correlationId, role, strip(minNotionalSafe(guard)));

            exec = client.placeMarketOrder(
                    chatId,
                    networkType,
                    symbol,
                    os,
                    amountToSend,
                    amountType,
                    finalPrice
            );

        } catch (Exception e) {
            log.error("❌ [MARKET EXCHANGE FAILED] chatId={} ex={} net={} sym={} side={} err={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm, e.toString(), correlationId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }

        BigDecimal executedQty = (exec != null && exec.getQuantity() != null && exec.getQuantity().signum() > 0)
                ? exec.getQuantity()
                : finalQty;

        BigDecimal executedPrice = (exec != null && exec.getPrice() != null && exec.getPrice().signum() > 0)
                ? exec.getPrice()
                : finalPrice;

        String status = (exec != null && exec.getStatus() != null && !exec.getStatus().isBlank())
                ? exec.getStatus().trim().toUpperCase(Locale.ROOT)
                : "FILLED";

        boolean filled = (exec != null) ? exec.isFilled() : "FILLED".equalsIgnoreCase(status);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(executedPrice);
        entity.setQuantity(executedQty);
        entity.setStrategyType(st.name());
        entity.setStatus(status);
        entity.setFilled(filled);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        if (executedPrice != null && executedQty != null) {
            entity.setTotal(executedPrice.multiply(executedQty));
        }

        orderRepository.save(entity);

        publishTradeSafe(chatId, st, symbol, sideNorm, executedPrice, executedQty);

        log.info("✅ [MARKET SAVED] chatId={} ex={} net={} sym={} side={} qty={} price={} status={} filled={} cid={}",
                chatId, exchangeName, networkType, symbol, sideNorm,
                strip(executedQty), strip(executedPrice), status, filled, correlationId);

        return mapToDto(entity);
    }

    private Order doPlaceLimit(OrderContext ctx,
                               String side,
                               BigDecimal quantity,
                               BigDecimal limitPrice,
                               String timeInForce) {

        requireCtx(ctx);

        Long chatId = ctx.chatId();
        StrategyType st = requireStrategy(ctx.strategyType());

        String symbol = normalizeSymbolOrThrow(ctx.symbol());
        String sideNorm = normalizeSide(side);

        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity invalid");
        if (limitPrice == null || limitPrice.signum() <= 0) throw new IllegalArgumentException("limitPrice invalid");

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = ctx.networkType() != null ? ctx.networkType() : NetworkType.MAINNET;
        if (exchangeName.isBlank()) exchangeName = resolveDefaultExchange(chatId);

        ExchangeClient client = resolveClientOrThrow(exchangeName);

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);

        GuardResult guard = (descriptor != null)
                ? aiGuard.validateAndAdjust(exchangeName, descriptor, quantity, limitPrice, false, false)
                : GuardResult.builder()
                .ok(true).adjusted(false)
                .finalQty(quantity)
                .finalPrice(limitPrice)
                .warnings(List.of("SYMBOL_DESCRIPTOR_NULL"))
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK LIMIT chatId={} ex={} net={} sym={} side={} qty={} price={} errors={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(quantity), strip(limitPrice), guard.errors(), correlationId);
            throw new IllegalArgumentException("AI-GUARD BLOCKED LIMIT ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        if (finalQty == null || finalQty.signum() <= 0) throw new IllegalArgumentException("finalQty invalid after guard");
        if (finalPrice == null || finalPrice.signum() <= 0) finalPrice = limitPrice;

        String tf = (timeInForce == null || timeInForce.isBlank())
                ? "GTC"
                : timeInForce.trim().toUpperCase(Locale.ROOT);

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        try {
            tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
            tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);
        } catch (Exception e) {
            log.debug("TradeJournal link skipped: {}", e.toString());
        }

        ExchangeClient.OrderResult placed;
        try {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("timeInForce", tf);
            extra.put("clientOrderId", clientOrderId);

            log.info("📤 [LIMIT->EXCHANGE] chatId={} ex={} net={} sym={} side={} qty={} price={} tif={} st={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(finalQty), strip(finalPrice), tf, st, correlationId);

            placed = client.placeOrder(
                    chatId,
                    networkType,
                    symbol,
                    sideNorm,
                    "LIMIT",
                    finalQty,
                    finalPrice,
                    extra
            );

        } catch (Exception e) {
            log.error("❌ [LIMIT EXCHANGE FAILED] chatId={} ex={} net={} sym={} side={} err={} cid={}",
                    chatId, exchangeName, networkType, symbol, sideNorm, e.toString(), correlationId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }

        String status = "NEW";
        if (placed != null && placed.status() != null && !placed.status().isBlank()) {
            status = placed.status().trim().toUpperCase(Locale.ROOT);
        }
        boolean filled = "FILLED".equalsIgnoreCase(status);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(st.name());
        entity.setStatus(status);
        entity.setFilled(filled);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTotal(finalPrice.multiply(finalQty));

        orderRepository.save(entity);

        log.info("✅ [LIMIT SAVED] chatId={} ex={} net={} sym={} side={} qty={} price={} status={} filled={} cid={}",
                chatId, exchangeName, networkType, symbol, sideNorm,
                strip(finalQty), strip(finalPrice), status, filled, correlationId);

        return mapToDto(entity);
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static void requireCtx(OrderContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("OrderContext is null");
        if (ctx.chatId() == null) throw new IllegalArgumentException("chatId is null");
        if (ctx.symbol() == null || ctx.symbol().isBlank()) throw new IllegalArgumentException("symbol is blank");
    }

    private static StrategyType requireStrategy(StrategyType st) {
        if (st == null) throw new IllegalArgumentException("strategyType is null in OrderContext");
        return st;
    }

    private ExchangeClient resolveClientOrThrow(String exchangeName) {
        String ex = safeUpper(exchangeName);
        if (ex.isBlank()) throw new IllegalArgumentException("exchangeName is blank");

        for (ExchangeClient c : exchangeClients) {
            if (c == null) continue;
            String name = c.getExchangeName();
            if (name != null && ex.equalsIgnoreCase(name.trim())) {
                return c;
            }
        }

        String available = exchangeClients.stream()
                .filter(Objects::nonNull)
                .map(ExchangeClient::getExchangeName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(", "));

        throw new IllegalStateException("ExchangeClient not found for exchange=" + ex + ". Available: " + available);
    }

    private BigDecimal fetchPriceHintOrThrow(OrderContext ctx, String symbol) {
        String exchangeName = safeUpper(ctx.exchangeName());
        if (exchangeName.isBlank() && ctx.chatId() != null) {
            exchangeName = resolveDefaultExchange(ctx.chatId());
        }

        ExchangeClient client = resolveClientOrThrow(exchangeName);

        try {
            double px = client.getPrice(symbol);
            if (px <= 0) throw new IllegalStateException("exchange returned price<=0");
            return BigDecimal.valueOf(px);
        } catch (Exception e) {
            throw new IllegalArgumentException("executionPrice is missing and cannot fetch price from exchange: " + e.getMessage(), e);
        }
    }

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

        try {
            return tradeJournalGateway.recordIntent(
                    chatId,
                    st,
                    exchangeName,
                    networkType,
                    symbol,
                    timeframe,
                    "BUY".equals(sideNorm) ? TradeIntentEvent.Signal.BUY : TradeIntentEvent.Signal.SELL,
                    guard.ok() ? TradeIntentEvent.Decision.ALLOW : TradeIntentEvent.Decision.REJECT,
                    guard.ok() ? "OK" : "AI_GUARD_BLOCK",
                    null, null, null,
                    null,
                    null,
                    null
            );
        } catch (Exception e) {
            return "cid-" + chatId + "-" + System.currentTimeMillis();
        }
    }

    private SymbolDescriptor resolveSymbolDescriptor(String exchangeName, NetworkType networkType, String symbol) {
        if (exchangeName == null || exchangeName.isBlank() || symbol == null || symbol.isBlank()) return null;

        try {
            return marketSymbolService.getSymbolInfo(
                    exchangeName,
                    networkType != null ? networkType : NetworkType.MAINNET,
                    "USDT",
                    symbol
            );
        } catch (Exception e) {
            log.warn("⚠️ Cannot resolve SymbolDescriptor ex={} net={} symbol={}", exchangeName, networkType, symbol, e);
            return null;
        }
    }

    private String resolveDefaultExchange(Long chatId) {
        try {
            return exchangeSettingsService.findAllByChatId(chatId)
                    .stream()
                    .map(ExchangeSettings::getExchange)
                    .filter(s -> s != null && !s.isBlank())
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
        o.setFilled(Boolean.TRUE.equals(e.getFilled()));
        o.setTime(e.getTimestamp());

        String st = e.getStrategyType();
        if (st != null && !st.isBlank()) {
            try {
                o.setStrategyType(StrategyType.valueOf(st.trim().toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) { }
        }
        return o;
    }

    private static String normalizeSide(String side) {
        String s = side == null ? "BUY" : side.trim().toUpperCase(Locale.ROOT);
        return ("SELL".equals(s)) ? "SELL" : "BUY";
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

    private String safeUpper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static String normalizeSymbolOrThrow(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("symbol is null");
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("symbol is blank");
        return s;
    }

    private static String defaultTimeframe(String tf) {
        if (tf == null || tf.isBlank()) return "1m";
        return tf.trim();
    }

    private static String defaultRole(String role, String def) {
        if (role == null || role.isBlank()) return def;
        return role.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal calcQtyFromQuote(BigDecimal quoteAmount, BigDecimal price) {
        if (quoteAmount == null || quoteAmount.signum() <= 0) return BigDecimal.ZERO;
        if (price == null || price.signum() <= 0) return BigDecimal.ZERO;
        return quoteAmount.divide(price, QTY_SCALE, RoundingMode.DOWN);
    }

    private static BigDecimal safeMul(BigDecimal a, BigDecimal b) {
        return QtyMath.mul(a, b);
    }

    /**
     * ✅ Защита от компиляции: если у GuardResult нет minNotional() — просто вернём null.
     */
    private static BigDecimal minNotionalSafe(GuardResult guard) {
        if (guard == null) return null;
        try {
            Method m = guard.getClass().getMethod("minNotional");
            Object v = m.invoke(guard);
            return (v instanceof BigDecimal) ? (BigDecimal) v : null;
        } catch (Exception ignore) {
            return null;
        }
    }
}

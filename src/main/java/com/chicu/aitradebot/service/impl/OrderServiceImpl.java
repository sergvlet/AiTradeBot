package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.OrderEntity;
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
import com.chicu.aitradebot.service.OrderService.OrderContext; // ✅ ВАЖНО: если OrderContext вложен в OrderService
import com.chicu.aitradebot.service.TradeJournalGateway;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private static final int QTY_SCALE = 8;

    private final OrderRepository orderRepository;
    private final StrategyLivePublisher livePublisher;

    // exchange/network выбираем НЕ через getByChat(), а из контекста/настроек
    private final ExchangeSettingsService exchangeSettingsService;

    // 🔥 AI-GUARD
    private final ExchangeAIGuard aiGuard;
    private final MarketSymbolService marketSymbolService;

    // ✅ journal gateway (NOOP или DB-реализация)
    private final TradeJournalGateway tradeJournalGateway;

    // =====================================================
    // ✅ НОВОЕ API (с OrderContext)
    // =====================================================

    /**
     * ВАЖНО (взрослая семантика):
     * - side=BUY  -> quantity трактуем как quoteAmount (USDT).
     * - side=SELL -> quantity трактуем как baseQty.
     *
     * BUY никогда не превысит бюджет:
     * - AI-GUARD НЕ имеет права "поднять" qty (minNotional/step) ценой превышения quoteAmount
     * - после guard мы считаем notional=finalQty*finalPrice и блокируем, если он больше quoteAmount
     */
    @Override
    @Transactional
    public Order placeMarket(OrderContext ctx,
                             String side,
                             BigDecimal quantity,
                             BigDecimal executionPrice) {

        if (ctx == null) throw new IllegalArgumentException("OrderContext is null");

        Long chatId = ctx.chatId();
        String symbolRaw = ctx.symbol();

        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbolRaw == null || symbolRaw.isBlank()) throw new IllegalArgumentException("symbol is blank");

        String symbol = normalizeSymbol(symbolRaw);
        String sideNorm = normalizeSide(side);

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("amount/qty must be > 0");
        }
        if (executionPrice == null || executionPrice.signum() <= 0) {
            throw new IllegalArgumentException("executionPrice must be > 0");
        }

        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        // ✅ БИРЖА/СЕТЬ — ТОЛЬКО ИЗ КОНТЕКСТА
        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = (ctx.networkType() != null) ? ctx.networkType() : NetworkType.MAINNET;

        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);

        // =====================================================
        // ✅ BUY: quantity = quoteAmount(USDT) -> requestedQty = quote/price
        // ✅ SELL: quantity = baseQty
        // =====================================================
        final BigDecimal quoteAmount = "BUY".equals(sideNorm) ? quantity : null;

        final BigDecimal requestedQty = "BUY".equals(sideNorm)
                ? calcQtyFromQuote(quoteAmount, executionPrice)
                : quantity;

        if (requestedQty == null || requestedQty.signum() <= 0) {
            throw new IllegalArgumentException("calculated qty <= 0");
        }

        // Для MARKET BUY запрещаем автоподнятие под minNotional: бюджет важнее.
        boolean allowIncreaseToMinNotional = false;

        GuardResult guard = aiGuard.validateAndAdjust(
                exchangeName,
                descriptor,
                requestedQty,
                executionPrice,
                true,
                allowIncreaseToMinNotional
        );

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK MARKET chatId={} ex={} net={} sym={} side={} reqQty={} price={} errors={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(requestedQty), strip(executionPrice), guard.errors());
            throw new IllegalArgumentException("AI-GUARD BLOCKED MARKET ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        if (finalQty == null || finalQty.signum() <= 0) {
            throw new IllegalArgumentException("finalQty invalid after guard");
        }
        if (finalPrice == null || finalPrice.signum() <= 0) {
            throw new IllegalArgumentException("finalPrice invalid after guard");
        }

        // =====================================================
        // ✅ ГЛАВНОЕ: BUY не может превысить quoteAmount (USDT)
        // =====================================================
        if ("BUY".equals(sideNorm)) {
            BigDecimal notional = safeMul(finalQty, finalPrice);
            if (notional == null || notional.signum() <= 0) {
                throw new IllegalArgumentException("notional invalid after guard");
            }

            // микродопуск на округления (0.05%)
            BigDecimal maxAllowed = quoteAmount.multiply(BigDecimal.valueOf(1.0005d));

            if (notional.compareTo(maxAllowed) > 0) {
                log.warn("💥 BUY BLOCKED: budget exceeded chatId={} ex={} net={} sym={} quote={} notional={} qty={} price={} cid={}",
                        chatId, exchangeName, networkType, symbol,
                        strip(quoteAmount), strip(notional), strip(finalQty), strip(finalPrice), correlationId);
                throw new IllegalArgumentException("BUDGET_EXCEEDED: notional=" + strip(notional) + " > quote=" + strip(quoteAmount));
            }
        }

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        log.info("📥 [MARKET] chatId={}, ex={}, net={}, symbol={}, side={}, reqQty={}, finalQty={}, price={}, st={}, cid={}, role={}, quoteAmount={}, notional={}, minNotional={}",
                chatId, exchangeName, networkType, symbol, sideNorm,
                strip(requestedQty), strip(finalQty), strip(finalPrice), st, correlationId, role,
                strip(quoteAmount),
                strip(safeMul(finalQty, finalPrice)),
                strip(guard.minNotional()));

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
        entity.setSide(sideNorm);
        entity.setPrice(finalPrice);
        entity.setQuantity(finalQty);
        entity.setStrategyType(st.name());
        entity.setStatus("FILLED");
        entity.setFilled(true);
        entity.setTimestamp(System.currentTimeMillis());
        entity.setCreatedAt(LocalDateTime.now());

        entity.setTotal(finalPrice.multiply(finalQty));

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
        String symbolRaw = ctx.symbol();

        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbolRaw == null || symbolRaw.isBlank()) throw new IllegalArgumentException("symbol is blank");

        String symbol = normalizeSymbol(symbolRaw);
        String sideNorm = normalizeSide(side);

        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity invalid");
        if (limitPrice == null || limitPrice.signum() <= 0) throw new IllegalArgumentException("limitPrice invalid");

        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = (ctx.networkType() != null) ? ctx.networkType() : NetworkType.MAINNET;

        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "ENTRY");

        SymbolDescriptor descriptor = resolveSymbolDescriptor(exchangeName, networkType, symbol);

        GuardResult guard = aiGuard.validateAndAdjust(
                exchangeName,
                descriptor,
                quantity,
                limitPrice,
                false,
                false // LIMIT: qty вверх не поднимаем
        );

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, sideNorm, guard
        );

        if (!guard.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK LIMIT chatId={} ex={} net={} sym={} side={} qty={} price={} errors={}",
                    chatId, exchangeName, networkType, symbol, sideNorm,
                    strip(quantity), strip(limitPrice), guard.errors());
            throw new IllegalArgumentException("AI-GUARD BLOCKED LIMIT ORDER: " + String.join("; ", guard.errors()));
        }

        BigDecimal finalQty = guard.finalQty();
        BigDecimal finalPrice = guard.finalPrice();

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
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
        String symbolRaw = ctx.symbol();

        if (chatId == null) throw new IllegalArgumentException("chatId is null");
        if (symbolRaw == null || symbolRaw.isBlank()) throw new IllegalArgumentException("symbol is blank");

        String symbol = normalizeSymbol(symbolRaw);
        StrategyType st = (ctx.strategyType() != null) ? ctx.strategyType() : StrategyType.values()[0];

        String exchangeName = safeUpper(ctx.exchangeName());
        NetworkType networkType = (ctx.networkType() != null) ? ctx.networkType() : NetworkType.MAINNET;

        if (exchangeName.isBlank()) {
            exchangeName = resolveDefaultExchange(chatId);
        }

        String timeframe = defaultTimeframe(ctx.timeframe());
        String role = defaultRole(ctx.role(), "OCO");

        // ✅ базовая валидация, чтобы не плодить мусорные OCO
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity invalid");
        if (takeProfitPrice != null && takeProfitPrice.signum() <= 0) throw new IllegalArgumentException("takeProfitPrice invalid");
        if (stopPrice != null && stopPrice.signum() <= 0) throw new IllegalArgumentException("stopPrice invalid");
        if (stopLimitPrice != null && stopLimitPrice.signum() <= 0) throw new IllegalArgumentException("stopLimitPrice invalid");
        if (takeProfitPrice == null && stopPrice == null && stopLimitPrice == null) {
            throw new IllegalArgumentException("OCO requires at least one price (tp/stop/stopLimit)");
        }

        GuardResult guard = GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(quantity)
                .finalPrice(takeProfitPrice)
                .warnings(List.of())
                .errors(List.of())
                .build();

        String correlationId = ensureCorrelationId(
                ctx, chatId, st, exchangeName, networkType, symbol, timeframe, "SELL", guard
        );

        String clientOrderId = OrderCorrelation.clientOrderId(correlationId, chatId, st, symbol, role);
        tradeJournalGateway.attachClientOrderId(correlationId, clientOrderId);
        tradeJournalGateway.linkClientOrder(chatId, st, exchangeName, networkType, symbol, timeframe, correlationId, clientOrderId, role);

        OrderEntity entity = new OrderEntity();
        entity.setChatId(chatId);
        entity.setUserId(chatId);
        entity.setSymbol(symbol);
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

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                normalizeSymbol(symbol),
                "1m",
                null,
                "ENTRY",
                ex,
                net
        );

        // ⚠️ Legacy совместимость:
        // Раньше BUY.quantity означал baseQty.
        // Теперь NEW placeMarket(ctx, BUY, amount, price) ждёт amount=quoteAmount.
        // Поэтому для BUY конвертируем baseQty -> quoteAmount = baseQty * price.
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

        return placeMarket(ctx, side, amount, executionPrice);
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

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                normalizeSymbol(symbol),
                "1m",
                null,
                "ENTRY",
                ex,
                net
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

        String ex = resolveDefaultExchange(chatId);
        NetworkType net = resolveDefaultNetwork(chatId, ex);

        OrderContext ctx = new OrderContext(
                chatId,
                st,
                normalizeSymbol(symbol),
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

        String sym = normalizeSymbol(symbol);

        List<OrderEntity> list =
                orderRepository.findByChatIdAndSymbolAndStatusIn(chatId, sym, openStatuses);

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
        String sym = normalizeSymbol(symbol);
        return orderRepository
                .findByChatIdAndSymbolAndStatusIn(
                        chatId,
                        sym,
                        List.of("NEW", "OPEN", "PARTIALLY_FILLED")
                )
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByChatIdAndSymbol(long chatId, String symbol) {
        String sym = normalizeSymbol(symbol);
        return orderRepository
                .findByChatIdAndSymbolOrderByTimestampAsc(chatId, sym)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderEntity> getOrderEntitiesByChatIdAndSymbol(long chatId, String symbol) {
        return orderRepository.findByChatIdAndSymbolOrderByTimestampAsc(chatId, normalizeSymbol(symbol));
    }

    @Override
    @Transactional
    public Order createOrder(Order order) {
        OrderEntity e = new OrderEntity();
        e.setChatId(order.getChatId());
        e.setUserId(order.getChatId());
        e.setSymbol(normalizeSymbol(order.getSymbol()));
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
            } catch (Exception ignored) {}
        }

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

    private String safeUpper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.trim().toUpperCase(Locale.ROOT);
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
        if (a == null || b == null) return null;
        return a.multiply(b);
    }
}

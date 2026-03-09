package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InMemoryPositionStoreImpl implements PositionStore {

    /** если старый код не передал symbol */
    private static final String ANY_SYMBOL = "__ANY__";

    private final OrderRepository orderRepository;

    /**
     * in-memory cache уже восстановленных позиций
     */
    private final Map<String, PositionSnapshot> positions = new ConcurrentHashMap<>();

    private static final class OpenLot {
        private BigDecimal qty;
        private final BigDecimal price;
        private final BigDecimal tp;
        private final BigDecimal sl;
        private final Long entryOrderId;
        private final Instant openedAt;

        private OpenLot(BigDecimal qty,
                        BigDecimal price,
                        BigDecimal tp,
                        BigDecimal sl,
                        Long entryOrderId,
                        Instant openedAt) {
            this.qty = qty;
            this.price = price;
            this.tp = tp;
            this.sl = sl;
            this.entryOrderId = entryOrderId;
            this.openedAt = openedAt;
        }
    }

    @Override
    public boolean isInPosition(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol) {

        if (chatId == null || type == null) return false;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            String prefix = prefixKey(chatId, type, ex, netKey);
            for (String k : positions.keySet()) {
                if (k != null && k.startsWith(prefix)) return true;
            }
            return false;
        }

        String k = key(chatId, type, ex, netKey, sym);
        if (positions.containsKey(k)) return true;

        return tryRestorePosition(chatId, type, ex, network, netKey, sym).isPresent();
    }

    @Override
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) sym = ANY_SYMBOL;

        positions.putIfAbsent(
                key(chatId, type, ex, netKey, sym),
                new PositionSnapshot(
                        chatId, type, ex, network, sym,
                        null, null, null, null,
                        null, null, Instant.now()
                )
        );

        if (log.isDebugEnabled()) {
            log.debug("[POS] OPEN(fact) chatId={} type={} ex={} net={} sym={}",
                    chatId, type, ex, netKey, sym);
        }
    }

    @Override
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol,
                           BigDecimal entryPrice,
                           BigDecimal qty,
                           BigDecimal tp,
                           BigDecimal sl,
                           BigDecimal quoteSpent,
                           Long entryOrderId,
                           Instant openedAt) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) sym = ANY_SYMBOL;

        PositionSnapshot snap = new PositionSnapshot(
                chatId,
                type,
                ex,
                network,
                sym,
                positiveOrNull(entryPrice),
                positiveOrNull(qty),
                positiveOrNull(tp),
                positiveOrNull(sl),
                positiveOrNull(quoteSpent),
                entryOrderId,
                openedAt != null ? openedAt : Instant.now()
        );

        positions.put(key(chatId, type, ex, netKey, sym), snap);

        if (log.isDebugEnabled()) {
            log.debug("[POS] OPEN chatId={} type={} ex={} net={} sym={} entryPrice={} qty={} tp={} sl={} orderId={}",
                    chatId, type, ex, netKey, sym,
                    toStr(entryPrice), toStr(qty), toStr(tp), toStr(sl), entryOrderId);
        }
    }

    @Override
    public void markClosed(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            String prefix = prefixKey(chatId, type, ex, netKey);
            List<String> toRemove = new ArrayList<>();

            for (String k : positions.keySet()) {
                if (k != null && k.startsWith(prefix)) {
                    toRemove.add(k);
                }
            }

            toRemove.forEach(positions::remove);

            if (log.isDebugEnabled()) {
                log.debug("[POS] CLOSE(all) chatId={} type={} ex={} net={} removed={}",
                        chatId, type, ex, netKey, toRemove.size());
            }
            return;
        }

        positions.remove(key(chatId, type, ex, netKey, sym));
        positions.remove(key(chatId, type, ex, netKey, ANY_SYMBOL));

        if (log.isDebugEnabled()) {
            log.debug("[POS] CLOSE chatId={} type={} ex={} net={} sym={}",
                    chatId, type, ex, netKey, sym);
        }
    }

    @Override
    public Optional<PositionSnapshot> getPosition(Long chatId,
                                                  StrategyType type,
                                                  String exchange,
                                                  NetworkType network,
                                                  String symbol) {

        if (chatId == null || type == null) return Optional.empty();

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);

        String sym = normUpper(symbol);
        if (sym == null) {
            String prefix = prefixKey(chatId, type, ex, netKey);

            for (Map.Entry<String, PositionSnapshot> e : positions.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith(prefix) && !k.endsWith(":" + ANY_SYMBOL)) {
                    return Optional.ofNullable(e.getValue());
                }
            }

            PositionSnapshot any = positions.get(key(chatId, type, ex, netKey, ANY_SYMBOL));
            return Optional.ofNullable(any);
        }

        PositionSnapshot snap = positions.get(key(chatId, type, ex, netKey, sym));
        if (snap != null) {
            return Optional.of(snap);
        }

        Optional<PositionSnapshot> restored = tryRestorePosition(chatId, type, ex, network, netKey, sym);
        if (restored.isPresent()) {
            return restored;
        }

        return Optional.ofNullable(positions.get(key(chatId, type, ex, netKey, ANY_SYMBOL)));
    }

    @Override
    public void clearPosition(Long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol) {
        markClosed(chatId, type, exchange, network, symbol);
    }

    // =====================================================
    // lazy restore from orders
    // =====================================================

    private Optional<PositionSnapshot> tryRestorePosition(Long chatId,
                                                          StrategyType type,
                                                          String exchange,
                                                          NetworkType network,
                                                          String networkKey,
                                                          String symbol) {

        if (chatId == null || type == null || symbol == null || orderRepository == null) {
            return Optional.empty();
        }

        String k = key(chatId, type, exchange, networkKey, symbol);
        PositionSnapshot cached = positions.get(k);
        if (cached != null) {
            return Optional.of(cached);
        }

        synchronized (positions) {
            cached = positions.get(k);
            if (cached != null) {
                return Optional.of(cached);
            }

            List<OrderEntity> orders = loadContextOrders(chatId, type, symbol, exchange, networkKey);
            if (orders == null || orders.isEmpty()) {
                return Optional.empty();
            }

            List<OpenLot> openLots = new ArrayList<>();

            for (OrderEntity order : orders) {
                if (order == null) continue;
                if (!isFilledOrder(order)) continue;

                String side = normUpper(order.getSide());
                BigDecimal qty = positiveOrNull(order.getQuantity());
                BigDecimal price = positiveOrNull(order.getPrice());

                if (qty == null || price == null) continue;

                if ("BUY".equals(side)) {
                    openLots.add(new OpenLot(
                            qty,
                            price,
                            positiveOrNull(order.getTakeProfitPrice()),
                            positiveOrNull(order.getStopLossPrice()),
                            order.getId(),
                            resolveOrderInstant(order)
                    ));
                    continue;
                }

                if ("SELL".equals(side)) {
                    BigDecimal leftToClose = qty;

                    while (leftToClose.signum() > 0 && !openLots.isEmpty()) {
                        OpenLot first = openLots.get(0);

                        if (first.qty.compareTo(leftToClose) <= 0) {
                            leftToClose = leftToClose.subtract(first.qty);
                            openLots.remove(0);
                        } else {
                            first.qty = first.qty.subtract(leftToClose);
                            leftToClose = BigDecimal.ZERO;
                        }
                    }
                }
            }

            if (openLots.isEmpty()) {
                return Optional.empty();
            }

            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;

            BigDecimal tp = null;
            BigDecimal sl = null;
            Long entryOrderId = null;
            Instant openedAt = null;

            for (OpenLot lot : openLots) {
                if (lot == null || lot.qty == null || lot.qty.signum() <= 0) continue;

                totalQty = totalQty.add(lot.qty);
                totalCost = totalCost.add(lot.qty.multiply(lot.price));

                if (lot.tp != null) tp = lot.tp;
                if (lot.sl != null) sl = lot.sl;

                entryOrderId = lot.entryOrderId;

                if (openedAt == null || (lot.openedAt != null && lot.openedAt.isBefore(openedAt))) {
                    openedAt = lot.openedAt;
                }
            }

            if (totalQty.signum() <= 0 || totalCost.signum() <= 0) {
                return Optional.empty();
            }

            BigDecimal avgEntry = totalCost.divide(totalQty, 12, BigDecimal.ROUND_HALF_UP);

            PositionSnapshot restored = new PositionSnapshot(
                    chatId,
                    type,
                    exchange,
                    network,
                    symbol,
                    avgEntry.stripTrailingZeros(),
                    totalQty.stripTrailingZeros(),
                    tp,
                    sl,
                    totalCost.stripTrailingZeros(),
                    entryOrderId,
                    openedAt != null ? openedAt : Instant.now()
            );

            positions.put(k, restored);

            log.warn("[POS] ♻️ RESTORED chatId={} type={} ex={} net={} sym={} qty={} entry={} tp={} sl={} entryOrderId={}",
                    chatId,
                    type,
                    exchange,
                    networkKey,
                    symbol,
                    toStr(restored.qty()),
                    toStr(restored.entryPrice()),
                    toStr(restored.tp()),
                    toStr(restored.sl()),
                    restored.entryOrderId());

            return Optional.of(restored);
        }
    }

    private List<OrderEntity> loadContextOrders(Long chatId,
                                                StrategyType type,
                                                String symbol,
                                                String exchange,
                                                String networkKey) {

        String strategy = type.name();
        List<OrderEntity> exact = List.of();

        if (exchange != null && networkKey != null) {
            exact = orderRepository
                    .findByChatIdAndStrategyTypeAndSymbolAndExchangeNameAndNetworkTypeOrderByTimestampAsc(
                            chatId, strategy, symbol, exchange, networkKey
                    );
        }

        if (exact != null && !exact.isEmpty()) {
            return exact;
        }

        List<OrderEntity> legacy = orderRepository.findByChatIdAndStrategyTypeAndSymbolOrderByTimestampAsc(
                chatId, strategy, symbol
        );

        if (legacy == null || legacy.isEmpty()) {
            return List.of();
        }

        if (exchange == null && networkKey == null) {
            return legacy;
        }

        List<OrderEntity> filtered = new ArrayList<>();
        for (OrderEntity order : legacy) {
            if (order == null) continue;

            String ox = normUpper(order.getExchangeName());
            String on = normUpper(order.getNetworkType());

            if (ox == null && on == null) {
                filtered.add(order);
                continue;
            }

            if (Objects.equals(ox, exchange) && Objects.equals(on, networkKey)) {
                filtered.add(order);
            }
        }

        return filtered;
    }

    private boolean isFilledOrder(OrderEntity order) {
        if (order == null) return false;
        if (Boolean.TRUE.equals(order.getFilled())) return true;

        String status = normUpper(order.getStatus());
        return "FILLED".equals(status);
    }

    private Instant resolveOrderInstant(OrderEntity order) {
        if (order == null) return Instant.now();

        try {
            if (order.getTimestamp() != null && order.getTimestamp() > 0) {
                return Instant.ofEpochMilli(order.getTimestamp());
            }
        } catch (Exception ignored) {
        }

        try {
            if (order.getCreatedAt() != null) {
                return order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
            }
        } catch (Exception ignored) {
        }

        return Instant.now();
    }

    // =====================================================
    // helpers
    // =====================================================

    private static String key(Long chatId, StrategyType type, String ex, String net, String sym) {
        return prefixKey(chatId, type, ex, net) + ":" + sym;
    }

    private static String prefixKey(Long chatId, StrategyType type, String ex, String net) {
        String t = (type != null ? type.name() : "NA");
        String e = (ex != null ? ex : "NA");
        String n = (net != null ? net : "NA");
        return chatId + ":" + t + ":" + e + ":" + n;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
    }

    private static String toStr(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}
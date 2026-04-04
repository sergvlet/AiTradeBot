package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InMemoryPositionStoreImpl implements PositionStore {

    /** если старый код не передал symbol */
    private static final String ANY_SYMBOL = "__ANY__";

    /** безопасный дефолт для отсечения пыли только при restore из истории */
    private static final BigDecimal DEFAULT_RESTORE_MIN_NOTIONAL = new BigDecimal("5");
    private static final long DEFAULT_RESTORE_RETRY_COOLDOWN_MS = 5_000L;
    private static final long DEFAULT_DUST_LOG_THROTTLE_MS = 60_000L;
    private static final long DEFAULT_DUST_SUPPRESS_RESTORE_MS = 21_600_000L;

    private final OrderRepository orderRepository;

    /**
     * in-memory cache уже восстановленных/открытых позиций.
     * ВАЖНО:
     * Здесь могут лежать реальные позиции после BUY, даже если после комиссии
     * их текущий notional стал < minNotional биржи. Такие позиции удалять нельзя,
     * иначе стратегия "забудет" открытую сделку и не сможет её сопровождать.
     */
    private final Map<String, PositionSnapshot> positions = new ConcurrentHashMap<>();

    /**
     * negative-cache для неудачных restore, чтобы не ходить в БД на каждом тике
     */
    private final Map<String, Instant> restoreMissUntil = new ConcurrentHashMap<>();

    /**
     * throttle для dust-логов, чтобы не засорять журнал
     */
    private final Map<String, Instant> dustSkipLogAt = new ConcurrentHashMap<>();

    /**
     * Явное подавление lazy-restore для dust / проблемных позиций.
     * Нужно, чтобы старая BUY-запись в БД не поднимала позицию заново после неудачного EXIT.
     */
    private final Map<String, Instant> suppressedRestoreUntil = new ConcurrentHashMap<>();

    /**
     * Если true — остатки позиции меньше порога не восстанавливаются как активные ИЗ ИСТОРИИ.
     * Это не должно вырубать уже открытую runtime-позицию.
     */
    @Value("${trade.position-restore.drop-dust:true}")
    private boolean dropDustOnRestore;

    /**
     * Минимальный notional для восстановления позиции из БД.
     * Нужен именно для restore, чтобы не поднимать «мертвую пыль».
     */
    @Value("${trade.position-restore.min-notional:5.00}")
    private BigDecimal restoreMinNotional;

    /**
     * На сколько миллисекунд запоминать неудачную попытку restore.
     * Нужен, чтобы при отсутствии/пыли не дёргать историю на каждом тике.
     */
    @Value("${trade.position-restore.retry-cooldown-ms:5000}")
    private long restoreRetryCooldownMs;

    /**
     * Throttle для dust-логов.
     */
    @Value("${trade.position-restore.dust-log-throttle-ms:60000}")
    private long dustLogThrottleMs;

    /**
     * На сколько подавлять повторное восстановление dust-позиции.
     */
    @Value("${trade.position-restore.dust-suppress-ms:21600000}")
    private long dustSuppressRestoreMs;

    /**
     * PROD: lazy-restore из истории ордеров выключен по умолчанию.
     * Иначе после рестарта легко поднять фантомную позицию, которой уже нет на бирже.
     */
    @Value("${trade.position-restore.from-order-history.enabled:false}")
    private boolean restoreFromOrderHistoryEnabled;

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
                if (k != null && k.startsWith(prefix)) {
                    PositionSnapshot snap = positions.get(k);
                    if (snap != null) {
                        return true;
                    }
                }
            }
            return false;
        }

        String k = key(chatId, type, ex, netKey, sym);
        PositionSnapshot cached = positions.get(k);
        if (cached != null) {
            return true;
        }

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

        String storeKey = key(chatId, type, ex, netKey, sym);

        positions.putIfAbsent(
                storeKey,
                new PositionSnapshot(
                        chatId, type, ex, network, sym,
                        null, null, null, null,
                        null, null, Instant.now()
                )
        );
        clearRestoreCaches(storeKey);

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

        String storeKey = key(chatId, type, ex, netKey, sym);
        positions.put(storeKey, snap);
        clearRestoreCaches(storeKey);

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
            clearRestoreCachesByPrefix(prefix);

            if (log.isDebugEnabled()) {
                log.debug("[POS] CLOSE(all) chatId={} type={} ex={} net={} removed={}",
                        chatId, type, ex, netKey, toRemove.size());
            }
            return;
        }

        clearRestoreCaches(key(chatId, type, ex, netKey, sym));
        clearRestoreCaches(key(chatId, type, ex, netKey, ANY_SYMBOL));

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
                PositionSnapshot snap = e.getValue();

                if (k != null
                        && k.startsWith(prefix)
                        && !k.endsWith(":" + ANY_SYMBOL)
                        && snap != null) {
                    return Optional.of(snap);
                }
            }

            PositionSnapshot any = positions.get(key(chatId, type, ex, netKey, ANY_SYMBOL));
            return Optional.ofNullable(any);
        }

        String posKey = key(chatId, type, ex, netKey, sym);
        PositionSnapshot snap = positions.get(posKey);
        if (snap != null) {
            return Optional.of(snap);
        }

        Optional<PositionSnapshot> restored = tryRestorePosition(chatId, type, ex, network, netKey, sym);
        if (restored.isPresent()) {
            return restored;
        }

        PositionSnapshot any = positions.get(key(chatId, type, ex, netKey, ANY_SYMBOL));
        return Optional.ofNullable(any);
    }

    @Override
    public void clearPosition(Long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol) {
        markClosed(chatId, type, exchange, network, symbol);
    }

    public void suppressRestore(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol,
                                long ttlMs,
                                String reason) {

        if (chatId == null || type == null) return;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);
        String sym = normUpper(symbol);
        if (sym == null) sym = ANY_SYMBOL;

        long effectiveTtlMs = Math.max(
                60_000L,
                ttlMs > 0 ? ttlMs : positiveMillisOrDefault(dustSuppressRestoreMs, DEFAULT_DUST_SUPPRESS_RESTORE_MS)
        );
        Instant until = Instant.now().plusMillis(effectiveTtlMs);

        String exactKey = key(chatId, type, ex, netKey, sym);
        String anyKey = key(chatId, type, ex, netKey, ANY_SYMBOL);

        positions.remove(exactKey);
        positions.remove(anyKey);

        restoreMissUntil.put(exactKey, until);
        restoreMissUntil.put(anyKey, until);
        suppressedRestoreUntil.put(exactKey, until);
        suppressedRestoreUntil.put(anyKey, until);

        log.warn("[POS] ⛔ SUPPRESS RESTORE chatId={} type={} ex={} net={} sym={} until={} reason={}",
                chatId,
                type,
                ex,
                netKey,
                sym,
                until,
                reason);
    }

    public boolean isRestoreSuppressed(Long chatId,
                                       StrategyType type,
                                       String exchange,
                                       NetworkType network,
                                       String symbol) {

        if (chatId == null || type == null) return false;

        String ex = normUpper(exchange);
        String netKey = normUpper(network != null ? network.name() : null);
        String sym = normUpper(symbol);
        if (sym == null) sym = ANY_SYMBOL;

        Instant now = Instant.now();

        String exactKey = key(chatId, type, ex, netKey, sym);
        if (isRestoreSuppressed(exactKey, now)) {
            return true;
        }

        String anyKey = key(chatId, type, ex, netKey, ANY_SYMBOL);
        return isRestoreSuppressed(anyKey, now);
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

        if (!restoreFromOrderHistoryEnabled) {
            return Optional.empty();
        }

        String k = key(chatId, type, exchange, networkKey, symbol);
        Instant now = Instant.now();

        if (isRestoreSuppressed(k, now)) {
            return Optional.empty();
        }

        if (isRestoreRetryOnCooldown(k, now)) {
            return Optional.empty();
        }

        PositionSnapshot cached = positions.get(k);
        if (cached != null) {
            clearRestoreCaches(k);
            return Optional.of(cached);
        }

        synchronized (positions) {
            cached = positions.get(k);
            if (cached != null) {
                clearRestoreCaches(k);
                return Optional.of(cached);
            }

            List<OrderEntity> orders = loadContextOrders(chatId, type, symbol, exchange, networkKey);
            if (orders == null || orders.isEmpty()) {
                rememberRestoreMiss(k, now);
                return Optional.empty();
            }

            List<OpenLot> openLots = new ArrayList<>();

            for (OrderEntity order : orders) {
                if (order == null) continue;

                String side = normUpper(order.getSide());
                BigDecimal qty = positiveOrNull(order.getQuantity());
                BigDecimal price = positiveOrNull(order.getPrice());

                if (qty == null || price == null) continue;

                if ("BUY".equals(side)) {
                    if (!shouldCountBuyForRestore(order)) continue;

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
                    if (!shouldCountSellForRestore(order)) continue;

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
                rememberRestoreMiss(k, now);
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

                if (entryOrderId == null) {
                    entryOrderId = lot.entryOrderId;
                }

                if (openedAt == null || (lot.openedAt != null && lot.openedAt.isBefore(openedAt))) {
                    openedAt = lot.openedAt;
                }
            }

            if (totalQty.signum() <= 0 || totalCost.signum() <= 0) {
                rememberRestoreMiss(k, now);
                return Optional.empty();
            }

            BigDecimal avgEntry = totalCost.divide(totalQty, 12, RoundingMode.HALF_UP);
            BigDecimal restoreNotional = totalQty.multiply(avgEntry);

            /**
             * Порог dust применяем только здесь — при restore из истории.
             * Уже открытую runtime-позицию этот фильтр не должен выбрасывать.
             */
            if (shouldSkipDustRestore(totalQty, restoreNotional)) {
                if (shouldLogDustSkip(k, now)) {
                    log.warn("[POS] 🚫 SKIP RESTORE DUST chatId={} type={} ex={} net={} sym={} qty={} entry={} notional={} floor={}",
                            chatId,
                            type,
                            exchange,
                            networkKey,
                            symbol,
                            toStr(totalQty),
                            toStr(avgEntry),
                            toStr(restoreNotional),
                            toStr(effectiveRestoreMinNotional()));
                }
                positions.remove(k);
                rememberRestoreMiss(k, now);
                suppressedRestoreUntil.put(k, now.plusMillis(positiveMillisOrDefault(dustSuppressRestoreMs, DEFAULT_DUST_SUPPRESS_RESTORE_MS)));
                return Optional.empty();
            }

            PositionSnapshot restored = new PositionSnapshot(
                    chatId,
                    type,
                    exchange,
                    network,
                    symbol,
                    avgEntry.stripTrailingZeros(),
                    totalQty.stripTrailingZeros(),
                    positiveOrNull(tp),
                    positiveOrNull(sl),
                    restoreNotional.stripTrailingZeros(),
                    entryOrderId,
                    openedAt != null ? openedAt : Instant.now()
            );

            positions.put(k, restored);
            clearRestoreCaches(k);

            log.warn("[POS] ♻️ RESTORED chatId={} type={} ex={} net={} sym={} qty={} entry={} tp={} sl={} entryOrderId={} notional={}",
                    chatId,
                    type,
                    exchange,
                    networkKey,
                    symbol,
                    toStr(restored.qty()),
                    toStr(restored.entryPrice()),
                    toStr(restored.tp()),
                    toStr(restored.sl()),
                    restored.entryOrderId(),
                    toStr(restored.quoteSpent()));

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

    private boolean shouldCountBuyForRestore(OrderEntity order) {
        if (order == null) return false;
        if (Boolean.TRUE.equals(order.getFilled())) return true;

        String status = normUpper(order.getStatus());
        if (status == null) return false;

        return switch (status) {
            case "FILLED", "PARTIALLY_FILLED", "PARTIALLYFILLED", "PARTIALLY_FILLED_CANCELED", "PARTIALLYFILLEDCANCELED" -> true;
            case "NEW", "OPEN", "PENDING_NEW", "PENDINGNEW", "CREATED", "ACCEPTED", "ACTIVE", "UNTRIGGERED", "TRIGGERED",
                 "CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "FAILED" -> false;
            default -> false;
        };
    }

    private boolean shouldCountSellForRestore(OrderEntity order) {
        if (order == null) return false;
        if (Boolean.TRUE.equals(order.getFilled())) return true;

        String status = normUpper(order.getStatus());
        if (status == null) return false;

        return switch (status) {
            case "FILLED", "PARTIALLY_FILLED", "PARTIALLYFILLED", "PARTIALLY_FILLED_CANCELED", "PARTIALLYFILLEDCANCELED" -> true;
            case "NEW", "OPEN", "PENDING_NEW", "PENDINGNEW", "CREATED", "ACCEPTED", "ACTIVE", "UNTRIGGERED", "TRIGGERED",
                 "CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "FAILED" -> false;
            default -> false;
        };
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

    private boolean shouldSkipDustRestore(BigDecimal qty, BigDecimal notional) {
        if (!dropDustOnRestore) return false;

        BigDecimal safeQty = positiveOrNull(qty);
        BigDecimal safeNotional = positiveOrNull(notional);

        if (safeQty == null || safeNotional == null) {
            return true;
        }

        return safeNotional.compareTo(effectiveRestoreMinNotional()) < 0;
    }

    private BigDecimal effectiveRestoreMinNotional() {
        BigDecimal v = positiveOrNull(restoreMinNotional);
        return v != null ? v : DEFAULT_RESTORE_MIN_NOTIONAL;
    }

    private boolean isRestoreSuppressed(String key, Instant now) {
        if (key == null || now == null) return false;

        Instant until = suppressedRestoreUntil.get(key);
        if (until == null) return false;

        if (now.isBefore(until)) {
            return true;
        }

        suppressedRestoreUntil.remove(key);
        return false;
    }

    private boolean isRestoreRetryOnCooldown(String key, Instant now) {
        if (key == null || now == null) return false;

        Instant until = restoreMissUntil.get(key);
        if (until == null) return false;

        return now.isBefore(until);
    }

    private void rememberRestoreMiss(String key, Instant now) {
        if (key == null || now == null) return;

        long cooldownMs = Math.max(250L, positiveMillisOrDefault(restoreRetryCooldownMs, DEFAULT_RESTORE_RETRY_COOLDOWN_MS));
        restoreMissUntil.put(key, now.plusMillis(cooldownMs));
    }

    private boolean shouldLogDustSkip(String key, Instant now) {
        if (key == null || now == null) return true;

        long throttleMs = Math.max(1_000L, positiveMillisOrDefault(dustLogThrottleMs, DEFAULT_DUST_LOG_THROTTLE_MS));
        Instant prev = dustSkipLogAt.get(key);

        if (prev != null) {
            long ageMs = Duration.between(prev, now).toMillis();
            if (ageMs >= 0 && ageMs < throttleMs) {
                return false;
            }
        }

        dustSkipLogAt.put(key, now);
        return true;
    }

    private void clearRestoreCaches(String key) {
        if (key == null) return;
        restoreMissUntil.remove(key);
        dustSkipLogAt.remove(key);
        suppressedRestoreUntil.remove(key);
    }

    private void clearRestoreCachesByPrefix(String prefix) {
        if (prefix == null) return;

        restoreMissUntil.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        dustSkipLogAt.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        suppressedRestoreUntil.keySet().removeIf(k -> k != null && k.startsWith(prefix));
    }

    private long positiveMillisOrDefault(long value, long def) {
        return value > 0 ? value : def;
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






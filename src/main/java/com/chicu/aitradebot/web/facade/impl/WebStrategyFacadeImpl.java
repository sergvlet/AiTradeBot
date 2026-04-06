package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.StrategyUi;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AiStrategyOrchestrator orchestrator;
    private final StrategySettingsService settingsService;
    private final OrderService orderService;

    @Override
    @Transactional(readOnly = true)
    public List<StrategyUi> getStrategies(Long chatId, String exchange, NetworkType network) {

        String exFilter = normExchange(exchange);

        log.info("📋 getStrategies chatId={} exchange={} (norm={}) network={}",
                chatId, exchange, exFilter, network);

        List<StrategySettings> all = settingsService.findAllByChatId(chatId);

        Map<StrategyType, StrategySettings> byType = new EnumMap<>(StrategyType.class);
        for (StrategySettings s : all) {
            if (s == null || s.getType() == null) continue;

            if (network != null && s.getNetworkType() != network) continue;

            if (exFilter != null) {
                String exFromDb = normExchange(s.getExchangeName());
                if (exFromDb == null || !exFilter.equals(exFromDb)) continue;
            }

            StrategyType strategyType = s.getType();
            StrategySettings cur = byType.get(strategyType);
            if (cur == null) {
                byType.put(strategyType, s);
                continue;
            }
            Long curId = cur.getId();
            Long newId = s.getId();
            if (newId != null && (curId == null || newId > curId)) {
                byType.put(strategyType, s);
            }
        }

        List<StrategyUi> result = new ArrayList<>();

        for (StrategyType strategyType : StrategyType.values()) {

            StrategySettings settings = byType.get(strategyType);

            if (settings == null) {
                result.add(StrategyUi.empty(chatId, strategyType, exFilter, network));
                continue;
            }

            String ex = normExchange(settings.getExchangeName());
            NetworkType net = settings.getNetworkType();

            boolean active = false;
            try {
                AiStrategyOrchestrator.RunBinding binding = currentBinding(chatId, strategyType);
                if (binding != null) {
                    active = true;
                } else {
                    StrategyRunInfo runtime = orchestrator.getStatus(chatId, strategyType, ex, net);
                    active = runtime != null && runtime.isActive();
                }
            } catch (Exception e) {
                log.warn("⚠ getStatus failed chatId={} type={} ex={} net={} : {}",
                        chatId, strategyType, ex, net, e.getMessage());
            }

            StrategyUi baseUi = StrategyUi.fromSettings(settings);
            result.add(baseUi.withActive(active));
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> listOrders(Long chatId,
                                      StrategyType type,
                                      String symbol,
                                      String exchange,
                                      NetworkType network) {
        if (chatId == null || chatId <= 0 || symbol == null || symbol.isBlank()) {
            return List.of();
        }

        String symbolNorm = normUpper(symbol);
        String exchangeNorm = normExchange(exchange);
        String pnlAsset = extractQuoteAsset(symbolNorm);

        try {
            List<OrderEntity> raw = orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbolNorm);

            List<OrderEntity> filtered = raw.stream()
                    .filter(Objects::nonNull)
                    .filter(order -> symbolNorm.equals(normUpper(order.getSymbol())))
                    .filter(order -> type == null || eq(order.getStrategyType(), type.name()))
                    .filter(order -> exchangeNorm == null || eq(order.getExchangeName(), exchangeNorm))
                    .filter(order -> network == null || eq(order.getNetworkType(), network.name()))
                    .sorted(Comparator
                            .comparing(WebStrategyFacadeImpl::sortTimestampOrZero)
                            .thenComparing(OrderEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            List<OrderEntity> deduped = deduplicateOrders(filtered);
            return buildOrderViews(deduped, pnlAsset);
        } catch (Exception e) {
            log.error("❌ listOrders error chatId={} type={} symbol={} ex={} net={}",
                    chatId, type, symbolNorm, exchangeNorm, network, e);
            return List.of();
        }
    }

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(Long chatId, StrategyType type) {
        return locks.computeIfAbsent(chatId + ":" + type.name(), k -> new Object());
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static boolean eq(String a, String b) {
        return Objects.equals(normUpper(a), normUpper(b));
    }

    private AiStrategyOrchestrator.RunBinding currentBinding(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0 || type == null) {
            return null;
        }
        try {
            return orchestrator.getBinding(chatId, type).orElse(null);
        } catch (Exception e) {
            log.debug("⚠ currentBinding failed chatId={} type={} err={}", chatId, type, e.toString());
            return null;
        }
    }

    private StrategySettings ensureExecutionContext(Long chatId,
                                                    StrategyType type,
                                                    String exchange,
                                                    NetworkType network,
                                                    StrategySettings current) {
        if (chatId == null || chatId <= 0 || type == null || exchange == null || network == null) {
            return current;
        }

        try {
            if (current == null) {
                return settingsService.getOrCreateAndPatchContext(chatId, type, exchange, network);
            }

            boolean changed = !eq(current.getExchangeName(), exchange)
                    || current.getNetworkType() != network;

            if (!changed) {
                return current;
            }

            return settingsService.getOrCreateAndPatchContext(chatId, type, exchange, network);
        } catch (Exception e) {
            log.warn("⚠ ensureExecutionContext failed chatId={} type={} ex={} net={} : {}",
                    chatId, type, exchange, network, e.getMessage());
            return current;
        }
    }

    private static String firstNonBlankExchange(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = normExchange(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static NetworkType firstNonNullNetwork(NetworkType... values) {
        if (values == null) return null;
        for (NetworkType value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private StrategyRunInfo statusFromBindingOrRequest(Long chatId,
                                                       StrategyType type,
                                                       String exchange,
                                                       NetworkType network) {
        AiStrategyOrchestrator.RunBinding binding = currentBinding(chatId, type);
        String ex = binding != null ? binding.exchange() : exchange;
        NetworkType net = binding != null ? binding.network() : network;
        return orchestrator.getStatus(chatId, type, ex, net);
    }

    @Transactional
    public StrategyRunInfo restartIfOutOfSync(Long chatId, StrategyType type) {

        if (chatId == null || chatId <= 0 || type == null) {
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            return info;
        }

        synchronized (lockFor(chatId, type)) {

            StrategySettings s;
            try {
                s = settingsService.getSettings(chatId, type);
            } catch (Exception e) {
                log.warn("⚠ restartIfOutOfSync: settings not found chatId={} type={} : {}", chatId, type, e.getMessage());
                StrategyRunInfo info = new StrategyRunInfo();
                info.setActive(false);
                return info;
            }

            String exNew = normExchange(s.getExchangeName());
            NetworkType netNew = s.getNetworkType();
            String symNew = normUpper(s.getSymbol());
            String tfNew = normUpper(s.getTimeframe());

            if (exNew == null || netNew == null) {
                log.warn("⚠ restartIfOutOfSync: missing ex/net in settings chatId={} type={} ex={} net={}",
                        chatId, type, s.getExchangeName(), s.getNetworkType());
                StrategyRunInfo info = new StrategyRunInfo();
                info.setActive(false);
                info.setExchangeName(exNew);
                info.setNetworkType(netNew);
                return info;
            }

            StrategyRunInfo runtime;
            try {
                runtime = statusFromBindingOrRequest(chatId, type, exNew, netNew);
            } catch (Exception e) {
                log.warn("⚠ restartIfOutOfSync: getStatus failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, exNew, netNew, e.getMessage());
                runtime = null;
            }

            if (runtime == null || !runtime.isActive()) {
                return runtime != null ? runtime : new StrategyRunInfo();
            }

            boolean mismatch =
                    !eq(runtime.getExchangeName(), exNew)
                            || runtime.getNetworkType() != netNew
                            || !eq(runtime.getSymbol(), symNew)
                            || !eq(runtime.getTimeframe(), tfNew);

            if (!mismatch) {
                return runtime;
            }

            log.warn("🔄 AUTO-RESTART (out-of-sync) chatId={} type={} old=[ex={} net={} sym={} tf={}] new=[ex={} net={} sym={} tf={}]",
                    chatId, type,
                    runtime.getExchangeName(), runtime.getNetworkType(), runtime.getSymbol(), runtime.getTimeframe(),
                    exNew, netNew, symNew, tfNew
            );

            return orchestrator.restartStrategyAtomic(chatId, type, exNew, netNew, "web_out_of_sync");
        }
    }

    @Override
    public StrategyRunInfo toggle(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String requestedEx = normExchange(exchange);
        NetworkType requestedNet = network;

        if (chatId == null || chatId <= 0 || type == null) {
            log.warn("⚠ TOGGLE пропуск: chatId={} type={} ex={} net={}", chatId, type, exchange, network);
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(requestedEx);
            info.setNetworkType(requestedNet);
            return info;
        }

        synchronized (lockFor(chatId, type)) {

            StrategySettings settings = null;
            try {
                settings = settingsService.getOrCreate(chatId, type);
            } catch (Exception e) {
                log.warn("⚠ TOGGLE: settings load failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, requestedEx, requestedNet, e.getMessage());
            }

            String targetEx = firstNonBlankExchange(requestedEx, settings != null ? settings.getExchangeName() : null);
            NetworkType targetNet = firstNonNullNetwork(requestedNet, settings != null ? settings.getNetworkType() : null);
            String targetSymbol = settings != null ? normUpper(settings.getSymbol()) : null;
            String targetTf = settings != null ? normUpper(settings.getTimeframe()) : null;

            StrategyRunInfo runtime = null;
            boolean isRunning = false;

            try {
                AiStrategyOrchestrator.RunBinding binding = currentBinding(chatId, type);
                if (binding != null) {
                    runtime = orchestrator.getStatus(chatId, type, binding.exchange(), binding.network());
                    isRunning = runtime != null && runtime.isActive();
                } else {
                    runtime = orchestrator.getStatus(chatId, type, targetEx, targetNet);
                    isRunning = runtime != null && runtime.isActive();
                }
            } catch (Exception e) {
                log.warn("⚠ getStatus failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, targetEx, targetNet, e.getMessage());
            }

            boolean contextMismatch = false;
            if (isRunning && runtime != null) {
                contextMismatch =
                        !eq(runtime.getExchangeName(), targetEx)
                                || runtime.getNetworkType() != targetNet
                                || (targetSymbol != null && !eq(runtime.getSymbol(), targetSymbol))
                                || (targetTf != null && !eq(runtime.getTimeframe(), targetTf));
            }

            log.info("🔁 TOGGLE chatId={} type={} running={} mismatch={} ex={} net={} symbol={} tf={} runtimeEx={} runtimeNet={} runtimeSymbol={} runtimeTf={}",
                    chatId,
                    type,
                    isRunning,
                    contextMismatch,
                    targetEx,
                    targetNet,
                    targetSymbol,
                    targetTf,
                    runtime != null ? runtime.getExchangeName() : null,
                    runtime != null ? runtime.getNetworkType() : null,
                    runtime != null ? runtime.getSymbol() : null,
                    runtime != null ? runtime.getTimeframe() : null);

            if (isRunning && !contextMismatch) {
                return orchestrator.stopStrategy(chatId, type, targetEx, targetNet);
            }

            StrategySettings preparedSettings = ensureExecutionContext(chatId, type, targetEx, targetNet, settings);
            if (preparedSettings != null) {
                targetSymbol = normUpper(preparedSettings.getSymbol());
                targetTf = normUpper(preparedSettings.getTimeframe());
            }

            if (!isRunning) {
                return orchestrator.startStrategy(chatId, type, targetEx, targetNet);
            }

            return orchestrator.restartStrategyAtomic(chatId, type, targetEx, targetNet, "web_toggle_context_switch");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normExchange(exchange);
        NetworkType net = network;

        if (chatId == null || chatId <= 0 || type == null) {
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(ex);
            info.setNetworkType(net);
            return info;
        }

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {
        }

        if (ex == null && s != null) {
            ex = normExchange(s.getExchangeName());
        }
        if (net == null && s != null) {
            net = s.getNetworkType();
        }

        AiStrategyOrchestrator.RunBinding binding = currentBinding(chatId, type);
        if (binding != null) {
            if (ex == null) {
                ex = binding.exchange();
            }
            if (net == null) {
                net = binding.network();
            }
        }

        StrategyRunInfo runtime;
        try {
            runtime = statusFromBindingOrRequest(chatId, type, ex, net);
        } catch (Exception e) {
            log.warn("⚠ getRunInfo getStatus failed chatId={} type={} ex={} net={} : {}",
                    chatId, type, ex, net, e.getMessage());
            runtime = null;
        }

        if (runtime == null) {
            runtime = new StrategyRunInfo();
            runtime.setActive(false);
            runtime.setExchangeName(ex);
            runtime.setNetworkType(net);
        }

        if (binding != null) {
            runtime.setActive(true);
            if (runtime.getExchangeName() == null) {
                runtime.setExchangeName(binding.exchange());
            }
            if (runtime.getNetworkType() == null) {
                runtime.setNetworkType(binding.network());
            }
            if (runtime.getSymbol() == null) {
                runtime.setSymbol(binding.symbol());
            }
            if (runtime.getTimeframe() == null) {
                runtime.setTimeframe(binding.timeframe());
            }
        }

        if (s != null) {
            if (runtime.getSymbol() == null) {
                runtime.setSymbol(s.getSymbol());
            }
            if (runtime.getTimeframe() == null) {
                runtime.setTimeframe(s.getTimeframe());
            }
            if (runtime.getExchangeName() == null) {
                runtime.setExchangeName(ex != null ? ex : normExchange(s.getExchangeName()));
            }
            if (runtime.getNetworkType() == null) {
                runtime.setNetworkType(net != null ? net : s.getNetworkType());
            }
        }

        return runtime;
    }

    private static List<OrderEntity> deduplicateOrders(List<OrderEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        Map<String, OrderEntity> uniq = new LinkedHashMap<>();
        for (OrderEntity order : orders) {
            String exactKey = exactKey(order);
            OrderEntity existing = uniq.get(exactKey);
            if (existing == null) {
                uniq.put(exactKey, order);
                continue;
            }
            uniq.put(exactKey, preferOrder(existing, order));
        }

        List<OrderEntity> exact = new ArrayList<>(uniq.values());
        List<OrderEntity> result = new ArrayList<>();

        for (OrderEntity candidate : exact) {
            if (result.isEmpty()) {
                result.add(candidate);
                continue;
            }

            OrderEntity prev = result.get(result.size() - 1);
            if (isNearDuplicate(prev, candidate)) {
                result.set(result.size() - 1, preferOrder(prev, candidate));
            } else {
                result.add(candidate);
            }
        }

        return result;
    }

    private static List<OrderView> buildOrderViews(List<OrderEntity> orders, String pnlAsset) {
        Deque<OpenLot> openLots = new ArrayDeque<>();
        List<OrderView> out = new ArrayList<>();

        for (OrderEntity order : orders) {
            String side = normUpper(order.getSide());
            BigDecimal qty = safe(order.getQuantity());
            BigDecimal price = safe(order.getPrice());
            BigDecimal total = safeTotal(order, price, qty);

            BigDecimal realizedPnl = normalizePnl(order.getRealizedPnlUsd());
            BigDecimal realizedPnlPct = normalizePct(order.getRealizedPnlPct());
            Long matchedEntryId = null;

            if ("BUY".equals(side) && isPositive(qty) && isPositive(price)) {
                openLots.addLast(new OpenLot(order.getId(), qty, price));
            } else if ("SELL".equals(side) && isPositive(qty) && isPositive(price)) {
                BigDecimal sellQtyLeft = qty;
                BigDecimal computedPnl = BigDecimal.ZERO;
                BigDecimal matchedCost = BigDecimal.ZERO;

                while (isPositive(sellQtyLeft) && !openLots.isEmpty()) {
                    OpenLot head = openLots.peekFirst();
                    BigDecimal matchedQty = head.remainingQty().min(sellQtyLeft);
                    if (!isPositive(matchedQty)) {
                        openLots.removeFirst();
                        continue;
                    }

                    if (matchedEntryId == null) {
                        matchedEntryId = head.entryId();
                    }

                    computedPnl = computedPnl.add(price.subtract(head.entryPrice()).multiply(matchedQty));
                    matchedCost = matchedCost.add(head.entryPrice().multiply(matchedQty));

                    BigDecimal newRemaining = head.remainingQty().subtract(matchedQty);
                    sellQtyLeft = sellQtyLeft.subtract(matchedQty);

                    openLots.removeFirst();
                    if (isPositive(newRemaining)) {
                        openLots.addFirst(new OpenLot(head.entryId(), newRemaining, head.entryPrice()));
                    }
                }

                if (realizedPnl == null && computedPnl.signum() != 0) {
                    realizedPnl = computedPnl.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
                }

                if (realizedPnlPct == null && isPositive(matchedCost)) {
                    realizedPnlPct = computedPnl
                            .divide(matchedCost, 8, RoundingMode.HALF_UP)
                            .multiply(HUNDRED)
                            .setScale(4, RoundingMode.HALF_UP)
                            .stripTrailingZeros();
                }
            }

            out.add(new OrderView(
                    order.getId(),
                    order.getSymbol(),
                    side,
                    normUpper(order.getStatus()),
                    price,
                    qty,
                    Boolean.TRUE.equals(order.getFilled()),
                    sortTimestampOrZero(order),
                    total,
                    realizedPnl,
                    realizedPnlPct,
                    pnlAsset,
                    matchedEntryId
            ));
        }

        out.sort(Comparator
                .comparing(OrderView::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OrderView::id, Comparator.nullsLast(Comparator.reverseOrder())));

        return out;
    }

    private record OpenLot(Long entryId, BigDecimal remainingQty, BigDecimal entryPrice) {
    }

    private static OrderEntity preferOrder(OrderEntity a, OrderEntity b) {
        if (a == null) return b;
        if (b == null) return a;

        int aScore = score(a);
        int bScore = score(b);

        if (bScore != aScore) {
            return bScore > aScore ? b : a;
        }

        Long aId = a.getId();
        Long bId = b.getId();
        if (aId == null) return b;
        if (bId == null) return a;
        return bId > aId ? b : a;
    }

    private static int score(OrderEntity order) {
        int score = 0;
        if (order.getExitPrice() != null) score += 4;
        if (order.getRealizedPnlUsd() != null) score += 4;
        if (order.getRealizedPnlPct() != null) score += 2;
        if (Boolean.TRUE.equals(order.getFilled())) score += 2;
        if (order.getUpdatedAt() != null) score += 1;
        return score;
    }

    private static boolean isNearDuplicate(OrderEntity left, OrderEntity right) {
        if (left == null || right == null) return false;
        if (!eq(left.getSymbol(), right.getSymbol())) return false;
        if (!eq(left.getSide(), right.getSide())) return false;
        if (!eq(left.getStatus(), right.getStatus())) return false;

        BigDecimal leftPrice = safe(left.getPrice());
        BigDecimal rightPrice = safe(right.getPrice());
        BigDecimal leftQty = safe(left.getQuantity());
        BigDecimal rightQty = safe(right.getQuantity());

        if (leftPrice.compareTo(rightPrice) != 0) return false;
        if (leftQty.compareTo(rightQty) != 0) return false;

        long lt = sortTimestampOrZero(left);
        long rt = sortTimestampOrZero(right);
        return Math.abs(lt - rt) <= 2_000L;
    }

    private static String exactKey(OrderEntity order) {
        return String.join("|",
                safeString(order.getChatId()),
                safeString(normUpper(order.getStrategyType())),
                safeString(normUpper(order.getSymbol())),
                safeString(normUpper(order.getSide())),
                safeString(normUpper(order.getStatus())),
                safeString(safe(order.getPrice())),
                safeString(safe(order.getQuantity())),
                safeString(sortTimestampOrZero(order) / 1000L),
                safeString(normUpper(order.getExchangeName())),
                safeString(normUpper(order.getNetworkType()))
        );
    }

    private static BigDecimal safeTotal(OrderEntity order, BigDecimal price, BigDecimal qty) {
        BigDecimal total = order.getTotal();
        if (total != null) {
            return total.stripTrailingZeros();
        }
        if (isPositive(price) && isPositive(qty)) {
            return price.multiply(qty).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return null;
    }

    private static BigDecimal normalizePnl(BigDecimal value) {
        if (value == null) return null;
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal normalizePct(BigDecimal value) {
        if (value == null) return null;
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static long sortTimestampOrZero(OrderEntity order) {
        if (order == null) return 0L;
        if (order.getExitTimestamp() != null && order.getExitTimestamp() > 0) {
            return order.getExitTimestamp();
        }
        if (order.getTimestamp() != null && order.getTimestamp() > 0) {
            return order.getTimestamp();
        }
        if (order.getCreatedAt() != null) {
            return order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return 0L;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String extractQuoteAsset(String symbol) {
        String normalized = normUpper(symbol);
        if (normalized == null) return null;

        for (String quote : List.of("USDT", "USDC", "FDUSD", "BUSD", "USDP", "DAI", "EUR", "TRY", "BTC", "ETH", "BNB")) {
            if (normalized.endsWith(quote) && normalized.length() > quote.length()) {
                return quote;
            }
        }
        return null;
    }

    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}


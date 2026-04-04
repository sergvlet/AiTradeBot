package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartTradeHistoryLoader {

    private static final int DEFAULT_LIMIT = 300;

    private final ExchangeClientFactory exchangeClientFactory;
    private final OrderRepository orderRepository;

    public List<StrategyChartDto.TradeMarker> loadTradeMarkers(Long chatId,
                                                               StrategyType strategyType,
                                                               String exchange,
                                                               NetworkType network,
                                                               String symbol,
                                                               String timeframe,
                                                               long fromMs,
                                                               long toMs,
                                                               int limit) {
        String ex = normUpper(exchange);
        String sym = normUpper(symbol);
        if (chatId == null || chatId <= 0 || strategyType == null || ex == null || network == null || sym == null) {
            return List.of();
        }

        int max = Math.max(10, Math.min(limit > 0 ? limit : DEFAULT_LIMIT, 1000));

        List<StrategyChartDto.TradeMarker> exchangeTrades = loadFromExchange(chatId, strategyType, ex, network, sym, timeframe, fromMs, toMs, max);
        if (!exchangeTrades.isEmpty()) {
            log.info("📈 [CHART] trade-history loaded from exchange chatId={} type={} ex={} net={} sym={} trades={} range=[{},{}]",
                    chatId, strategyType, ex, network, sym, exchangeTrades.size(), fromMs, toMs);
            return exchangeTrades;
        }

        List<StrategyChartDto.TradeMarker> localTrades = loadFromLocalOrders(chatId, strategyType, ex, network, sym, fromMs, toMs, max);
        if (!localTrades.isEmpty()) {
            log.info("📈 [CHART] trade-history fallback to local orders chatId={} type={} ex={} net={} sym={} trades={} range=[{},{}]",
                    chatId, strategyType, ex, network, sym, localTrades.size(), fromMs, toMs);
        }
        return localTrades;
    }

    private List<StrategyChartDto.TradeMarker> loadFromExchange(Long chatId,
                                                                StrategyType strategyType,
                                                                String exchange,
                                                                NetworkType network,
                                                                String symbol,
                                                                String timeframe,
                                                                long fromMs,
                                                                long toMs,
                                                                int limit) {
        ExchangeClient client;
        try {
            client = exchangeClientFactory.get(exchange, network);
        } catch (Exception e) {
            return List.of();
        }
        if (client == null) return List.of();

        for (String method : List.of(
                "getMyTrades",
                "getTradeHistory",
                "getExecutionHistory",
                "getExecutions",
                "listTradeHistory",
                "listExecutions",
                "getOrderHistory"
        )) {
            Object raw = invokeTradeMethod(client, method, chatId, symbol, timeframe, fromMs, toMs, limit);
            List<Object> rows = extractObjects(raw);
            if (rows.isEmpty()) continue;

            List<StrategyChartDto.TradeMarker> trades = normalizeTradeRows(rows, symbol, fromMs, toMs, limit, "exchange");
            if (!trades.isEmpty()) {
                return trades;
            }
        }

        return List.of();
    }

    private Object invokeTradeMethod(Object target,
                                     String methodName,
                                     Long chatId,
                                     String symbol,
                                     String timeframe,
                                     long fromMs,
                                     long toMs,
                                     int limit) {
        List<Object[]> candidates = List.of(
                new Object[]{symbol, fromMs, toMs, limit},
                new Object[]{symbol, timeframe, fromMs, toMs, limit},
                new Object[]{chatId, symbol, fromMs, toMs, limit},
                new Object[]{chatId, symbol, timeframe, fromMs, toMs, limit},
                new Object[]{symbol, limit},
                new Object[]{chatId, symbol, limit},
                new Object[]{symbol},
                new Object[]{chatId, symbol}
        );

        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            for (Object[] args : candidates) {
                if (!isCompatible(m.getParameterTypes(), args)) continue;
                try {
                    return m.invoke(target, coerceArgs(m.getParameterTypes(), args));
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private List<Object> extractObjects(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) if (item != null) out.add(item);
            return out;
        }
        if (raw.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = Array.getLength(raw);
            for (int i = 0; i < n; i++) {
                Object item = Array.get(raw, i);
                if (item != null) out.add(item);
            }
            return out;
        }
        for (String nested : List.of("getItems", "items", "getRows", "rows", "getList", "list", "getData", "data", "getResult", "result")) {
            Object value = invokeNoArg(raw, nested);
            if (value != null && value != raw) {
                List<Object> nestedRows = extractObjects(value);
                if (!nestedRows.isEmpty()) return nestedRows;
            }
        }
        return List.of();
    }

    private List<StrategyChartDto.TradeMarker> normalizeTradeRows(List<Object> rows,
                                                                  String symbol,
                                                                  long fromMs,
                                                                  long toMs,
                                                                  int limit,
                                                                  String source) {
        List<StrategyChartDto.TradeMarker> out = new ArrayList<>();
        String sym = normUpper(symbol);

        for (Object row : rows) {
            if (row == null) continue;
            String rowSymbol = firstNonBlank(
                    readString(row, "getSymbol", "symbol", "getOrderSymbol", "orderSymbol")
            );
            if (rowSymbol != null && sym != null && !sym.equalsIgnoreCase(rowSymbol.trim())) continue;

            String side = normUpper(firstNonBlank(
                    readString(row, "getSide", "side", "getExecSide", "execSide", "getOrderSide", "orderSide")
            ));
            if (!"BUY".equals(side) && !"SELL".equals(side)) continue;

            Double price = firstFinite(
                    readDouble(row, "getPrice", "price", "getExecPrice", "execPrice", "getExecutedPrice", "executedPrice", "getAvgPrice", "avgPrice")
            );
            if (price == null || price <= 0.0d) continue;

            Long time = firstPositiveTime(
                    readTime(row, "getTime", "time", "getTimestamp", "timestamp", "getTradeTime", "tradeTime", "getExecTime", "execTime", "getExecutedTime", "executedTime", "getCreatedAt", "createdAt", "getUpdatedAt", "updatedAt")
            );
            if (time == null) continue;
            if (time < fromMs || time > toMs) continue;

            Double qty = firstFinite(
                    readDouble(row, "getQty", "qty", "getSize", "size", "getExecutedQty", "executedQty", "getExecQty", "execQty", "getQuantity", "quantity")
            );

            String status = normUpper(firstNonBlank(readString(row, "getStatus", "status", "getExecType", "execType")));
            if (status != null && (status.contains("CANCEL") || status.contains("REJECT") || status.contains("FAIL"))) {
                continue;
            }

            out.add(StrategyChartDto.TradeMarker.builder()
                    .side(side)
                    .price(price)
                    .qty(qty)
                    .time(time)
                    .source(source)
                    .build());
        }

        out.sort(Comparator.comparingLong(StrategyChartDto.TradeMarker::getTime));
        out = dedup(out);
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    private List<StrategyChartDto.TradeMarker> loadFromLocalOrders(Long chatId,
                                                                   StrategyType strategyType,
                                                                   String exchange,
                                                                   NetworkType network,
                                                                   String symbol,
                                                                   long fromMs,
                                                                   long toMs,
                                                                   int limit) {
        String strategy = strategyType.name();
        String networkKey = network != null ? network.name() : null;
        List<OrderEntity> rows = List.of();
        try {
            if (exchange != null && networkKey != null) {
                rows = orderRepository.findByChatIdAndStrategyTypeAndSymbolAndExchangeNameAndNetworkTypeOrderByTimestampAsc(
                        chatId, strategy, symbol, exchange, networkKey
                );
            }
            if (rows == null || rows.isEmpty()) {
                rows = orderRepository.findByChatIdAndStrategyTypeAndSymbolOrderByTimestampAsc(chatId, strategy, symbol);
            }
        } catch (Exception e) {
            log.debug("⚠️ [CHART] local trade-history read failed chatId={} type={} sym={} err={}", chatId, strategyType, symbol, e.toString());
            return List.of();
        }

        if (rows == null || rows.isEmpty()) return List.of();

        List<StrategyChartDto.TradeMarker> out = new ArrayList<>();
        for (OrderEntity row : rows) {
            if (row == null) continue;
            if (!matchesContext(row, exchange, networkKey)) continue;
            if (!isFilled(row)) continue;

            String side = normUpper(row.getSide());
            if (!"BUY".equals(side) && !"SELL".equals(side)) continue;

            BigDecimal price = row.getPrice();
            BigDecimal qty = row.getQuantity();
            Long time = row.getTimestamp();
            if (time == null || time <= 0) continue;
            if (time < fromMs || time > toMs) continue;
            if (price == null || price.signum() <= 0) continue;

            out.add(StrategyChartDto.TradeMarker.builder()
                    .side(side)
                    .price(price.doubleValue())
                    .qty(qty != null ? qty.doubleValue() : null)
                    .time(time)
                    .source("local")
                    .build());
        }

        out.sort(Comparator.comparingLong(StrategyChartDto.TradeMarker::getTime));
        out = dedup(out);
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    private boolean matchesContext(OrderEntity row, String exchange, String networkKey) {
        String rowExchange = normUpper(row.getExchangeName());
        String rowNetwork = normUpper(row.getNetworkType());
        if (rowExchange != null && exchange != null && !rowExchange.equals(exchange)) return false;
        if (rowNetwork != null && networkKey != null && !rowNetwork.equals(networkKey)) return false;
        return true;
    }

    private boolean isFilled(OrderEntity row) {
        if (row == null) return false;
        if (Boolean.TRUE.equals(row.getFilled())) return true;
        String status = normUpper(row.getStatus());
        if (status == null) return false;
        return status.equals("FILLED") || status.startsWith("PARTIALLY_FILLED") || status.equals("PARTIALLYFILLED");
    }

    private List<StrategyChartDto.TradeMarker> dedup(List<StrategyChartDto.TradeMarker> rows) {
        LinkedHashMap<String, StrategyChartDto.TradeMarker> map = new LinkedHashMap<>();
        for (StrategyChartDto.TradeMarker row : rows) {
            if (row == null) continue;
            String key = row.getSide() + "|" + row.getTime() + "|" + row.getPrice();
            map.put(key, row);
        }
        return new ArrayList<>(map.values());
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            if (m.getParameterCount() == 0) return m.invoke(target);
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isCompatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            if (t.isInstance(a)) continue;
            if (Number.class.isAssignableFrom(t) && a instanceof Number) continue;
            if (t == String.class) continue;
            if (Enum.class.isAssignableFrom(t) && (a instanceof Enum<?> || a instanceof String)) continue;
            return false;
        }
        return true;
    }

    private Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = coerceArg(types[i], args[i]);
        }
        return out;
    }

    private Object coerceArg(Class<?> type, Object arg) {
        if (arg == null) return null;
        Class<?> t = wrap(type);
        if (t.isInstance(arg)) return arg;
        if (t == String.class) return String.valueOf(arg);
        if (Enum.class.isAssignableFrom(t)) {
            try {
                String name = (arg instanceof Enum<?> e) ? e.name() : String.valueOf(arg);
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object en = Enum.valueOf((Class<? extends Enum>) t.asSubclass(Enum.class), name);
                return en;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (Number.class.isAssignableFrom(t)) {
            if (arg instanceof Number n) {
                if (t == Integer.class) return n.intValue();
                if (t == Long.class) return n.longValue();
                if (t == Double.class) return n.doubleValue();
            }
            try {
                String s = String.valueOf(arg).trim();
                if (t == Integer.class) return Integer.parseInt(s);
                if (t == Long.class) return Long.parseLong(s);
                if (t == Double.class) return Double.parseDouble(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return arg;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private String readString(Object bean, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(bean, methodName);
            if (value == null) continue;
            String s = String.valueOf(value).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    private Double readDouble(Object bean, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(bean, methodName);
            if (value == null) continue;
            if (value instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Long readTime(Object bean, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(bean, methodName);
            Long parsed = parseTime(value);
            if (parsed != null && parsed > 0L) return parsed;
        }
        return null;
    }

    private Long parseTime(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant.toEpochMilli();
        if (value instanceof Date date) return date.getTime();
        if (value instanceof Number n) {
            long v = n.longValue();
            return v > 3_000_000_000L ? v : v * 1000L;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        try {
            long v = Long.parseLong(s);
            return v > 3_000_000_000L ? v : v * 1000L;
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private Double firstFinite(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private Long firstPositiveTime(Long value) {
        return value != null && value > 0L ? value : null;
    }

    private String firstNonBlank(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }
}

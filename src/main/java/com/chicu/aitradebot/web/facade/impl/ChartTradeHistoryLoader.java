package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartTradeHistoryLoader {

    private static final int DEFAULT_LIMIT = 300;
    private static final int MAX_LIMIT = 1000;
    private static final int BYBIT_PAGE_LIMIT = 100;
    private static final int BYBIT_MAX_PAGES = 5;

    private final ExchangeClientFactory exchangeClientFactory;

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

        if (chatId == null || chatId <= 0
                || strategyType == null
                || ex == null
                || network == null
                || sym == null) {
            return List.of();
        }

        long safeFromMs = Math.max(0L, fromMs);
        long safeToMs = Math.max(safeFromMs, toMs);
        int max = Math.max(10, Math.min(limit > 0 ? limit : DEFAULT_LIMIT, MAX_LIMIT));

        List<StrategyChartDto.TradeMarker> exchangeTrades =
                loadFromExchange(chatId, strategyType, ex, network, sym, timeframe, safeFromMs, safeToMs, max);

        if (!exchangeTrades.isEmpty()) {
            log.info("📈 [CHART] trade-history loaded from exchange chatId={} type={} ex={} net={} sym={} trades={} range=[{},{}]",
                    chatId, strategyType, ex, network, sym, exchangeTrades.size(), safeFromMs, safeToMs);
            return exchangeTrades;
        }

        log.info("📭 [CHART] exchange trade-history is empty chatId={} type={} ex={} net={} sym={} range=[{},{}]",
                chatId, strategyType, ex, network, sym, safeFromMs, safeToMs);

        // По требованию: для графика источник истины только биржа.
        return List.of();
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
            log.debug("⚠️ [CHART] exchange client resolve failed chatId={} type={} ex={} net={} sym={} err={}",
                    chatId, strategyType, exchange, network, symbol, e.toString());
            return List.of();
        }

        if (client == null) {
            return List.of();
        }

        // 1) Нормальный путь через интерфейс ExchangeClient.
        List<StrategyChartDto.TradeMarker> directTrades =
                tryDirectGetMyTrades(client, chatId, network, symbol, fromMs, toMs, limit);
        if (!directTrades.isEmpty()) {
            return directTrades;
        }

        // 2) Универсальный рефлексивный fallback.
        // ВАЖНО: теперь здесь есть варианты сигнатур С network.
        for (String method : List.of(
                "getMyTrades",
                "getTradeHistory",
                "getExecutionHistory",
                "getExecutions",
                "listTradeHistory",
                "listExecutions",
                "getOrderHistory"
        )) {
            Object raw = invokeTradeMethod(client, method, chatId, network, symbol, timeframe, fromMs, toMs, limit);
            List<Object> rows = extractObjects(raw);
            if (rows.isEmpty()) {
                continue;
            }

            List<StrategyChartDto.TradeMarker> trades =
                    normalizeTradeRows(rows, symbol, fromMs, toMs, limit, "exchange");

            if (!trades.isEmpty()) {
                return trades;
            }
        }

        // 3) Спец-fallback для Bybit, если клиент ещё не реализовал getMyTrades(),
        // но внутри есть signedV5()/resolve().
        if (isBybitClient(client)) {
            List<StrategyChartDto.TradeMarker> bybitTrades =
                    loadBybitExecutionsViaReflection(client, chatId, network, symbol, fromMs, toMs, limit);
            if (!bybitTrades.isEmpty()) {
                return bybitTrades;
            }
        }

        return List.of();
    }

    private List<StrategyChartDto.TradeMarker> tryDirectGetMyTrades(ExchangeClient client,
                                                                    Long chatId,
                                                                    NetworkType network,
                                                                    String symbol,
                                                                    long fromMs,
                                                                    long toMs,
                                                                    int limit) {
        try {
            List<ExchangeClient.TradeFill> fills = client.getMyTrades(chatId, network, symbol, fromMs, toMs, limit);
            if (fills == null || fills.isEmpty()) {
                return List.of();
            }

            List<Object> rows = new ArrayList<>(fills);
            return normalizeTradeRows(rows, symbol, fromMs, toMs, limit, "exchange");
        } catch (UnsupportedOperationException | AbstractMethodError ignored) {
            return List.of();
        } catch (Exception e) {
            log.debug("⚠️ [CHART] direct getMyTrades failed client={} sym={} err={}",
                    client.getClass().getSimpleName(), symbol, e.toString());
            return List.of();
        }
    }

    private Object invokeTradeMethod(Object target,
                                     String methodName,
                                     Long chatId,
                                     NetworkType network,
                                     String symbol,
                                     String timeframe,
                                     long fromMs,
                                     long toMs,
                                     int limit) {

        List<Object[]> candidates = List.of(
                new Object[]{chatId, network, symbol, fromMs, toMs, limit},
                new Object[]{chatId, network, symbol, timeframe, fromMs, toMs, limit},
                new Object[]{network, symbol, fromMs, toMs, limit},
                new Object[]{network, symbol, timeframe, fromMs, toMs, limit},

                new Object[]{chatId, symbol, fromMs, toMs, limit},
                new Object[]{chatId, symbol, timeframe, fromMs, toMs, limit},
                new Object[]{symbol, fromMs, toMs, limit},
                new Object[]{symbol, timeframe, fromMs, toMs, limit},

                new Object[]{chatId, network, symbol, limit},
                new Object[]{network, symbol, limit},
                new Object[]{chatId, symbol, limit},
                new Object[]{symbol, limit},

                new Object[]{chatId, network, symbol},
                new Object[]{network, symbol},
                new Object[]{chatId, symbol},
                new Object[]{symbol}
        );

        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }

            for (Object[] args : candidates) {
                if (!isCompatible(m.getParameterTypes(), args)) {
                    continue;
                }

                try {
                    return m.invoke(target, coerceArgs(m.getParameterTypes(), args));
                } catch (Exception ignored) {
                    // Идём к следующей совместимой сигнатуре.
                }
            }
        }

        return null;
    }

    private List<Object> extractObjects(Object raw) {
        if (raw == null) {
            return List.of();
        }

        if (raw instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) {
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        }

        if (raw.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = Array.getLength(raw);
            for (int i = 0; i < n; i++) {
                Object item = Array.get(raw, i);
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        }

        for (String nested : List.of(
                "getItems", "items",
                "getRows", "rows",
                "getList", "list",
                "getData", "data",
                "getResult", "result"
        )) {
            Object value = invokeNoArg(raw, nested);
            if (value != null && value != raw) {
                List<Object> nestedRows = extractObjects(value);
                if (!nestedRows.isEmpty()) {
                    return nestedRows;
                }
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
            if (row == null) {
                continue;
            }

            String rowSymbol = firstNonBlank(
                    readString(row,
                            "getSymbol", "symbol",
                            "getOrderSymbol", "orderSymbol")
            );
            if (rowSymbol != null && sym != null && !sym.equalsIgnoreCase(rowSymbol.trim())) {
                continue;
            }

            String side = normUpper(firstNonBlank(
                    readString(row,
                            "getSide", "side",
                            "getExecSide", "execSide",
                            "getOrderSide", "orderSide")
            ));
            if (!"BUY".equals(side) && !"SELL".equals(side)) {
                continue;
            }

            Double price = firstFinite(
                    readDouble(row,
                            "getPrice", "price",
                            "getExecPrice", "execPrice",
                            "getExecutedPrice", "executedPrice",
                            "getAvgPrice", "avgPrice")
            );
            if (price == null || price <= 0.0d) {
                continue;
            }

            Long time = firstPositiveTime(
                    readTime(row,
                            "getTime", "time",
                            "getTimeMs", "timeMs",
                            "getTimestamp", "timestamp",
                            "getUpdateTimeMs", "updateTimeMs",
                            "getTradeTime", "tradeTime",
                            "getExecTime", "execTime",
                            "getExecutedTime", "executedTime",
                            "getCreatedAt", "createdAt",
                            "getUpdatedAt", "updatedAt")
            );
            if (time == null || time < fromMs || time > toMs) {
                continue;
            }

            Double qty = firstFinite(
                    readDouble(row,
                            "getQty", "qty",
                            "getSize", "size",
                            "getExecutedQty", "executedQty",
                            "getExecQty", "execQty",
                            "getQuantity", "quantity",
                            "getOrigQty", "origQty")
            );

            String status = normUpper(firstNonBlank(
                    readString(row,
                            "getStatus", "status",
                            "getExecType", "execType")
            ));
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

    private List<StrategyChartDto.TradeMarker> loadBybitExecutionsViaReflection(Object bybitClient,
                                                                                Long chatId,
                                                                                NetworkType network,
                                                                                String symbol,
                                                                                long fromMs,
                                                                                long toMs,
                                                                                int limit) {
        try {
            ExchangeSettings settings = resolveBybitSettings(bybitClient, chatId, network);
            if (settings == null) {
                return List.of();
            }

            String sym = normUpper(symbol);
            if (sym == null) {
                return List.of();
            }

            List<StrategyChartDto.TradeMarker> collected = new ArrayList<>();
            String cursor = null;
            int page = 0;

            while (page < BYBIT_MAX_PAGES && collected.size() < limit) {
                page++;

                Map<String, String> params = new LinkedHashMap<>();
                params.put("category", "spot");
                params.put("symbol", sym);
                params.put("startTime", String.valueOf(fromMs));
                params.put("endTime", String.valueOf(toMs));
                params.put("limit", String.valueOf(Math.min(BYBIT_PAGE_LIMIT, Math.max(1, limit))));
                if (cursor != null && !cursor.isBlank()) {
                    params.put("cursor", cursor);
                }

                String raw = signedBybitRequest(bybitClient, settings, "/v5/execution/list", params);
                if (raw == null || raw.isBlank()) {
                    break;
                }

                JSONObject root = new JSONObject(raw);
                if (root.optInt("retCode", -1) != 0) {
                    break;
                }

                JSONObject result = root.optJSONObject("result");
                JSONArray list = result != null ? result.optJSONArray("list") : null;
                if (list == null || list.isEmpty()) {
                    break;
                }

                List<StrategyChartDto.TradeMarker> pageTrades =
                        normalizeBybitExecutionList(list, sym, fromMs, toMs, limit, "exchange");
                collected.addAll(pageTrades);

                String nextCursor = result != null ? result.optString("nextPageCursor", null) : null;
                if (nextCursor == null || nextCursor.isBlank() || nextCursor.equals(cursor)) {
                    break;
                }
                cursor = nextCursor;
            }

            collected.sort(Comparator.comparingLong(StrategyChartDto.TradeMarker::getTime));
            collected = dedup(collected);
            if (collected.size() > limit) {
                collected = new ArrayList<>(collected.subList(collected.size() - limit, collected.size()));
            }

            if (!collected.isEmpty()) {
                log.info("📈 [CHART] trade-history loaded from BYBIT execution/list chatId={} net={} sym={} trades={} range=[{},{}]",
                        chatId, network, sym, collected.size(), fromMs, toMs);
            }

            return collected;
        } catch (Exception e) {
            log.debug("⚠️ [CHART] bybit execution/list reflection failed chatId={} net={} sym={} err={}",
                    chatId, network, symbol, e.toString());
            return List.of();
        }
    }

    private ExchangeSettings resolveBybitSettings(Object bybitClient,
                                                  Long chatId,
                                                  NetworkType network) throws Exception {
        if (bybitClient == null || chatId == null || network == null) {
            return null;
        }

        Method resolve = bybitClient.getClass().getDeclaredMethod("resolve", long.class, NetworkType.class);
        resolve.setAccessible(true);

        Object result = resolve.invoke(bybitClient, chatId.longValue(), network);
        if (result instanceof ExchangeSettings settings) {
            return settings;
        }

        return null;
    }

    private String signedBybitRequest(Object bybitClient,
                                      ExchangeSettings settings,
                                      String endpoint,
                                      Map<String, String> params) throws Exception {
        if (bybitClient == null || settings == null || endpoint == null) {
            return null;
        }

        Method signed = bybitClient.getClass().getDeclaredMethod(
                "signedV5",
                ExchangeSettings.class,
                String.class,
                Map.class,
                HttpMethod.class
        );
        signed.setAccessible(true);

        Object raw = signed.invoke(bybitClient, settings, endpoint, params, HttpMethod.GET);
        return raw != null ? String.valueOf(raw) : null;
    }

    private List<StrategyChartDto.TradeMarker> normalizeBybitExecutionList(JSONArray arr,
                                                                           String symbol,
                                                                           long fromMs,
                                                                           long toMs,
                                                                           int limit,
                                                                           String source) {
        List<StrategyChartDto.TradeMarker> out = new ArrayList<>();
        String sym = normUpper(symbol);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) {
                continue;
            }

            String rowSymbol = normUpper(row.optString("symbol", null));
            if (rowSymbol != null && sym != null && !sym.equals(rowSymbol)) {
                continue;
            }

            String side = normUpper(row.optString("side", null));
            if (!"BUY".equals(side) && !"SELL".equals(side)) {
                continue;
            }

            BigDecimal px = bdOrNull(row.opt("execPrice"));
            if (px == null || px.signum() <= 0) {
                continue;
            }

            BigDecimal qty = bdOrNull(row.opt("execQty"));
            Long time = parseTime(row.opt("execTime"));
            if (time == null || time < fromMs || time > toMs) {
                continue;
            }

            String execType = normUpper(row.optString("execType", null));
            if (execType != null && (execType.contains("CANCEL") || execType.contains("REJECT") || execType.contains("FAIL"))) {
                continue;
            }

            out.add(StrategyChartDto.TradeMarker.builder()
                    .side(side)
                    .price(px.doubleValue())
                    .qty(qty != null ? qty.doubleValue() : null)
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

    private boolean isBybitClient(Object client) {
        if (client == null) {
            return false;
        }
        String name = client.getClass().getName().toUpperCase(Locale.ROOT);
        return name.contains("BYBIT");
    }

    private List<StrategyChartDto.TradeMarker> dedup(List<StrategyChartDto.TradeMarker> rows) {
        LinkedHashMap<String, StrategyChartDto.TradeMarker> map = new LinkedHashMap<>();
        for (StrategyChartDto.TradeMarker row : rows) {
            if (row == null) {
                continue;
            }
            String key = row.getSide() + "|" + row.getTime() + "|" + row.getPrice();
            map.put(key, row);
        }
        return new ArrayList<>(map.values());
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            if (m.getParameterCount() == 0) {
                return m.invoke(target);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isCompatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }

        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            Object a = args[i];

            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }

            if (t.isInstance(a)) {
                continue;
            }

            if (Number.class.isAssignableFrom(t) && a instanceof Number) {
                continue;
            }

            if (t == String.class) {
                continue;
            }

            if (Enum.class.isAssignableFrom(t) && (a instanceof Enum<?> || a instanceof String)) {
                continue;
            }

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
        if (arg == null) {
            return null;
        }

        Class<?> t = wrap(type);
        if (t.isInstance(arg)) {
            return arg;
        }

        if (t == String.class) {
            return String.valueOf(arg);
        }

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
                if (t == Integer.class) {
                    return n.intValue();
                }
                if (t == Long.class) {
                    return n.longValue();
                }
                if (t == Double.class) {
                    return n.doubleValue();
                }
            }

            try {
                String s = String.valueOf(arg).trim();
                if (t == Integer.class) {
                    return Integer.parseInt(s);
                }
                if (t == Long.class) {
                    return Long.parseLong(s);
                }
                if (t == Double.class) {
                    return Double.parseDouble(s);
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        return arg;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }

    private String readString(Object bean, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(bean, methodName);
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }

    private Double readDouble(Object bean, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(bean, methodName);
            if (value == null) {
                continue;
            }
            if (value instanceof Number n) {
                return n.doubleValue();
            }
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
            if (parsed != null && parsed > 0L) {
                return parsed;
            }
        }
        return null;
    }

    private Long parseTime(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }

        if (value instanceof Date date) {
            return date.getTime();
        }

        if (value instanceof Number n) {
            long v = n.longValue();
            return v > 3_000_000_000L ? v : v * 1000L;
        }

        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }

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

    private BigDecimal bdOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            return new BigDecimal(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normUpper(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }
}
package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClient.OrderAmountType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.AccountFees;
import com.chicu.aitradebot.exchange.model.AccountInfo;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.chicu.aitradebot.trade.TradeExecutionServiceImpl.positiveOrNull;

@Slf4j
@Component
public class BinanceExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.binance.com";
    private static final String TEST = "https://testnet.binance.vision";

    private static final String RECV_WINDOW = "5000";
    private static final int FALLBACK_QTY_SCALE = 8;

    private final Map<String, BigDecimal> qtyStepCache = new ConcurrentHashMap<>();

    /**
     * Смещение времени клиента относительно Binance serverTime.
     * Нужен для борьбы с -1021 (timestamp outside recvWindow).
     */
    private volatile long timeOffsetMs = 0L;

    private final ExchangeSettingsService settingsService;
    private final RestTemplate rest;

    public BinanceExchangeClient(
            ExchangeSettingsService settingsService,
            @Qualifier("marketRestTemplate") RestTemplate rest
    ) {
        this.settingsService = settingsService;
        this.rest = rest;
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    private String baseUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? TEST : MAIN;
    }

    private ExchangeSettings resolve(Long chatId, NetworkType network) {
        return settingsService.getOrCreate(chatId, "BINANCE", network);
    }

    // =====================================================================
    // MARKET DATA
    // =====================================================================

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {
        return getKlines(symbol, interval, 0L, 0L, limit);
    }

    @Override
    public List<Kline> getKlines(
            String symbol,
            String interval,
            long startTimeMs,
            long endTimeMs,
            int limit
    ) throws Exception {

        // Публичные ручки: используем MAINNET (стабильнее)
        String sym = normalizeSymbolOrThrow(symbol);
        String tf = normalizeIntervalOrThrow(interval);

        int safeLimit = clamp(limit, 1, 1000);

        StringBuilder url = new StringBuilder(MAIN)
                .append("/api/v3/klines?symbol=").append(sym)
                .append("&interval=").append(tf)
                .append("&limit=").append(safeLimit);

        if (startTimeMs > 0) url.append("&startTime=").append(startTimeMs);
        if (endTimeMs > 0) url.append("&endTime=").append(endTimeMs);

        String body = rest.getForObject(url.toString(), String.class);
        if (body == null || body.isBlank()) return List.of();

        JSONArray arr = new JSONArray(body);
        List<Kline> out = new ArrayList<>(arr.length());

        for (int i = 0; i < arr.length(); i++) {
            JSONArray c = arr.getJSONArray(i);
            out.add(new Kline(
                    c.getLong(0),
                    c.getDouble(1),
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getDouble(4),
                    c.getDouble(5)
            ));
        }

        out.sort(Comparator.comparingLong(Kline::openTime));
        return out;
    }

    @Override
    public double getPrice(String symbol) throws Exception {
        // Публичные ручки: MAINNET
        String sym = normalizeSymbolOrThrow(symbol);

        try {
            String url = MAIN + "/api/v3/ticker/price?symbol=" + sym;
            String raw = rest.getForObject(url, String.class);
            if (raw == null || raw.isBlank()) return 0;

            JSONObject json = new JSONObject(raw);
            return json.optDouble("price", 0);

        } catch (Exception e) {
            log.warn("⚠️ BINANCE getPrice failed symbol={} msg={}", sym, e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getPrice", e);
            return 0;
        }
    }

    // =====================================================================
    // SIGNATURE / TIME SYNC
    // =====================================================================

    private String hmac(String data, String secret) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = m.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private long nowMs() {
        return System.currentTimeMillis() + timeOffsetMs;
    }

    private void syncTimeOffsetSafe() {
        try {
            String raw = rest.getForObject(MAIN + "/api/v3/time", String.class);
            if (raw == null || raw.isBlank()) return;
            JSONObject j = new JSONObject(raw);
            long serverTime = j.optLong("serverTime", 0L);
            if (serverTime <= 0) return;

            long local = System.currentTimeMillis();
            long offset = serverTime - local;

            this.timeOffsetMs = offset;

            if (log.isDebugEnabled()) {
                log.debug("BINANCE time sync: serverTime={} local={} offsetMs={}", serverTime, local, offset);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("BINANCE time sync failed: {}", e.toString());
        }
    }

    private String encode(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    private String toQuery(Map<String, String> p) {
        if (p == null || p.isEmpty()) return "";
        return p.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null)
                .map(e -> e.getKey().trim() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * Signed запрос (в query-string), с 1 ретраем на -1021 (timestamp).
     */
    private String signedRequest(
            ExchangeSettings s,
            String endpoint,
            Map<String, String> params,
            HttpMethod method
    ) throws Exception {
        return signedRequestInternal(s, endpoint, params, method, true);
    }

    private String signedRequestInternal(
            ExchangeSettings s,
            String endpoint,
            Map<String, String> params,
            HttpMethod method,
            boolean allowTimeResyncRetry
    ) throws Exception {

        Map<String, String> p = new LinkedHashMap<>(params != null ? params : Map.of());
        p.put("recvWindow", RECV_WINDOW);
        p.put("timestamp", String.valueOf(nowMs()));

        String query = toQuery(p);
        String sig = hmac(query, s.getApiSecret());

        String full = baseUrl(s.getNetwork()) + endpoint + "?" + query + "&signature=" + sig;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", s.getApiKey());

        try {
            ResponseEntity<String> resp = rest.exchange(full, method, new HttpEntity<>(null, headers), String.class);
            return resp.getBody();

        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            if (allowTimeResyncRetry && isTimestampError(body)) {
                syncTimeOffsetSafe();
                return signedRequestInternal(s, endpoint, params, method, false);
            }
            throw new RuntimeException("Binance error: " + body, e);
        }
    }

    private boolean isTimestampError(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JSONObject j = new JSONObject(body);
            int code = j.optInt("code", 0);
            String msg = j.optString("msg", "");
            return code == -1021 || msg.toLowerCase(Locale.ROOT).contains("timestamp");
        } catch (Exception ignored) {
            return body.toLowerCase(Locale.ROOT).contains("timestamp");
        }
    }

    // =====================================================================
    // ORDERS (NEW INTERFACE)
    // =====================================================================

    @Override
    public OrderResult placeOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal price,
            Map<String, String> extraParams
    ) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        ExchangeSettings s = resolve(chatId, network);

        String sym = normalizeSymbolOrThrow(symbol);
        String sd = normalizeUpperOrThrow(side, "side");
        String tp = normalizeUpperOrThrow(type, "type");

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", sym);
        p.put("side", sd);
        p.put("type", tp);

        Map<String, String> extra = sanitizeExtra(extraParams);

        // единый ключ от OrderServiceImpl -> Binance имя
        if (extra.containsKey("clientOrderId") && !extra.containsKey("newClientOrderId")) {
            extra.put("newClientOrderId", extra.get("clientOrderId"));
            extra.remove("clientOrderId");
        }

        boolean hasQuoteOrderQty = extra.containsKey("quoteOrderQty");

        // quantity ставим только если НЕ используем quoteOrderQty
        if (!hasQuoteOrderQty) {
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("quantity invalid (<=0)");
            }
            p.put("quantity", strip(quantity));
        } else {
            if (!"MARKET".equalsIgnoreCase(tp)) {
                throw new IllegalArgumentException("quoteOrderQty допустим только для MARKET");
            }
            if (!"BUY".equalsIgnoreCase(sd)) {
                throw new IllegalArgumentException("quoteOrderQty допустим только для BUY");
            }
        }

        // LIMIT параметры
        if ("LIMIT".equalsIgnoreCase(tp)) {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("LIMIT требует price > 0");
            }
            p.put("price", strip(price));

            // timeInForce: default GTC
            if (!extra.containsKey("timeInForce")) {
                p.put("timeInForce", "GTC");
            }
        }

        // расширенный ответ
        if (!extra.containsKey("newOrderRespType")) {
            extra.put("newOrderRespType", "MARKET".equalsIgnoreCase(tp) ? "FULL" : "RESULT");
        }

        // мердж extra в конец
        p.putAll(extra);

        String raw = signedRequest(s, "/api/v3/order", p, HttpMethod.POST);
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Binance order empty response");
        }

        JSONObject json = new JSONObject(raw);

        String orderId = json.optString("orderId", null);
        String status = json.optString("status", "NEW");

        BigDecimal executedQty = bdOrZero(json.optString("executedQty", null));
        BigDecimal cummulativeQuoteQty = bdOrZero(json.optString("cummulativeQuoteQty", null));
        BigDecimal avgPrice = computeAvgPrice(json, executedQty, cummulativeQuoteQty);
        BigDecimal baseCommission = extractBaseAssetCommission(sym, json.optJSONArray("fills"));
        BigDecimal sellableQty = executedQty;

        if ("BUY".equalsIgnoreCase(sd) && executedQty.signum() > 0 && baseCommission.signum() > 0) {
            sellableQty = executedQty.subtract(baseCommission);
            if (sellableQty.signum() < 0) {
                sellableQty = BigDecimal.ZERO;
            }

            log.info("🧾 BINANCE BUY commission applied sym={} grossQty={} baseCommission={} netQty={}",
                    sym,
                    strip(executedQty),
                    strip(baseCommission),
                    strip(sellableQty));
        }

        BigDecimal qtyOut;
        BigDecimal priceOut;

        if ("MARKET".equalsIgnoreCase(tp)) {
            if ("BUY".equalsIgnoreCase(sd)) {
                qtyOut = sellableQty.signum() > 0
                        ? sellableQty
                        : (executedQty.signum() > 0 ? executedQty : (quantity != null ? quantity : BigDecimal.ZERO));
            } else {
                qtyOut = executedQty.signum() > 0 ? executedQty : (quantity != null ? quantity : BigDecimal.ZERO);
            }
            priceOut = avgPrice.signum() > 0 ? avgPrice : bdOrZero(json.optString("price", null));
        } else {
            qtyOut = (quantity != null ? quantity : bdOrZero(json.optString("origQty", null)));
            priceOut = (price != null ? price : bdOrZero(json.optString("price", null)));
        }

        return new OrderResult(
                orderId,
                sym,
                sd,
                tp,
                qtyOut,
                priceOut,
                status,
                System.currentTimeMillis()
        );
    }

    @Override
    public Order placeMarketOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            OrderSide side,
            BigDecimal amount,
            OrderAmountType amountType,
            BigDecimal priceHint
    ) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        String sym = normalizeSymbolOrThrow(symbol);
        if (side == null) throw new IllegalArgumentException("side=null");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount invalid (<=0)");
        if (amountType == null) throw new IllegalArgumentException("amountType=null");

        BigDecimal usedPrice = positiveOrNull(priceHint);
        if (usedPrice == null) {
            double p = getPrice(sym);
            if (p > 0) {
                usedPrice = BigDecimal.valueOf(p);
            }
        }

        OrderResult r;

        if (amountType == OrderAmountType.BASE_QTY) {
            BigDecimal qtyBase = normalizeBaseQty(sym, network, amount);
            if (qtyBase == null || qtyBase.signum() <= 0) {
                throw new RuntimeException("BINANCE BASE_QTY normalization produced qtyBase=0");
            }

            log.info("🔄 BINANCE MARKET {} base->base sym={} baseQty={}",
                    side.name(),
                    sym,
                    strip(qtyBase));

            r = placeOrder(
                    chatId,
                    network,
                    sym,
                    side.name(),
                    "MARKET",
                    qtyBase,
                    null,
                    Map.of()
            );

        } else {
            BigDecimal qtyQuote = normalizeQuoteQty(amount);
            if (qtyQuote == null || qtyQuote.signum() <= 0) {
                throw new RuntimeException("BINANCE QUOTE_QTY normalization produced qtyQuote=0");
            }

            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("quoteOrderQty", strip(qtyQuote));

            try {
                log.info("🔄 BINANCE MARKET {} quote->quote sym={} quoteQty={} priceHint={}",
                        side.name(),
                        sym,
                        strip(qtyQuote),
                        strip(usedPrice));

                r = placeOrder(
                        chatId,
                        network,
                        sym,
                        side.name(),
                        "MARKET",
                        BigDecimal.ZERO,
                        null,
                        extra
                );

            } catch (RuntimeException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();

                if (!msg.contains("Quote order qty market orders are not support for this symbol")) {
                    throw ex;
                }

                if (usedPrice == null || usedPrice.signum() <= 0) {
                    throw new RuntimeException(
                            "BINANCE symbol не поддерживает quoteOrderQty, а цена для fallback-конвертации недоступна: " + sym,
                            ex
                    );
                }

                BigDecimal rawBase = qtyQuote.divide(usedPrice, 16, RoundingMode.DOWN);
                BigDecimal qtyBaseFallback = normalizeBaseQty(sym, network, rawBase);

                if (qtyBaseFallback == null || qtyBaseFallback.signum() <= 0) {
                    throw new RuntimeException(
                            "BINANCE fallback BASE_QTY conversion produced qtyBase=0 for symbol=" + sym,
                            ex
                    );
                }

                log.warn("⚠️ BINANCE sym={} не поддерживает quoteOrderQty. Перехожу на fallback через BASE_QTY: quoteQty={} price={} rawBase={} normBase={}",
                        sym,
                        strip(qtyQuote),
                        strip(usedPrice),
                        strip(rawBase),
                        strip(qtyBaseFallback));

                r = placeOrder(
                        chatId,
                        network,
                        sym,
                        side.name(),
                        "MARKET",
                        qtyBaseFallback,
                        null,
                        Map.of()
                );
            }
        }

        BigDecimal px = positiveOrNull(r.price()) != null
                ? r.price()
                : (positiveOrNull(usedPrice) != null ? usedPrice : BigDecimal.ZERO);

        BigDecimal q = positiveOrNull(r.qty()) != null
                ? r.qty()
                : BigDecimal.ZERO;

        return Order.builder()
                .orderId(r.orderId())
                .chatId(chatId)
                .symbol(r.symbol() != null ? r.symbol() : sym)
                .side(r.side() != null ? r.side() : side.name())
                .type(r.type() != null ? r.type() : "MARKET")
                .price(px)
                .quantity(q)
                .status(r.status())
                .filled("FILLED".equalsIgnoreCase(r.status()))
                .time(r.timestamp())
                .build();
    }



    private BigDecimal normalizeBaseQty(String symbol, NetworkType network, BigDecimal qty) {
        BigDecimal safeQty = positiveOrNull(qty);
        if (safeQty == null) return BigDecimal.ZERO;

        BigDecimal step = getQtyStep(symbol, network);
        if (positiveOrNull(step) != null) {
            BigDecimal normalized = safeQty.divide(step, 0, RoundingMode.DOWN).multiply(step);
            return normalized.setScale(Math.max(0, step.stripTrailingZeros().scale()), RoundingMode.DOWN).stripTrailingZeros();
        }

        return safeQty.setScale(FALLBACK_QTY_SCALE, RoundingMode.DOWN).stripTrailingZeros();
    }

    private BigDecimal getQtyStep(String symbol, NetworkType network) {
        String sym = normalizeSymbolOrThrow(symbol);
        NetworkType net = (network != null ? network : NetworkType.MAINNET);
        String cacheKey = net.name() + ":" + sym;

        BigDecimal cached = qtyStepCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String url = baseUrl(net) + "/api/v3/exchangeInfo?symbol=" + sym;
            String raw = rest.getForObject(url, String.class);
            if (raw == null || raw.isBlank()) return null;

            JSONObject root = new JSONObject(raw);
            JSONArray symbols = root.optJSONArray("symbols");
            if (symbols == null || symbols.isEmpty()) return null;

            JSONObject item = symbols.optJSONObject(0);
            if (item == null) return null;

            JSONArray filters = item.optJSONArray("filters");
            if (filters == null || filters.isEmpty()) return null;

            BigDecimal marketLotStep = null;
            BigDecimal lotStep = null;

            for (int i = 0; i < filters.length(); i++) {
                JSONObject filter = filters.optJSONObject(i);
                if (filter == null) continue;

                String filterType = filter.optString("filterType", "");
                if ("MARKET_LOT_SIZE".equalsIgnoreCase(filterType)) {
                    marketLotStep = bdOrNull(filter.optString("stepSize", null));
                } else if ("LOT_SIZE".equalsIgnoreCase(filterType)) {
                    lotStep = bdOrNull(filter.optString("stepSize", null));
                }
            }

            BigDecimal step = firstPositive(marketLotStep, lotStep);
            if (positiveOrNull(step) != null) {
                qtyStepCache.put(cacheKey, step);
            }
            return step;

        } catch (Exception e) {
            log.debug("BINANCE qtyStep resolve failed sym={} net={} msg={}", sym, net, e.toString());
            return null;
        }
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal value : values) {
            if (positiveOrNull(value) != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal normalizeQuoteQty(BigDecimal quoteQty) {
        BigDecimal safe = positiveOrNull(quoteQty);
        if (safe == null) return BigDecimal.ZERO;
        return safe.stripTrailingZeros();
    }

    /**
     * ✅ OCO (SPOT) — настоящий Binance OCO.
     * Используем /api/v3/order/oco.
     */
    @Override
    public OcoResult placeOcoOrder(
            Long chatId,
            NetworkType network,
            String symbol,
            BigDecimal quantityBase,
            BigDecimal takeProfitPrice,
            BigDecimal stopPrice,
            BigDecimal stopLimitPrice,
            Map<String, String> extraParams
    ) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        String sym = normalizeSymbolOrThrow(symbol);

        if (quantityBase == null || quantityBase.signum() <= 0) {
            throw new IllegalArgumentException("quantityBase invalid");
        }
        if (takeProfitPrice == null || takeProfitPrice.signum() <= 0) {
            throw new IllegalArgumentException("takeProfitPrice invalid");
        }
        if (stopPrice == null || stopPrice.signum() <= 0) {
            throw new IllegalArgumentException("stopPrice invalid");
        }
        if (stopLimitPrice == null || stopLimitPrice.signum() <= 0) {
            // на бинансе stopLimitPrice обязателен для OCO
            throw new IllegalArgumentException("stopLimitPrice invalid (binance requires stopLimitPrice)");
        }

        ExchangeSettings s = resolve(chatId, network);

        Map<String, String> extra = sanitizeExtra(extraParams);

        // единый clientOrderId -> listClientOrderId
        if (extra.containsKey("clientOrderId") && !extra.containsKey("listClientOrderId")) {
            extra.put("listClientOrderId", extra.get("clientOrderId"));
            extra.remove("clientOrderId");
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", sym);
        p.put("side", "SELL"); // классический OCO для фиксации позиции
        p.put("quantity", strip(quantityBase));

        // take profit leg: limit price
        p.put("price", strip(takeProfitPrice));

        // stop leg:
        p.put("stopPrice", strip(stopPrice));
        p.put("stopLimitPrice", strip(stopLimitPrice));

        // для stopLimit leg нужен tif
        if (!extra.containsKey("stopLimitTimeInForce")) {
            p.put("stopLimitTimeInForce", "GTC");
        }

        // иногда полезно задать отдельные clientOrderId на ноги
        // (оставляем как расширение: limitClientOrderId, stopClientOrderId)
        p.putAll(extra);

        String raw = signedRequest(s, "/api/v3/order/oco", p, HttpMethod.POST);
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("Binance OCO empty response");
        }

        JSONObject json = new JSONObject(raw);

        String orderListId = String.valueOf(json.optLong("orderListId", 0L));
        if ("0".equals(orderListId)) {
            // иногда возвращает строкой — перестрахуемся
            orderListId = json.optString("orderListId", null);
        }

        // Binance: listOrderStatus / listStatusType
        String status = json.optString("listOrderStatus", null);
        if (status == null || status.isBlank()) status = json.optString("listStatusType", "NEW");

        // в orders[] лежат два ордера
        String tpId = null;
        String slId = null;
        JSONArray orders = json.optJSONArray("orders");
        if (orders != null && !orders.isEmpty()) {
            for (int i = 0; i < orders.length(); i++) {
                JSONObject o = orders.optJSONObject(i);
                if (o == null) continue;
                String oid = o.optString("orderId", null);
                if (oid == null || oid.isBlank()) continue;

                // heuristic: stop-leg обычно имеет "stopPrice" в report / либо тип STOP_LOSS_LIMIT в orderReports
                // тут оставим простое: первый в tpId, второй в slId
                if (tpId == null) tpId = oid;
                else if (slId == null) slId = oid;
            }
        }

        long ts = System.currentTimeMillis();
        return new OcoResult(
                orderListId,
                sym,
                status != null ? status.trim().toUpperCase(Locale.ROOT) : "NEW",
                tpId,
                slId,
                ts
        );
    }

    @Override
    public boolean cancelOrder(Long chatId, NetworkType network, String symbol, String orderId) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId пустой");

        ExchangeSettings s = resolve(chatId, network);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", normalizeSymbolOrThrow(symbol));
        p.put("orderId", orderId.trim());

        String raw = signedRequest(s, "/api/v3/order", p, HttpMethod.DELETE);
        return raw != null && raw.contains("orderId");
    }

    // =====================================================================
    // ✅ RECONCILE (openOrders / order / myTrades)
    // =====================================================================

    @Override
    public List<OrderSnapshot> getOpenOrders(Long chatId, NetworkType network, String symbol) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        ExchangeSettings s = resolve(chatId, network);

        Map<String, String> p = new LinkedHashMap<>();
        String sym = normalizeSymbolOrThrow(symbol);
        p.put("symbol", sym);

        String raw = signedRequest(s, "/api/v3/openOrders", p, HttpMethod.GET);
        if (raw == null || raw.isBlank()) return List.of();

        JSONArray arr = new JSONArray(raw);
        List<OrderSnapshot> out = new ArrayList<>(arr.length());

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(mapOrderSnapshotFromBinance(o));
        }

        return out;
    }

    @Override
    public OrderSnapshot getOrder(Long chatId, NetworkType network, String symbol, String orderIdOrClientOrderId) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");
        if (orderIdOrClientOrderId == null || orderIdOrClientOrderId.isBlank()) {
            throw new IllegalArgumentException("orderIdOrClientOrderId blank");
        }

        ExchangeSettings s = resolve(chatId, network);

        String sym = normalizeSymbolOrThrow(symbol);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", sym);

        String key = isNumeric(orderIdOrClientOrderId.trim()) ? "orderId" : "origClientOrderId";
        p.put(key, orderIdOrClientOrderId.trim());

        String raw = signedRequest(s, "/api/v3/order", p, HttpMethod.GET);
        if (raw == null || raw.isBlank()) return null;

        JSONObject json = new JSONObject(raw);
        return mapOrderSnapshotFromBinance(json);
    }

    @Override
    public List<TradeFill> getMyTrades(Long chatId,
                                       NetworkType network,
                                       String symbol,
                                       long startTimeMs,
                                       long endTimeMs,
                                       int limit) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        ExchangeSettings s = resolve(chatId, network);

        String sym = normalizeSymbolOrThrow(symbol);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", sym);

        int safeLimit = clamp(limit, 1, 1000);
        p.put("limit", String.valueOf(safeLimit));

        if (startTimeMs > 0) p.put("startTime", String.valueOf(startTimeMs));
        if (endTimeMs > 0) p.put("endTime", String.valueOf(endTimeMs));

        String raw = signedRequest(s, "/api/v3/myTrades", p, HttpMethod.GET);
        if (raw == null || raw.isBlank()) return List.of();

        JSONArray arr = new JSONArray(raw);
        List<TradeFill> out = new ArrayList<>(arr.length());

        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;

            String tradeId = String.valueOf(t.optLong("id", 0L));
            String orderId = String.valueOf(t.optLong("orderId", 0L));

            BigDecimal price = bdOrZero(t.optString("price", null));
            BigDecimal qty = bdOrZero(t.optString("qty", null));
            BigDecimal quoteQty = bdOrZero(t.optString("quoteQty", null));

            BigDecimal commission = bdOrZero(t.optString("commission", null));
            String commissionAsset = t.optString("commissionAsset", null);

            boolean isBuyer = t.optBoolean("isBuyer", false);
            String side = isBuyer ? "BUY" : "SELL";

            long timeMs = t.optLong("time", 0L);

            out.add(new TradeFill(
                    tradeId,
                    orderId,
                    sym,
                    side,
                    price,
                    qty,
                    quoteQty,
                    commission,
                    commissionAsset,
                    timeMs
            ));
        }

        return out;
    }

    private OrderSnapshot mapOrderSnapshotFromBinance(JSONObject o) {

        String orderId = String.valueOf(o.optLong("orderId", 0L));
        if ("0".equals(orderId)) orderId = o.optString("orderId", null);

        String clientOrderId = o.optString("clientOrderId", null);

        String symbol = o.optString("symbol", null);
        String side = o.optString("side", null);
        String type = o.optString("type", null);
        String status = o.optString("status", null);

        BigDecimal origQty = bdOrZero(o.optString("origQty", null));
        BigDecimal executedQty = bdOrZero(o.optString("executedQty", null));

        BigDecimal price = bdOrZero(o.optString("price", null));

        // Binance order detail может содержать cummulativeQuoteQty
        BigDecimal cumQuote = bdOrZero(o.optString("cummulativeQuoteQty", null));
        BigDecimal avgPrice = BigDecimal.ZERO;
        if (executedQty.signum() > 0 && cumQuote.signum() > 0) {
            avgPrice = cumQuote.divide(executedQty, 12, RoundingMode.HALF_UP);
        }

        long updateTime = o.optLong("updateTime", 0L);
        if (updateTime <= 0) updateTime = o.optLong("time", 0L);

        return new OrderSnapshot(
                orderId,
                clientOrderId,
                symbol,
                side,
                type,
                status,
                origQty,
                executedQty,
                price,
                avgPrice.signum() > 0 ? avgPrice : null,
                updateTime
        );
    }

    // =====================================================================
    // BALANCE
    // =====================================================================

    @Override
    public Balance getBalance(Long chatId, String asset, NetworkType network) throws Exception {
        Map<String, Balance> all = getFullBalance(chatId, network);
        String a = asset == null ? "" : asset.trim().toUpperCase(Locale.ROOT);
        return all.getOrDefault(a, new Balance(a, 0, 0));
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId, NetworkType network) throws Exception {

        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");

        ExchangeSettings s = resolve(chatId, network);
        String body = signedRequest(s, "/api/v3/account", new HashMap<>(), HttpMethod.GET);
        if (body == null || body.isBlank()) return Map.of();

        Map<String, Balance> out = new LinkedHashMap<>();
        JSONArray arr = new JSONObject(body).optJSONArray("balances");
        if (arr == null) return Map.of();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            double free = parseDoubleSafe(o.optString("free", "0"));
            double locked = parseDoubleSafe(o.optString("locked", "0"));

            if (free + locked > 0) {
                String a = o.optString("asset", "").trim().toUpperCase(Locale.ROOT);
                if (!a.isBlank()) out.put(a, new Balance(a, free, locked));
            }
        }

        return out;
    }

    // =====================================================================
    // SYMBOLS
    // =====================================================================

    @Override
    public List<String> getAllSymbols() {
        try {
            String body = rest.getForObject(MAIN + "/api/v3/exchangeInfo", String.class);
            if (body == null || body.isBlank()) return List.of();

            JSONArray arr = new JSONObject(body).optJSONArray("symbols");
            if (arr == null) return List.of();

            List<String> list = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("TRADING".equalsIgnoreCase(o.optString("status"))) {
                    String sym = o.optString("symbol", "").trim().toUpperCase(Locale.ROOT);
                    if (!sym.isBlank()) list.add(sym);
                }
            }

            list.sort(String::compareTo);
            return list;

        } catch (Exception e) {
            log.warn("⚠️ BINANCE getAllSymbols failed: {}", e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAllSymbols", e);
            return List.of();
        }
    }

    // =====================================================================
    // ACCOUNT INFO / FEES
    // =====================================================================

    @Override
    public AccountInfo getAccountInfo(long chatId, NetworkType networkType) {

        try {
            ExchangeSettings s = resolve(chatId, networkType);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("recvWindow", RECV_WINDOW);
            params.put("timestamp", String.valueOf(nowMs()));

            String query = toQuery(params);
            String sign = hmac(query, s.getApiSecret());

            String url = baseUrl(networkType) + "/api/v3/account"
                         + "?" + query + "&signature=" + sign;

            HttpHeaders h = new HttpHeaders();
            h.set("X-MBX-APIKEY", s.getApiKey());

            String body = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(null, h), String.class).getBody();
            if (body == null || body.isBlank()) throw new RuntimeException("empty accountInfo");

            JSONObject json = new JSONObject(body);

            BigDecimal makerPct = BigDecimal.valueOf(json.optInt("makerCommission", 10))
                    .divide(BigDecimal.valueOf(10000), 8, RoundingMode.HALF_UP);

            BigDecimal takerPct = BigDecimal.valueOf(json.optInt("takerCommission", 10))
                    .divide(BigDecimal.valueOf(10000), 8, RoundingMode.HALF_UP);

            int vip = json.optInt("feeTier", 0);

            boolean hasBNB = false;
            JSONArray balances = json.optJSONArray("balances");
            if (balances != null) {
                for (int i = 0; i < balances.length(); i++) {
                    JSONObject b = balances.optJSONObject(i);
                    if (b == null) continue;
                    String asset = b.optString("asset", "");
                    if (!"BNB".equalsIgnoreCase(asset)) continue;
                    double free = parseDoubleSafe(b.optString("free", "0"));
                    if (free > 0.0001) {
                        hasBNB = true;
                        break;
                    }
                }
            }

            BigDecimal makerDiscount = hasBNB ? makerPct.multiply(BigDecimal.valueOf(0.75)) : makerPct;
            BigDecimal takerDiscount = hasBNB ? takerPct.multiply(BigDecimal.valueOf(0.75)) : takerPct;

            return AccountInfo.builder()
                    .makerFee(makerPct.doubleValue())
                    .takerFee(takerPct.doubleValue())
                    .makerFeeWithDiscount(makerDiscount.doubleValue())
                    .takerFeeWithDiscount(takerDiscount.doubleValue())
                    .vipLevel(vip)
                    .usingBnbDiscount(hasBNB)
                    .build();

        } catch (Exception e) {
            log.warn("⚠️ BINANCE getAccountInfo failed chatId={} network={} msg={}",
                    chatId, networkType, e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAccountInfo", e);

            return AccountInfo.builder()
                    .makerFee(0.001)
                    .takerFee(0.001)
                    .makerFeeWithDiscount(0.001)
                    .takerFeeWithDiscount(0.001)
                    .vipLevel(0)
                    .usingBnbDiscount(false)
                    .build();
        }
    }

    @Override
    public AccountFees getAccountFees(long chatId, NetworkType networkType) {

        try {
            ExchangeSettings s = resolve(chatId, networkType);

            String body = signedRequest(
                    s,
                    "/api/v3/account",
                    new LinkedHashMap<>(),
                    HttpMethod.GET
            );

            if (body == null || body.isBlank()) return null;

            JSONObject json = new JSONObject(body);

            BigDecimal makerPct = BigDecimal
                    .valueOf(json.getInt("makerCommission"))
                    .divide(BigDecimal.valueOf(10000), 8, RoundingMode.HALF_UP);

            BigDecimal takerPct = BigDecimal
                    .valueOf(json.getInt("takerCommission"))
                    .divide(BigDecimal.valueOf(10000), 8, RoundingMode.HALF_UP);

            return AccountFees.builder()
                    .makerPct(makerPct)
                    .takerPct(takerPct)
                    .build();

        } catch (Exception e) {
            log.warn("⚠️ BINANCE getAccountFees failed chatId={} network={} msg={}",
                    chatId, networkType, e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAccountFees", e);
            return null;
        }
    }

    // =====================================================================
    // TRADABLE SYMBOLS (public)
    // =====================================================================

    @Override
    public List<SymbolDescriptor> getTradableSymbols(String quoteAsset) {

        final String baseUrl = MAIN;

        String qa = (quoteAsset == null || quoteAsset.isBlank())
                ? "USDT"
                : quoteAsset.trim().toUpperCase(Locale.ROOT);

        JSONObject info;
        try {
            String infoBody = getForStringWithRetry(baseUrl + "/api/v3/exchangeInfo", "exchangeInfo");
            if (infoBody == null || infoBody.isBlank()) return List.of();
            info = new JSONObject(infoBody);
        } catch (Exception e) {
            log.warn("⚠️ BINANCE exchangeInfo failed: asset={} err={}", qa, e.toString());
            return List.of();
        }

        JSONArray symbols = info.optJSONArray("symbols");
        if (symbols == null || symbols.isEmpty()) return List.of();

        Map<String, JSONObject> tickerMap = new HashMap<>();
        try {
            String tickerBody = getForStringWithRetry(baseUrl + "/api/v3/ticker/24hr", "ticker24h");
            if (tickerBody != null && !tickerBody.isBlank()) {
                JSONArray tickers = new JSONArray(tickerBody);
                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject t = tickers.optJSONObject(i);
                    if (t == null) continue;
                    String sym = t.optString("symbol", null);
                    if (sym != null && !sym.isBlank()) tickerMap.put(sym, t);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ BINANCE ticker/24hr failed: asset={} err={}", qa, e.toString());
            tickerMap = Map.of();
        }

        List<SymbolDescriptor> out = new ArrayList<>(Math.min(symbols.length(), 2000));

        for (int i = 0; i < symbols.length(); i++) {

            JSONObject s = symbols.optJSONObject(i);
            if (s == null) continue;

            String symbol = s.optString("symbol", null);
            if (symbol == null || symbol.isBlank()) continue;

            String status = s.optString("status", "");
            String base = s.optString("baseAsset", null);
            String quote = s.optString("quoteAsset", null);

            if (quote == null || !qa.equalsIgnoreCase(quote)) continue;
            if (!"TRADING".equalsIgnoreCase(status)) continue;
            if (!isSpotAllowed(s)) continue;

            BigDecimal minNotional = null;
            BigDecimal stepSize = null;
            BigDecimal tickSize = null;
            Integer maxOrders = null;

            JSONArray filters = s.optJSONArray("filters");
            if (filters != null) {
                for (int f = 0; f < filters.length(); f++) {
                    JSONObject filter = filters.optJSONObject(f);
                    if (filter == null) continue;

                    String type = filter.optString("filterType", "");
                    if (type.isBlank()) continue;

                    switch (type) {
                        case "MIN_NOTIONAL", "NOTIONAL" -> {
                            BigDecimal v = bdOrNull(filter.optString("minNotional", null));
                            if (v != null) minNotional = v;
                        }
                        case "LOT_SIZE" -> {
                            BigDecimal v = bdOrNull(filter.optString("stepSize", null));
                            if (v != null) stepSize = v;
                        }
                        case "MARKET_LOT_SIZE" -> {
                            if (stepSize == null) {
                                BigDecimal v = bdOrNull(filter.optString("stepSize", null));
                                if (v != null) stepSize = v;
                            }
                        }
                        case "PRICE_FILTER" -> {
                            BigDecimal v = bdOrNull(filter.optString("tickSize", null));
                            if (v != null) tickSize = v;
                        }
                        case "MAX_NUM_ORDERS" -> {
                            int v = filter.optInt("maxNumOrders", 0);
                            maxOrders = v > 0 ? v : null;
                        }
                    }
                }
            }

            JSONObject t = tickerMap.get(symbol);

            BigDecimal lastPrice = (t != null) ? bdOrNull(t.optString("lastPrice", null)) : null;
            BigDecimal priceChangePct = (t != null) ? bdOrNull(t.optString("priceChangePercent", null)) : null;
            BigDecimal volume = (t != null) ? bdOrNull(t.optString("quoteVolume", null)) : null;

            out.add(SymbolDescriptor.of(
                    symbol,
                    base,
                    quote,
                    lastPrice,
                    priceChangePct,
                    volume,
                    minNotional,
                    stepSize,
                    tickSize,
                    maxOrders,
                    true,
                    "BINANCE"
            ));
        }

        return out;
    }

    // =====================================================================
    // AVG PRICE HELPERS
    // =====================================================================

    private BigDecimal extractBaseAssetCommission(String symbol, JSONArray fills) {
        if (fills == null || fills.isEmpty()) return BigDecimal.ZERO;

        String baseAsset = guessBaseAsset(symbol);
        if (baseAsset == null || baseAsset.isBlank()) return BigDecimal.ZERO;

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < fills.length(); i++) {
            JSONObject f = fills.optJSONObject(i);
            if (f == null) continue;

            String commissionAsset = f.optString("commissionAsset", "").trim().toUpperCase(Locale.ROOT);
            if (!baseAsset.equalsIgnoreCase(commissionAsset)) continue;

            BigDecimal commission = bdOrNull(f.optString("commission", null));
            if (commission != null && commission.signum() > 0) {
                total = total.add(commission);
            }
        }
        return total;
    }

    private String guessBaseAsset(String symbol) {
        String s = normalizeSymbolOrThrow(symbol);
        for (String quote : List.of("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "BTC", "ETH", "BNB", "EUR", "TRY", "BRL", "GBP", "UAH", "PLN")) {
            if (s.endsWith(quote) && s.length() > quote.length()) {
                return s.substring(0, s.length() - quote.length());
            }
        }
        return null;
    }

    private BigDecimal computeAvgPrice(JSONObject orderJson, BigDecimal executedQty, BigDecimal cummulativeQuoteQty) {

        // 1) fills[] (FULL response)
        JSONArray fills = orderJson.optJSONArray("fills");
        if (fills != null && !fills.isEmpty()) {
            BigDecimal sumQuote = BigDecimal.ZERO;
            BigDecimal sumQty = BigDecimal.ZERO;

            for (int i = 0; i < fills.length(); i++) {
                JSONObject f = fills.optJSONObject(i);
                if (f == null) continue;

                BigDecimal px = bdOrNull(f.optString("price", null));
                BigDecimal q = bdOrNull(f.optString("qty", null));
                if (px == null || q == null) continue;
                if (q.signum() <= 0 || px.signum() <= 0) continue;

                sumQty = sumQty.add(q);
                sumQuote = sumQuote.add(px.multiply(q));
            }

            if (sumQty.signum() > 0 && sumQuote.signum() > 0) {
                return sumQuote.divide(sumQty, 12, RoundingMode.HALF_UP);
            }
        }

        // 2) cummulativeQuoteQty / executedQty
        if (executedQty != null && executedQty.signum() > 0 && cummulativeQuoteQty != null && cummulativeQuoteQty.signum() > 0) {
            return cummulativeQuoteQty.divide(executedQty, 12, RoundingMode.HALF_UP);
        }

        return BigDecimal.ZERO;
    }

    // =====================================================================
    // MISC HELPERS
    // =====================================================================

    private Map<String, String> sanitizeExtra(Map<String, String> extraParams) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (extraParams == null || extraParams.isEmpty()) return extra;

        for (Map.Entry<String, String> e : extraParams.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (e.getValue() == null) continue;

            String k = e.getKey().trim();
            String v = String.valueOf(e.getValue()).trim();
            if (!k.isEmpty() && !v.isEmpty()) extra.put(k, v);
        }
        return extra;
    }

    private boolean isSpotAllowed(JSONObject symbolJson) {
        JSONArray perms = symbolJson.optJSONArray("permissions");
        if (perms != null && !perms.isEmpty()) {
            boolean hasSpot = false;
            for (int i = 0; i < perms.length(); i++) {
                String p = perms.optString(i, "");
                if ("SPOT".equalsIgnoreCase(p)) {
                    hasSpot = true;
                    break;
                }
            }
            if (!hasSpot) return false;
        }
        return symbolJson.optBoolean("isSpotTradingAllowed", true);
    }

    private String getForStringWithRetry(String url, String label) {
        try {
            return rest.getForObject(url, String.class);
        } catch (Exception e1) {
            try {
                log.warn("⚠️ BINANCE {} failed, retrying once… err={}", label, e1.toString());
                return rest.getForObject(url, String.class);
            } catch (Exception e2) {
                throw e2;
            }
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static double parseDoubleSafe(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static BigDecimal bdOrZero(String s) {
        BigDecimal v = bdOrNull(s);
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal bdOrNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeSymbolOrThrow(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("symbol=null");
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("symbol пустой");
        return s;
    }

    private static String normalizeIntervalOrThrow(String interval) {
        if (interval == null) throw new IllegalArgumentException("interval=null");
        String s = interval.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("interval пустой");
        return s;
    }

    private static String normalizeUpperOrThrow(String v, String name) {
        if (v == null) throw new IllegalArgumentException(name + "=null");
        String s = v.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException(name + " пустой");
        return s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String strip(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }
}




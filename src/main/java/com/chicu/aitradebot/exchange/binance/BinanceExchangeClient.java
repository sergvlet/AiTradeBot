package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BinanceExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.binance.com";
    private static final String TEST = "https://testnet.binance.vision";

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

    private ExchangeSettings resolve(Long chatId) {
        return settingsService.findAllByChatId(chatId)
                .stream()
                .findFirst()
                .orElseGet(() -> settingsService.getOrCreate(chatId, "BINANCE", NetworkType.MAINNET));
    }

    private ExchangeSettings resolve(Long chatId, NetworkType network) {
        return settingsService.getOrCreate(chatId, "BINANCE", network);
    }

    // =====================================================================
    // MARKET DATA
    // =====================================================================

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {

        String url = MAIN + "/api/v3/klines?symbol=" + symbol +
                     "&interval=" + interval + "&limit=" + limit;

        JSONArray arr = new JSONArray(rest.getForObject(url, String.class));
        List<Kline> out = new ArrayList<>();

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

        return out;
    }

    @Override
    public double getPrice(String symbol) throws Exception {
        try {
            String url = MAIN + "/api/v3/ticker/price?symbol=" + symbol.toUpperCase();
            JSONObject json = new JSONObject(rest.getForObject(url, String.class));
            return json.getDouble("price");
        } catch (Exception e) {
            log.error("Ошибка getPrice Binance: {}", e.getMessage());
            return 0;
        }
    }

    // =====================================================================
    // SIGNATURE
    // =====================================================================

    private String hmac(String data, String secret) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = m.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));

            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подписи Binance", e);
        }
    }

    private String signedRequest(
            ExchangeSettings s,
            String endpoint,
            Map<String, String> params,
            HttpMethod method
    ) throws Exception {

        params.put("recvWindow", "5000");
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));

        String query = params.entrySet()
                .stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        String sig = hmac(query, s.getApiSecret());

        String full = baseUrl(s.getNetwork()) + endpoint + "?" + query + "&signature=" + sig;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", s.getApiKey());

        try {
            return rest.exchange(full, method, new HttpEntity<>(null, headers), String.class).getBody();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Binance error: " + e.getResponseBodyAsString());
        }
    }

    private static String strip(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // =====================================================================
    // ORDERS
    // =====================================================================

    @Override
    public OrderResult placeOrder(
            Long chatId,
            String symbol,
            String side,
            String type,
            double qty,
            Double price
    ) throws Exception {

        ExchangeSettings s = resolve(chatId);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("side", side);
        p.put("type", type);
        p.put("quantity", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            p.put("price", strip(price));
            p.put("timeInForce", "GTC");
        }

        String r = signedRequest(s, "/api/v3/order", p, HttpMethod.POST);
        JSONObject json = new JSONObject(r);

        return new OrderResult(
                json.optString("orderId"),
                symbol,
                side,
                type,
                qty,
                price == null ? 0 : price,
                json.optString("status", "NEW"),
                System.currentTimeMillis()
        );
    }

    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) throws Exception {

        ExchangeClient.OrderResult r = placeOrder(
                0L,                    // chatId не нужен бирже
                symbol,
                side.name(),
                "MARKET",
                qty.doubleValue(),
                null
        );

        return Order.builder()
                .orderId(r.orderId())
                .chatId(0L)                             // Binance не знает chatId
                .symbol(r.symbol())
                .side(r.side())
                .type(r.type())
                .price(BigDecimal.valueOf(r.price()))
                .quantity(BigDecimal.valueOf(r.qty()))
                .status(r.status())
                .filled("FILLED".equalsIgnoreCase(r.status()))
                .time(r.timestamp())
                .strategyType(StrategyType.SCALPING)
                .build();
    }

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) throws Exception {

        ExchangeSettings s = resolve(chatId);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("orderId", orderId);

        String r = signedRequest(s, "/api/v3/order", p, HttpMethod.DELETE);

        return r.contains("orderId");
    }

    // =====================================================================
    // BALANCE
    // =====================================================================

    @Override
    public Balance getBalance(Long chatId, String asset, NetworkType network) throws Exception {
        Map<String, Balance> all = getFullBalance(chatId, network);
        return all.getOrDefault(asset, new Balance(asset, 0, 0));
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId, NetworkType network) throws Exception {

        ExchangeSettings s = resolve(chatId, network);
        String body = signedRequest(s, "/api/v3/account", new HashMap<>(), HttpMethod.GET);

        Map<String, Balance> out = new LinkedHashMap<>();
        JSONArray arr = new JSONObject(body).getJSONArray("balances");

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            double free = o.getDouble("free");
            double locked = o.getDouble("locked");

            if (free + locked > 0) {
                out.put(o.getString("asset"), new Balance(o.getString("asset"), free, locked));
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
            JSONArray arr = new JSONObject(body).getJSONArray("symbols");

            List<String> list = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                if ("TRADING".equalsIgnoreCase(o.optString("status"))) {
                    list.add(o.getString("symbol"));
                }
            }

            list.sort(String::compareTo);
            return list;

        } catch (Exception e) {
            log.error("Ошибка getAllSymbols Binance: {}", e.getMessage());
            return List.of();
        }
    }

    // =====================================================================
    // ACCOUNT INFO
    // =====================================================================

    @Override
    public AccountInfo getAccountInfo(long chatId, NetworkType networkType) {
        try {
            ExchangeSettings s = resolve(chatId, networkType);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "5000");

            String query = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            String sign = hmac(query, s.getApiSecret());

            String url = baseUrl(networkType) + "/api/v3/account"
                         + "?" + query + "&signature=" + sign;

            HttpHeaders h = new HttpHeaders();
            h.set("X-MBX-APIKEY", s.getApiKey());

            String body = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(null, h), String.class).getBody();

            JSONObject json = new JSONObject(body);

            double maker = json.optDouble("makerCommission", 10) / 100.0;
            double taker = json.optDouble("takerCommission", 10) / 100.0;

            int vip = json.optInt("feeTier", 0);

            boolean hasBNB = json.getJSONArray("balances")
                    .toList().stream()
                    .anyMatch(o -> {
                        Map<?, ?> m = (Map<?, ?>) o;
                        return "BNB".equalsIgnoreCase((String) m.get("asset"))
                               && Double.parseDouble((String) m.get("free")) > 0.0001;
                    });

            double makerDiscount = hasBNB ? maker * 0.75 : maker;
            double takerDiscount = hasBNB ? taker * 0.75 : taker;

            return AccountInfo.builder()
                    .makerFee(maker)
                    .takerFee(taker)
                    .makerFeeWithDiscount(makerDiscount)
                    .takerFeeWithDiscount(takerDiscount)
                    .vipLevel(vip)
                    .usingBnbDiscount(hasBNB)
                    .build();

        } catch (Exception e) {
            log.error("❌ Ошибка getAccountInfo Binance: {}", e.getMessage());

            return AccountInfo.builder()
                    .makerFee(0.1)
                    .takerFee(0.1)
                    .makerFeeWithDiscount(0.1)
                    .takerFeeWithDiscount(0.1)
                    .vipLevel(0)
                    .usingBnbDiscount(false)
                    .build();
        }
    }

    @Override
    public AccountFees getAccountFees(long chatId, NetworkType networkType) {

        try {
            ExchangeSettings s = resolve(chatId, networkType);

            // Binance: комиссии возвращаются в bips
            // 10 = 0.1%  → делим на 10000
            String body = signedRequest(
                    s,
                    "/api/v3/account",
                    new LinkedHashMap<>(),
                    HttpMethod.GET
            );

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
            log.warn("⚠️ Binance getAccountFees failed chatId={} network={}: {}",
                    chatId, networkType, e.getMessage());

            return null;
        }
    }

    @Override
    public List<SymbolDescriptor> getTradableSymbols(String quoteAsset) {

        // ⚠️ Для списка символов Binance ВСЕГДА используем MAINNET (публичные ручки)
        final String baseUrl = MAIN;

        String qa = (quoteAsset == null || quoteAsset.isBlank())
                ? "USDT"
                : quoteAsset.trim().toUpperCase();

        // =========================================================
        // 1) exchangeInfo — символы + фильтры
        // =========================================================
        JSONObject info;
        try {
            String infoBody = getForStringWithRetry(baseUrl + "/api/v3/exchangeInfo", "exchangeInfo");
            if (infoBody == null || infoBody.isBlank()) return List.of();
            info = new JSONObject(infoBody);
        } catch (Exception e) {
            log.warn("⚠️ BINANCE exchangeInfo failed: asset={} err={}", qa, e.toString());
            return List.of(); // без exchangeInfo вообще нечего строить
        }

        JSONArray symbols = info.optJSONArray("symbols");
        if (symbols == null || symbols.isEmpty()) return List.of();

        // =========================================================
        // 2) ticker 24h — цена / объём / изменение (может упасть)
        // =========================================================
        Map<String, JSONObject> tickerMap = new HashMap<>();
        try {
            String tickerBody = getForStringWithRetry(baseUrl + "/api/v3/ticker/24hr", "ticker24h");
            if (tickerBody != null && !tickerBody.isBlank()) {
                JSONArray tickers = new JSONArray(tickerBody);
                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject t = tickers.optJSONObject(i);
                    if (t == null) continue;
                    String sym = t.optString("symbol", null);
                    if (sym != null && !sym.isBlank()) {
                        tickerMap.put(sym, t);
                    }
                }
            }
        } catch (Exception e) {
            // ⚠️ важно: не ломаем UI — просто работаем без тикера
            log.warn("⚠️ BINANCE ticker/24hr failed: asset={} err={}", qa, e.toString());
            tickerMap = Map.of();
        }

        List<SymbolDescriptor> out = new ArrayList<>(Math.min(symbols.length(), 2000));

        // =========================================================
        // 3) Основной проход по символам
        // =========================================================
        for (int i = 0; i < symbols.length(); i++) {

            JSONObject s = symbols.optJSONObject(i);
            if (s == null) continue;

            String symbol = s.optString("symbol", null);
            if (symbol == null || symbol.isBlank()) continue;

            String status = s.optString("status", "");
            String base = s.optString("baseAsset", null);
            String quote = s.optString("quoteAsset", null);

            // --- фильтрация по котируемому активу ---
            if (quote == null || !qa.equalsIgnoreCase(quote)) continue;

            // --- только TRADING ---
            if (!"TRADING".equalsIgnoreCase(status)) continue;

            // --- SPOT allowed: надёжно ---
            if (!isSpotAllowed(s)) continue;

            // =====================================================
            // 4) Разбор filters
            // =====================================================
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

                        // ✅ поддерживаем оба варианта
                        case "MIN_NOTIONAL", "NOTIONAL" -> {
                            // У Binance в обоих обычно поле minNotional
                            BigDecimal v = bdOrNull(filter.optString("minNotional", null));
                            if (v != null) minNotional = v;
                        }

                        // LOT_SIZE для лимитных, MARKET_LOT_SIZE для маркетов — берём любой, но LOT_SIZE предпочтительнее
                        case "LOT_SIZE" -> {
                            BigDecimal v = bdOrNull(filter.optString("stepSize", null));
                            if (v != null) stepSize = v;
                        }

                        case "MARKET_LOT_SIZE" -> {
                            if (stepSize == null) { // не затираем LOT_SIZE
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

            // =====================================================
            // 5) Тикер (может отсутствовать)
            // =====================================================
            JSONObject t = tickerMap.get(symbol);

            BigDecimal lastPrice =
                    (t != null) ? bdOrNull(t.optString("lastPrice", null)) : null;

            BigDecimal priceChangePct =
                    (t != null) ? bdOrNull(t.optString("priceChangePercent", null)) : null;

            BigDecimal volume =
                    (t != null) ? bdOrNull(t.optString("quoteVolume", null)) : null;

            // =====================================================
            // 6) Итоговый SymbolDescriptor
            // =====================================================
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

    /**
     * Надёжная проверка SPOT.
     * Binance отдаёт либо isSpotTradingAllowed, либо permissions=["SPOT",...]
     */
    private boolean isSpotAllowed(JSONObject symbolJson) {

        // 1) permissions
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

        // 2) флаг spotAllowed (если нет — считаем true, но permissions уже отфильтровали)
        // Важно: не ставим default=true бездумно, но пусть будет true для старых ответов.
        boolean spotAllowed = symbolJson.optBoolean("isSpotTradingAllowed", true);
        return spotAllowed;
    }

    /**
     * 1 повтор при сетевой проблеме/таймауте.
     */
    private String getForStringWithRetry(String url, String label) {
        try {
            return rest.getForObject(url, String.class);
        } catch (Exception e1) {
            // один повтор
            try {
                log.warn("⚠️ BINANCE {} failed, retrying once… err={}", label, e1.toString());
                return rest.getForObject(url, String.class);
            } catch (Exception e2) {
                throw e2;
            }
        }
    }

    /**
     * Безопасный BigDecimal парсер.
     */
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

}

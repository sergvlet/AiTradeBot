package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
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
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BybitExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.bybit.com";
    private static final String DEMO = "https://api-demo.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final RestTemplate rest;
    private final ExchangeSettingsService settingsService;

    public BybitExchangeClient(
            ExchangeSettingsService settingsService,
            @Qualifier("marketRestTemplate") RestTemplate rest
    ) {
        this.settingsService = settingsService;
        this.rest = rest;
        log.info("⚠️ BYBIT: TESTNET работает как DEMO (api-demo.bybit.com)");
    }

    // =================================================================
    // META
    // =================================================================

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    private String baseUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? DEMO : MAIN;
    }

    private ExchangeSettings resolve(long chatId, NetworkType net) {
        return settingsService.getOrCreate(chatId, "BYBIT", net);
    }

    // =================================================================
    // MARKET DATA
    // =================================================================

    @Override
    public double getPrice(String symbol) {
        try {
            String url = MAIN + "/spot/v3/public/quote/ticker/price?symbol=" + symbol.toUpperCase();
            JSONObject json = new JSONObject(rest.getForObject(url, String.class));
            return json.getJSONObject("result").optDouble("price", 0);
        } catch (Exception e) {
            log.error("❌ Bybit getPrice: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) {

        try {
            String url = MAIN +
                         "/v5/market/kline" +
                         "?category=spot" +
                         "&symbol=" + symbol.toUpperCase() +
                         "&interval=" + mapIntervalV5(interval) +
                         "&limit=" + limit;

            JSONObject root = new JSONObject(rest.getForObject(url, String.class));

            if (root.optInt("retCode", -1) != 0) {
                log.warn("⚠️ BYBIT KLINES retCode={} msg={}",
                        root.optInt("retCode"),
                        root.optString("retMsg"));
                return List.of();
            }

            JSONArray list = root
                    .getJSONObject("result")
                    .optJSONArray("list");

            if (list == null || list.isEmpty()) {
                return List.of();
            }

            List<Kline> out = new ArrayList<>();

            for (int i = 0; i < list.length(); i++) {
                JSONArray k = list.getJSONArray(i);

                // v5 format:
                // 0 ts, 1 open, 2 high, 3 low, 4 close, 5 volume, 6 turnover
                out.add(new Kline(
                        k.getLong(0),
                        k.getDouble(1),
                        k.getDouble(2),
                        k.getDouble(3),
                        k.getDouble(4),
                        k.getDouble(5)
                ));
            }

            return out;

        } catch (Exception e) {
            log.error("❌ Bybit getKlines(v5) failed", e);
            return List.of();
        }
    }

    /**
     * Bybit v5 interval mapper
     */
    private String mapIntervalV5(String tf) {
        if (tf == null) return "1";

        return switch (tf) {
            case "1m" -> "1";
            case "3m" -> "3";
            case "5m" -> "5";
            case "15m" -> "15";
            case "30m" -> "30";
            case "1h" -> "60";
            case "4h" -> "240";
            case "1d" -> "D";
            default -> "1";
        };
    }

    // =================================================================
    // ORDERS
    // =================================================================

    @Override
    public OrderResult placeOrder(
            Long chatId,
            String symbol,
            String side,
            String type,
            double qty,
            Double price
    ) {

        // ✅ фикс: учитываем сеть, и не берём "первый попавшийся" BYBIT
        ExchangeSettings s = resolve(chatId, guessNetworkOrMain(chatId));

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol.toUpperCase());
        p.put("side", side);
        p.put("orderType", type);
        p.put("qty", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            p.put("price", strip(price));
            p.put("timeInForce", "GTC");
        }

        JSONObject root = new JSONObject(
                signed(s, "/spot/v3/private/order", p, HttpMethod.POST)
        );

        // ✅ фикс: обработка ret_code/ret_msg
        if (root.optInt("ret_code", 0) != 0) {
            throw new RuntimeException("BYBIT order error: " + root.optString("ret_msg"));
        }

        JSONObject r = root.optJSONObject("result");

        return new OrderResult(
                r != null ? r.optString("orderId") : null,
                symbol,
                side,
                type,
                qty,
                price != null ? price : 0,
                r != null ? r.optString("orderStatus", "NEW") : "NEW",
                System.currentTimeMillis()
        );
    }

    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) {

        OrderResult r = placeOrder(
                0L,
                symbol,
                side.name(),
                "MARKET",
                qty.doubleValue(),
                null
        );

        return Order.builder()
                .orderId(r.orderId())
                .symbol(r.symbol())
                .side(r.side())
                .type(r.type())
                .price(BigDecimal.valueOf(r.price()))
                .status(r.status())
                .filled(true)
                .build();
    }

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) {

        ExchangeSettings s = resolve(chatId, guessNetworkOrMain(chatId));

        String raw = signed(
                s,
                "/spot/v3/private/cancel-order",
                Map.of("symbol", symbol.toUpperCase(), "orderId", orderId),
                HttpMethod.POST
        );

        JSONObject root = new JSONObject(raw);
        if (root.optInt("ret_code", 0) != 0) {
            log.warn("⚠️ BYBIT cancel error: {}", root.optString("ret_msg"));
            return false;
        }

        return raw != null && raw.contains("orderId");
    }

    // =================================================================
    // BALANCE
    // =================================================================

    @Override
    public Balance getBalance(Long chatId, String asset, NetworkType network) {
        return getFullBalance(chatId, network)
                .getOrDefault(asset, new Balance(asset, 0, 0));
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId, NetworkType network) {

        try {
            ExchangeSettings s = resolve(chatId, network);

            String raw = signed(
                    s,
                    "/v5/account/wallet-balance",
                    Map.of("accountType", "UNIFIED"),
                    HttpMethod.GET
            );

            JSONObject root = new JSONObject(raw);

            if (root.optInt("retCode", -1) != 0) {
                log.warn("⚠️ BYBIT BALANCE retCode={} msg={}",
                        root.optInt("retCode"),
                        root.optString("retMsg"));
                return Map.of();
            }

            JSONObject result = root.optJSONObject("result");
            if (result == null) {
                log.warn("⚠️ BYBIT BALANCE: result is null");
                return Map.of();
            }

            JSONArray list = result.optJSONArray("list");
            if (list == null || list.isEmpty()) {
                log.warn("⚠️ BYBIT BALANCE: list empty");
                return Map.of();
            }

            JSONArray coins = list.getJSONObject(0).optJSONArray("coin");
            if (coins == null) {
                log.warn("⚠️ BYBIT BALANCE: coin array missing");
                return Map.of();
            }

            Map<String, Balance> out = new HashMap<>();

            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.getJSONObject(i);

                String a = c.optString("coin");
                if (a.isBlank()) continue;

                BigDecimal wallet = safeDecimal(c.opt("walletBalance"));
                BigDecimal withdraw = safeDecimal(c.opt("availableToWithdraw"));

                BigDecimal free = withdraw.compareTo(BigDecimal.ZERO) > 0 ? withdraw : wallet;
                BigDecimal locked = wallet.subtract(free).max(BigDecimal.ZERO);

                if (wallet.compareTo(BigDecimal.ZERO) > 0) {
                    out.put(a, new Balance(a, free.doubleValue(), locked.doubleValue()));
                }
            }

            return out;

        } catch (Exception e) {
            log.error("❌ Bybit getFullBalance FAILED", e);
            return Map.of();
        }
    }

    // =================================================================
    // SYMBOLS
    // =================================================================

    @Override
    public List<String> getAllSymbols() {
        try {
            JSONArray arr = new JSONObject(
                    rest.getForObject(MAIN + "/spot/v3/public/symbols", String.class)
            ).getJSONObject("result").getJSONArray("list");

            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("Trading".equalsIgnoreCase(o.optString("status"))) {
                    out.add(o.getString("name").toUpperCase());
                }
            }
            return out;

        } catch (Exception e) {
            log.error("❌ Bybit getAllSymbols: {}", e.getMessage());
            return List.of();
        }
    }

    // =================================================================
    // ACCOUNT INFO
    // =================================================================

    @Override
    public AccountInfo getAccountInfo(long chatId, NetworkType network) {
        return AccountInfo.builder()
                .makerFee(0.1)
                .takerFee(0.1)
                .makerFeeWithDiscount(0.1)
                .takerFeeWithDiscount(0.1)
                .vipLevel(0)
                .usingBnbDiscount(false)
                .build();
    }

    // =================================================================
    // SIGN
    // =================================================================

    private String signed(
            ExchangeSettings s,
            String endpoint,
            Map<String, String> params,
            HttpMethod method
    ) {

        try {
            long ts = System.currentTimeMillis();

            // ✅ фикс: Bybit подпись = timestamp + apiKey + recvWindow + queryString
            // ВАЖНО: queryString должен быть именно тем, что пойдёт в URL/body (encoded)
            String query = toQuery(params);
            String preSign = ts + s.getApiKey() + RECV_WINDOW + query;

            HttpHeaders h = new HttpHeaders();
            h.set("X-BAPI-API-KEY", s.getApiKey());
            h.set("X-BAPI-SIGN", sign(preSign, s.getApiSecret()));
            h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            h.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);

            // ✅ фикс: для POST Bybit часто ждёт x-www-form-urlencoded
            if (method == HttpMethod.POST) {
                h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            }

            String url = baseUrl(s.getNetwork()) + endpoint +
                         (method == HttpMethod.GET && !query.isEmpty() ? "?" + query : "");

            HttpEntity<String> entity = new HttpEntity<>(method == HttpMethod.POST ? query : null, h);

            return rest.exchange(url, method, entity, String.class).getBody();

        } catch (Exception e) {
            throw new RuntimeException("Bybit signed request error", e);
        }
    }

    private String toQuery(Map<String, String> p) {
        if (p == null || p.isEmpty()) return "";
        return p.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    private String strip(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private BigDecimal safeDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;

        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    // =================================================================
    // FEES
    // =================================================================

    @Override
    public AccountFees getAccountFees(long chatId, NetworkType networkType) {

        try {
            ExchangeSettings s = resolve(chatId, networkType);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("category", "spot");

            String raw = signed(
                    s,
                    "/v5/account/fee-rate",
                    params,
                    HttpMethod.GET
            );

            JSONObject json = new JSONObject(raw);

            int retCode = json.optInt("retCode", -1);
            if (retCode != 0) {
                log.warn("⚠️ Bybit getAccountFees retCode={} msg={}",
                        retCode, json.optString("retMsg"));
                return null;
            }

            JSONObject result = json.optJSONObject("result");
            if (result == null) return null;

            JSONArray list = result.optJSONArray("list");
            if (list == null || list.isEmpty()) return null;

            JSONObject fees = list.getJSONObject(0);

            // ⚠️ Bybit даёт долю (0.001 = 0.1%)
            BigDecimal makerRate = parseBd(fees.optString("makerFeeRate", null));
            BigDecimal takerRate = parseBd(fees.optString("takerFeeRate", null));

            BigDecimal makerPct = makerRate != null
                    ? makerRate.multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP)
                    : null;

            BigDecimal takerPct = takerRate != null
                    ? takerRate.multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP)
                    : null;

            return AccountFees.builder()
                    .makerPct(makerPct)
                    .takerPct(takerPct)
                    .build();

        } catch (Exception e) {
            log.warn("⚠️ Bybit getAccountFees failed (chatId={}, network={}): {}",
                    chatId, networkType, e.toString());
            return null;
        }
    }

    private BigDecimal parseBd(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (Exception ignored) {
            return null;
        }
    }

    // =================================================================
    // TRADABLE SYMBOLS (V5)
    // =================================================================

    @Override
    public List<SymbolDescriptor> getTradableSymbols(String quoteAsset) {

        final String baseUrl = MAIN;

        String qa = (quoteAsset == null || quoteAsset.isBlank())
                ? "USDT"
                : quoteAsset.trim().toUpperCase();

        // =====================================================
        // 1) INSTRUMENTS (SPOT, V5) — критично
        // =====================================================
        JSONArray instruments;
        try {
            String instrumentsRaw = getForStringWithRetry(
                    baseUrl + "/v5/market/instruments-info?category=spot",
                    "bybit instruments"
            );

            if (instrumentsRaw == null || instrumentsRaw.isBlank()) return List.of();

            JSONObject instrumentsRoot = new JSONObject(instrumentsRaw);
            int retCode = instrumentsRoot.optInt("retCode", 0);
            if (retCode != 0) {
                log.warn("⚠️ BYBIT instruments retCode={} msg={}",
                        retCode, instrumentsRoot.optString("retMsg"));
                return List.of();
            }

            JSONObject result = instrumentsRoot.optJSONObject("result");
            instruments = result != null ? result.optJSONArray("list") : null;

            if (instruments == null || instruments.isEmpty()) return List.of();

        } catch (Exception e) {
            log.warn("⚠️ BYBIT instruments failed: asset={} err={}", qa, e.toString());
            return List.of();
        }

        // =====================================================
        // 2) TICKERS 24H (V5) — не критично (может падать)
        // =====================================================
        Map<String, JSONObject> tickerMap = Map.of();
        try {
            String tickersRaw = getForStringWithRetry(
                    baseUrl + "/v5/market/tickers?category=spot",
                    "bybit tickers"
            );

            if (tickersRaw != null && !tickersRaw.isBlank()) {
                JSONObject tickersRoot = new JSONObject(tickersRaw);
                int retCode = tickersRoot.optInt("retCode", 0);
                if (retCode == 0) {
                    JSONObject result = tickersRoot.optJSONObject("result");
                    JSONArray tickers = result != null ? result.optJSONArray("list") : null;

                    if (tickers != null && !tickers.isEmpty()) {
                        Map<String, JSONObject> tmp = new HashMap<>();
                        for (int i = 0; i < tickers.length(); i++) {
                            JSONObject t = tickers.optJSONObject(i);
                            if (t == null) continue;
                            String sym = t.optString("symbol", null);
                            if (sym != null && !sym.isBlank()) tmp.put(sym, t);
                        }
                        tickerMap = tmp;
                    }
                } else {
                    log.warn("⚠️ BYBIT tickers retCode={} msg={}",
                            retCode, tickersRoot.optString("retMsg"));
                }
            }
        } catch (Exception e) {
            // ⚠️ не валим метод — просто работаем без тикеров
            log.warn("⚠️ BYBIT tickers failed: asset={} err={}", qa, e.toString());
            tickerMap = Map.of();
        }

        List<SymbolDescriptor> out = new ArrayList<>(Math.min(instruments.length(), 2000));

        // =====================================================
        // 3) MAIN LOOP
        // =====================================================
        for (int i = 0; i < instruments.length(); i++) {

            JSONObject s = instruments.optJSONObject(i);
            if (s == null) continue;

            String symbol = s.optString("symbol", null);
            if (symbol == null || symbol.isBlank()) continue;

            String base = s.optString("baseCoin", null);
            String quote = s.optString("quoteCoin", null);
            String status = s.optString("status", "");

            // статус: Trading
            if (!"Trading".equalsIgnoreCase(status)) continue;

            // фильтрация по quote
            if (quote == null || !qa.equalsIgnoreCase(quote)) continue;

            // =================================================
            // LIMITS (Bybit V5)
            // =================================================
            JSONObject lotSize = s.optJSONObject("lotSizeFilter");
            JSONObject price = s.optJSONObject("priceFilter");

            // Bybit: minOrderAmt (минимальная сумма в quote), qtyStep (шаг количества)
            BigDecimal minNotional = bdOrNull(lotSize != null ? lotSize.optString("minOrderAmt", null) : null);
            BigDecimal stepSize    = bdOrNull(lotSize != null ? lotSize.optString("qtyStep", null) : null);
            BigDecimal tickSize    = bdOrNull(price != null ? price.optString("tickSize", null) : null);

            Integer maxOrders = null; // spot Bybit обычно не отдаёт лимит

            // =================================================
            // TICKER (может отсутствовать)
            // =================================================
            JSONObject t = tickerMap.get(symbol);

            BigDecimal lastPrice = (t != null) ? bdOrNull(t.optString("lastPrice", null)) : null;

            BigDecimal priceChangePct = null;
            if (t != null) {
                // price24hPcnt: "0.0123" => 1.23%
                BigDecimal frac = bdOrNull(t.optString("price24hPcnt", null));
                if (frac != null) {
                    priceChangePct = frac.multiply(BigDecimal.valueOf(100));
                }
            }

            // turnover24h — оборот в quote (USDT)
            BigDecimal volume = (t != null) ? bdOrNull(t.optString("turnover24h", null)) : null;

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
                    "BYBIT"
            ));
        }

        log.info("✅ BYBIT symbols loaded: asset={} total={}", qa, out.size());
        return out;
    }

    /**
     * 1 повтор при сетевой проблеме/таймауте.
     */
    private String getForStringWithRetry(String url, String label) {
        try {
            return rest.getForObject(url, String.class);
        } catch (Exception e1) {
            try {
                log.warn("⚠️ {} failed, retrying once… err={}", label, e1.toString());
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

    // =================================================================
    // small helpers
    // =================================================================

    /**
     * Мини-хак: в твоём коде placeOrder/cancelOrder не принимают NetworkType,
     * поэтому хотя бы не берём "рандомные" настройки.
     * Если у тебя есть активная сеть в StrategySettingsContext — лучше передавать её в методы.
     */
    private NetworkType guessNetworkOrMain(Long chatId) {
        try {
            // если в БД есть BYBIT TESTNET — можно выбрать её, иначе MAINNET
            return settingsService.findAllByChatId(chatId).stream()
                    .filter(es -> "BYBIT".equalsIgnoreCase(es.getExchange()))
                    .map(ExchangeSettings::getNetwork)
                    .findFirst()
                    .orElse(NetworkType.MAINNET);
        } catch (Exception ignored) {
            return NetworkType.MAINNET;
        }
    }
}

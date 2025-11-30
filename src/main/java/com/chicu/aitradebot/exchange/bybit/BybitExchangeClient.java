package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Kline;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bybit Spot (MAINNET + TESTNET)
 * Полностью совместим с ExchangeClient V4
 */
@Slf4j

public class BybitExchangeClient implements ExchangeClient {

    private static final String MAINNET = "https://api.bybit.com";
    private static final String TESTNET = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ExchangeSettingsService settingsService;
    private final boolean testnet;

    public BybitExchangeClient(ExchangeSettingsService settingsService) {
        this(false, settingsService);
    }

    public BybitExchangeClient(boolean testnet, ExchangeSettingsService settingsService) {
        this.testnet = testnet;
        this.settingsService = settingsService;
        log.info("✅ BybitExchangeClient [{}] инициализирован", testnet ? "TESTNET" : "MAINNET");
    }

    @Override
    public String getExchangeName() { return "BYBIT"; }

    @Override
    public NetworkType getNetworkType() {
        return testnet ? NetworkType.TESTNET : NetworkType.MAINNET;
    }

    private String baseUrl() {
        return testnet ? TESTNET : MAINNET;
    }

    // =============================================================
    // MARKET DATA
    // =============================================================
    @Override
    public double getPrice(String symbol) throws Exception {
        String url = baseUrl() + "/spot/v3/public/quote/ticker/price?symbol=" + symbol.toUpperCase();

        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(resp.getBody());
        JSONObject result = json.optJSONObject("result");

        return result != null ? result.optDouble("price", 0.0) : 0.0;
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {
        String iv = mapInterval(interval);

        String url = baseUrl() +
                "/spot/v3/public/quote/kline?symbol=" + symbol.toUpperCase()
                + "&interval=" + iv
                + "&limit=" + limit;

        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(resp.getBody());
        JSONObject result = json.optJSONObject("result");

        if (result == null) return List.of();

        JSONArray arr = result.optJSONArray("list");
        if (arr == null) return List.of();

        List<Kline> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONArray k = arr.getJSONArray(i);

            out.add(new Kline(
                    Long.parseLong(k.getString(0)), // openTime
                    Double.parseDouble(k.getString(1)),
                    Double.parseDouble(k.getString(2)),
                    Double.parseDouble(k.getString(3)),
                    Double.parseDouble(k.getString(4)),
                    Double.parseDouble(k.getString(5))
            ));
        }
        return out;
    }

    private String mapInterval(String tf) {
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
            default -> tf;
        };
    }

    // =============================================================
    // ORDERS
    // =============================================================

    @Override
    public OrderResult placeOrder(Long chatId,
                                  String symbol,
                                  String side,
                                  String type,
                                  double qty,
                                  Double price) throws Exception {

        ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", side);
        params.put("orderType", type);
        params.put("qty", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            params.put("price", strip(price));
            params.put("timeInForce", "GTC");
        }

        String result = signedRequest(s, "/spot/v3/private/order", params, HttpMethod.POST);

        JSONObject json = new JSONObject(result);
        JSONObject r = json.optJSONObject("result");

        return new OrderResult(
                r != null ? r.optString("orderId") : null,
                symbol,
                side,
                type,
                qty,
                price != null ? price : 0.0,
                r != null ? r.optString("orderStatus", "NEW") : "NEW",
                System.currentTimeMillis()
        );
    }

    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) throws Exception {

        OrderResult r = placeOrder(
                0L,
                symbol,
                side.name(),
                "MARKET",
                qty.doubleValue(),
                null
        );

        Order o = new Order();
        o.setOrderId(r.orderId());
        o.setSymbol(r.symbol());
        o.setSide(r.side());
        o.setType(r.type());
        o.setQty(BigDecimal.valueOf(r.qty()));
        o.setPrice(BigDecimal.valueOf(r.price()));
        o.setStatus(r.status());
        o.setTimestamp(r.timestamp());
        o.setFilled(true);

        return o;
    }

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) throws Exception {

        ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("orderId", orderId);

        String res = signedRequest(s, "/spot/v3/private/cancel-order", params, HttpMethod.POST);

        return res != null && res.contains("orderId");
    }

    // =============================================================
    // BALANCE
    // =============================================================

    @Override
    public Balance getBalance(Long chatId, String asset) throws Exception {

        ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");

        Map<String, String> params = Map.of("accountType", "UNIFIED");

        String result = signedRequest(s, "/v5/account/wallet-balance", params, HttpMethod.GET);

        JSONObject json = new JSONObject(result);
        JSONObject res = json.optJSONObject("result");

        if (res == null) return new Balance(asset, 0, 0);

        JSONArray list = res.optJSONArray("list");
        if (list == null || list.isEmpty()) return new Balance(asset, 0, 0);

        JSONArray coins = list.getJSONObject(0).optJSONArray("coin");
        if (coins == null) return new Balance(asset, 0, 0);

        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.getJSONObject(i);
            if (!asset.equalsIgnoreCase(c.getString("coin"))) continue;

            double free = c.optDouble("availableToWithdraw", 0.0);
            double total = c.optDouble("walletBalance", 0.0);

            return new Balance(asset, free, Math.max(0, total - free));
        }

        return new Balance(asset, 0, 0);
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId) {
        log.warn("⚠️ getFullBalance() для Bybit пока не реализован");
        return new LinkedHashMap<>();
    }

    // =============================================================
    // SIGNED REQUEST
    // =============================================================

    private String signedRequest(ExchangeSettings s,
                                 String endpoint,
                                 Map<String, String> params,
                                 HttpMethod method) {

        try {
            long ts = System.currentTimeMillis();

            String query = toQuery(params);
            String preSign = ts + s.getApiKey() + RECV_WINDOW + query;

            String signature = sign(preSign, s.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", s.getApiKey());
            headers.set("X-BAPI-SIGN", signature);
            headers.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            headers.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String url = baseUrl() + endpoint + (method == HttpMethod.GET && !query.isEmpty()
                    ? "?" + query
                    : "");

            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    method,
                    new HttpEntity<>(method == HttpMethod.POST ? query : "", headers),
                    String.class
            );

            return resp.getBody();

        } catch (Exception e) {
            log.error("❌ Bybit signedRequest error: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // =============================================================
    // HELPERS
    // =============================================================

    private String strip(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private String toQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подписи BYBIT", e);
        }
    }

    // =============================================================
    // SYMBOLS / TIMEFRAMES
    // =============================================================

    @Override
    public List<String> getAllSymbols() {
        try {
            String url = baseUrl() + "/spot/v3/public/symbols";

            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);

            JSONObject json = new JSONObject(resp.getBody());
            JSONObject result = json.optJSONObject("result");

            JSONArray arr = result != null ? result.optJSONArray("list") : null;
            if (arr == null) return List.of();

            List<String> out = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("Trading".equalsIgnoreCase(o.optString("status")))
                    out.add(o.getString("name").toUpperCase());
            }

            out.sort(String::compareTo);
            return out;

        } catch (Exception e) {
            log.error("❌ Ошибка getAllSymbols Bybit: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<String> getAvailableTimeframes() {
        return List.of("1", "3", "5", "15", "30", "60", "240", "D", "W", "M");
    }
}

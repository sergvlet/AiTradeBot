package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 🌐 Универсальный клиент Bybit Spot (MAINNET + TESTNET)
 * Поддерживает MARKET / LIMIT / Cancel / GetOrders
 * Подпись HMAC-SHA256: timestamp + apiKey + recvWindow + queryString
 */
@Slf4j

public class BybitExchangeClient implements ExchangeClient {

    private static final String MAINNET = "https://api.bybit.com";
    private static final String TESTNET = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ExchangeSettingsService settingsService;
    private final boolean testnet;

    /** ✅ Конструктор MAINNET */
    public BybitExchangeClient(ExchangeSettingsService settingsService) {
        this(false, settingsService);
    }

    /** ✅ Конструктор с выбором сети */
    public BybitExchangeClient(boolean testnet, ExchangeSettingsService settingsService) {
        this.testnet = testnet;
        this.settingsService = settingsService;
        log.info("✅ BybitExchangeClient инициализирован [{}]", testnet ? "TESTNET" : "MAINNET");
    }

    private String baseUrl(ExchangeSettings s) {
        // Если в ExchangeSettings явно указана сеть — приоритет за ней
        if (s != null && s.getNetwork() == NetworkType.TESTNET) return TESTNET;
        return testnet ? TESTNET : MAINNET;
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public NetworkType getNetworkType() {
        return testnet ? NetworkType.TESTNET : NetworkType.MAINNET;
    }

    // ===================== ТОРГОВЫЕ МЕТОДЫ =====================

    @Override
    public double getPrice(String symbol) throws Exception {
        String url = baseUrl(null) + "/spot/v3/public/quote/ticker/price?symbol=" + symbol.toUpperCase();
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(response.getBody());
        JSONObject result = json.optJSONObject("result");
        return result == null ? 0.0 : result.optDouble("price", 0.0);
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {
        String bybitInterval = mapInterval(interval);
        String url = baseUrl(null) + "/spot/v3/public/quote/kline?symbol=" + symbol.toUpperCase() +
                     "&interval=" + bybitInterval + "&limit=" + limit;

        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(resp.getBody());
        JSONObject result = json.optJSONObject("result");
        if (result == null) return Collections.emptyList();

        JSONArray list = result.optJSONArray("list");
        if (list == null) return Collections.emptyList();

        List<Kline> out = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONArray k = list.getJSONArray(i);
            out.add(new Kline(
                    Long.parseLong(k.getString(0)),
                    Double.parseDouble(k.getString(1)),
                    Double.parseDouble(k.getString(2)),
                    Double.parseDouble(k.getString(3)),
                    Double.parseDouble(k.getString(4)),
                    Double.parseDouble(k.getString(5))
            ));
        }
        return out;
    }

    private String mapInterval(String interval) {
        if (interval == null) return "1";
        switch (interval.toLowerCase()) {
            case "1m": return "1";
            case "3m": return "3";
            case "5m": return "5";
            case "15m": return "15";
            case "30m": return "30";
            case "1h": return "60";
            case "4h": return "240";
            case "1d": return "D";
            default: return interval;
        }
    }

    // ===================== ОРДЕРА =====================

    @Override
    public OrderResult placeOrder(Long chatId, String symbol, String side, String type, double qty, Double price) throws Exception {
        ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", side.toUpperCase());
        params.put("orderType", type.toUpperCase());
        params.put("qty", String.valueOf(qty));
        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            params.put("price", String.valueOf(price));
            params.put("timeInForce", "GTC");
        }

        String res = signedPost(s, "/spot/v3/private/order", params);
        JSONObject json = new JSONObject(res);
        JSONObject result = json.optJSONObject("result");

        String orderId = result != null ? result.optString("orderId", null) : null;
        String status = result != null ? result.optString("orderStatus", "NEW") : "NEW";

        return new OrderResult(orderId, symbol, side, type, qty, price == null ? 0.0 : price, status, System.currentTimeMillis());
    }

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) throws Exception {
        ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");
        Map<String, String> params = Map.of("symbol", symbol.toUpperCase(), "orderId", orderId);
        String res = signedPost(s, "/spot/v3/private/cancel-order", new LinkedHashMap<>(params));
        return res != null && res.contains("orderId");
    }

    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) throws Exception {
        OrderResult res = placeOrder(0L, symbol, side.name(), "MARKET", qty.doubleValue(), null);
        Order order = new Order();
        order.setOrderId(res.orderId());
        order.setSymbol(res.symbol());
        order.setSide(res.side());
        order.setType(res.type());
        order.setQty(BigDecimal.valueOf(res.qty()));
        order.setPrice(BigDecimal.valueOf(res.price()));
        order.setStatus(res.status());
        order.setTimestamp(res.timestamp());
        order.setFilled(true);
        return order;
    }

    // ===================== БАЛАНС =====================

    @Override
    public Balance getBalance(Long chatId, String asset) {
        try {
            ExchangeSettings s = settingsService.getOrThrow(chatId, "BYBIT");

            Map<String, String> params = Map.of("accountType", "UNIFIED");
            String query = toQueryString(params);
            long ts = System.currentTimeMillis();
            String preSign = ts + s.getApiKey() + RECV_WINDOW + query;
            String signature = sign(preSign, s.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", s.getApiKey());
            headers.set("X-BAPI-SIGN", signature);
            headers.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            headers.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);

            String url = baseUrl(s) + "/v5/account/wallet-balance" + (query.isEmpty() ? "" : "?" + query);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>("", headers), String.class);

            JSONObject json = new JSONObject(resp.getBody());
            JSONObject result = json.optJSONObject("result");
            JSONArray list = result != null ? result.optJSONArray("list") : null;
            if (list == null || list.isEmpty()) return new Balance(asset, 0.0, 0.0);

            JSONArray coins = list.getJSONObject(0).optJSONArray("coin");
            if (coins == null) return new Balance(asset, 0.0, 0.0);

            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.getJSONObject(i);
                if (asset.equalsIgnoreCase(c.getString("coin"))) {
                    double free = c.optDouble("availableToWithdraw", 0.0);
                    double total = c.optDouble("walletBalance", 0.0);
                    return new Balance(asset, free, Math.max(0.0, total - free));
                }
            }
            return new Balance(asset, 0.0, 0.0);
        } catch (Exception e) {
            log.error("❌ Ошибка Bybit getBalance: {}", e.getMessage());
            return new Balance(asset, 0.0, 0.0);
        }
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId) {
        log.warn("⚠️ getFullBalance() пока не реализован для Bybit (chatId={})", chatId);
        return new LinkedHashMap<>();
    }

    // ===================== ВСПОМОГАТЕЛЬНЫЕ =====================

    private String signedPost(ExchangeSettings s, String endpoint, Map<String, String> params) {
        return signedRequest(s, endpoint, params, HttpMethod.POST);
    }

    private String signedRequest(ExchangeSettings s, String endpoint, Map<String, String> params, HttpMethod method) {
        try {
            long timestamp = System.currentTimeMillis();
            String query = toQueryString(params);
            String preSign = timestamp + s.getApiKey() + RECV_WINDOW + query;
            String signature = sign(preSign, s.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", s.getApiKey());
            headers.set("X-BAPI-SIGN", signature);
            headers.set("X-BAPI-TIMESTAMP", String.valueOf(timestamp));
            headers.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String url = baseUrl(s) + endpoint + (method == HttpMethod.GET && !query.isEmpty() ? "?" + query : "");
            log.info("🔐 [{}] {} {}", testnet ? "TESTNET" : "MAINNET", method.name(), url);
            ResponseEntity<String> response = restTemplate.exchange(url, method, new HttpEntity<>(method == HttpMethod.POST ? query : "", headers), String.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("❌ Ошибка Bybit {}: {}", endpoint, e.getMessage());
            return null;
        }
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подписи Bybit: " + e.getMessage());
        }
    }

    private String toQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String v) {
        try {
            return URLEncoder.encode(v, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return v;
        }
    }
    @Override
    public List<String> getAllSymbols() {
        try {
            String url = baseUrl(null) + "/spot/v3/public/symbols";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ Не удалось получить список символов Bybit ({}): пустой ответ", getNetworkType());
                return Collections.emptyList();
            }

            JSONObject json = new JSONObject(response.getBody());
            JSONObject result = json.optJSONObject("result");
            JSONArray list = result != null ? result.optJSONArray("list") : null;
            if (list == null) {
                log.warn("⚠️ Пустой список символов Bybit [{}]", getNetworkType());
                return Collections.emptyList();
            }

            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                // Берём только активные пары
                if ("Trading".equalsIgnoreCase(s.optString("status"))) {
                    symbols.add(s.getString("name").toUpperCase());
                }
            }

            symbols.sort(String::compareTo);
            log.info("📊 Bybit: загружено {} символов [{}]", symbols.size(), getNetworkType());
            return symbols;

        } catch (Exception e) {
            log.error("❌ Ошибка получения списка символов Bybit [{}]: {}", getNetworkType(), e.getMessage());
            return Collections.emptyList();
        }
    }
    @Override
    public List<String> getAvailableTimeframes() {
        try {
            // Эндпоинт Bybit, который возвращает список интервалов
            String url = bybitPublicBaseUrl() + "/v5/market/kline/intervals";

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ Bybit: пустой или неуспешный ответ по таймфреймам [{}]", getNetworkType());
                return List.of();
            }

            JSONObject json = new JSONObject(response.getBody());
            // retCode == 0 → OK
            if (!"0".equals(String.valueOf(json.opt("retCode")))) {
                log.warn("⚠️ Bybit API error: {} [{}]", json.optString("retMsg"), getNetworkType());
                return List.of();
            }

            JSONArray arr = json.optJSONArray("result");
            if (arr == null || arr.isEmpty()) {
                log.warn("⚠️ Bybit: массив таймфреймов пуст [{}]", getNetworkType());
                return List.of();
            }

            List<String> intervals = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                intervals.add(arr.getString(i));
            }

            log.info("⏱ Bybit: загружено {} таймфреймов [{}]", intervals.size(), getNetworkType());
            return intervals;
        } catch (Exception e) {
            log.error("❌ Ошибка получения таймфреймов Bybit [{}]: {}", getNetworkType(), e.getMessage(), e);
            // Надёжный fallback под Bybit (минуты: числа, часы: кратные 60, дни/недели/месяцы — буквенные)
            return List.of(
                    "1", "3", "5", "15", "30",
                    "60", "120", "240", "360", "720",
                    "D", "W", "M"
            );
        }
    }
    /** Базовый публичный URL Bybit для выбранной сети. */
    private String bybitPublicBaseUrl() {
        // если в классе уже есть геттер сети — используем его
        NetworkType nt = getNetworkType(); // либо this.networkType, если поле публично в классе
        return (nt == NetworkType.MAINNET)
                ? "https://api.bybit.com"
                : "https://api-testnet.bybit.com";
    }


}

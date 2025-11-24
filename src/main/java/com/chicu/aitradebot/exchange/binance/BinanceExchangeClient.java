package com.chicu.aitradebot.exchange.binance;

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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Унифицированный клиент Binance
 * Полностью совместим c ExchangeClient
 */
@Slf4j
public class BinanceExchangeClient implements ExchangeClient {

    private static final String MAINNET = "https://api.binance.com";
    private static final String TESTNET = "https://testnet.binance.vision";

    private final boolean testnet;
    private final RestTemplate restTemplate;
    private final ExchangeSettingsService settingsService;

    private List<String> cachedSymbols = new ArrayList<>();
    private long lastSymbolsFetch = 0L;

    public BinanceExchangeClient(boolean testnet, ExchangeSettingsService settingsService) {
        this.testnet = testnet;
        this.settingsService = settingsService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public NetworkType getNetworkType() {
        return testnet ? NetworkType.TESTNET : NetworkType.MAINNET;
    }

    private String baseUrl() {
        return testnet ? TESTNET : MAINNET;
    }

    // ======================================================
    //  MARKET DATA
    // ======================================================

    @Override
    public double getPrice(String symbol) throws Exception {
        String url = baseUrl() + "/api/v3/ticker/price?symbol=" + symbol.toUpperCase();
        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(resp.getBody());
        return json.getDouble("price");
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) throws Exception {
        String url = baseUrl() + "/api/v3/klines?symbol=" + symbol.toUpperCase()
                     + "&interval=" + interval
                     + "&limit=" + limit;

        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        JSONArray arr = new JSONArray(resp.getBody());

        List<Kline> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray c = arr.getJSONArray(i);
            list.add(new Kline(
                    c.getLong(0),  // open time
                    c.getDouble(1),
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getDouble(4),
                    c.getDouble(5)
            ));
        }
        return list;
    }

    // ======================================================
    //  PLACE ORDER
    // ======================================================

    @Override
    public OrderResult placeOrder(Long chatId, String symbol, String side, String type,
                                  double qty, Double price) throws Exception {

        ExchangeSettings s = resolveSettings(chatId);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", side.toUpperCase());
        params.put("type", type.toUpperCase());
        params.put("quantity", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            params.put("price", strip(price));
            params.put("timeInForce", "GTC");
        }

        String result = executeSigned(s, "/api/v3/order", params, HttpMethod.POST);

        JSONObject json = new JSONObject(result);

        return new OrderResult(
                json.optString("orderId"),
                symbol,
                side,
                type,
                qty,
                price == null ? 0.0 : price,
                json.optString("status", "NEW"),
                System.currentTimeMillis()
        );
    }

    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) throws Exception {

        OrderResult res = placeOrder(
                0L,
                symbol,
                side.name(),
                "MARKET",
                qty.doubleValue(),
                null
        );

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

    // ======================================================
    //  CANCEL
    // ======================================================

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) throws Exception {
        ExchangeSettings s = resolveSettings(chatId);

        Map<String, String> params = Map.of(
                "symbol", symbol.toUpperCase(),
                "orderId", orderId
        );

        String r = executeSigned(s, "/api/v3/order", params, HttpMethod.DELETE);
        return r != null && r.contains("orderId");
    }

    // ======================================================
    //  BALANCES
    // ======================================================

    @Override
    public Balance getBalance(Long chatId, String asset) throws Exception {
        Map<String, Balance> all = getFullBalance(chatId);
        return all.getOrDefault(asset, new Balance(asset, 0, 0));
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId) throws Exception {
        ExchangeSettings s = resolveSettings(chatId);

        String resp = executeSigned(s, "/api/v3/account", new HashMap<>(), HttpMethod.GET);

        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.getJSONArray("balances");

        Map<String, Balance> map = new LinkedHashMap<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            String asset = o.getString("asset");
            double free = o.optDouble("free", 0);
            double locked = o.optDouble("locked", 0);

            if (free + locked > 0)
                map.put(asset, new Balance(asset, free, locked));
        }

        return map;
    }

    // ======================================================
    //  PRIVATE SIGNED REQUEST
    // ======================================================

    private String executeSigned(ExchangeSettings s,
                                 String endpoint,
                                 Map<String, String> params,
                                 HttpMethod method) {

        try {
            long ts = System.currentTimeMillis();

            params.put("recvWindow", "5000");
            params.put("timestamp", String.valueOf(ts));

            String query = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            String signature = sign(query, s.getApiSecret());

            String url = baseUrl() + endpoint + "?" + query + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", s.getApiKey());

            ResponseEntity<String> r = restTemplate.exchange(
                    url,
                    method,
                    new HttpEntity<>("", headers),
                    String.class
            );

            return r.getBody();

        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Binance HTTP error: " + e.getResponseBodyAsString(), e);
        }
    }

    // ======================================================
    //  SIGNATURE
    // ======================================================

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes)
                sb.append(String.format("%02x", b));

            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка подписи Binance", e);
        }
    }

    // ======================================================
    //  HELPERS
    // ======================================================

    private ExchangeSettings resolveSettings(Long chatId) {
        NetworkType need = getNetworkType();

        return settingsService.findAllByChatId(chatId)
                .stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(es -> es.getExchange().equalsIgnoreCase("BINANCE"))
                .filter(es -> es.getNetwork() == need)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Нет ключей Binance для chatId=" + chatId));
    }

    private static String strip(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ======================================================
    //  SYMBOL LIST
    // ======================================================

    @Override
    public List<String> getAllSymbols() {

        long now = System.currentTimeMillis();

        if (!cachedSymbols.isEmpty() && now - lastSymbolsFetch < 3600_000)
            return cachedSymbols;

        try {
            String url = baseUrl() + "/api/v3/exchangeInfo";
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);

            JSONObject json = new JSONObject(resp.getBody());
            JSONArray arr = json.getJSONArray("symbols");

            List<String> list = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if ("TRADING".equalsIgnoreCase(s.optString("status")))
                    list.add(s.getString("symbol"));
            }

            list.sort(String::compareTo);
            cachedSymbols = list;
            lastSymbolsFetch = now;

            return list;

        } catch (Exception e) {
            log.error("Ошибка getAllSymbols: {}", e.getMessage());
            return cachedSymbols;
        }
    }
}

package com.chicu.aitradebot.exchange.binance;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.AccountInfo;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;
import com.chicu.aitradebot.exchange.model.Order;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
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

    public BinanceExchangeClient(ExchangeSettingsService settingsService) {
        this.settingsService = settingsService;

        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setReadTimeout(7000);
        f.setConnectTimeout(7000);

        this.rest = new RestTemplate(f);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public NetworkType getNetworkType() {
        throw new UnsupportedOperationException("Use resolve(chatId)");
    }

    private String baseUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? TEST : MAIN;
    }

    private ExchangeSettings resolve(Long chatId) {
        return settingsService.findAllByChatId(chatId)
                .stream()
                .filter(ExchangeSettings::isEnabled)
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
        OrderResult r = placeOrder(0L, symbol, side.name(), "MARKET", qty.doubleValue(), null);

        return new Order(
                null,
                "BINANCE",
                0L,
                r.symbol(),
                r.side(),
                r.type(),
                BigDecimal.valueOf(r.qty()),
                BigDecimal.valueOf(r.price()),
                BigDecimal.valueOf(r.qty()),
                r.status(),
                r.timestamp(),
                r.timestamp(),
                true,
                StrategyType.SMART_FUSION
        );
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
}

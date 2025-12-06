package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.exchange.model.AccountInfo;
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
 * Bybit Spot (MAINNET + TESTNET)
 * Полностью совместим с ExchangeClient V4
 */
@Slf4j
@Component // <<< главное изменение — теперь это Spring-бин
public class BybitExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.bybit.com";
    private static final String TEST = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final RestTemplate rest = new RestTemplate();
    private final ExchangeSettingsService settingsService;

    public BybitExchangeClient(ExchangeSettingsService settingsService) {
        this.settingsService = settingsService;
        log.info("✅ BybitExchangeClient инициализирован");
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public NetworkType getNetworkType() {
        return NetworkType.MAINNET;
    }

    private String baseUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? TEST : MAIN;
    }

    private ExchangeSettings resolve(long chatId, NetworkType net) {
        return settingsService.getOrCreate(chatId, "BYBIT", net);
    }

    // =============================================================
    // MARKET DATA
    // =============================================================
    @Override
    public double getPrice(String symbol) {
        try {
            String url = MAIN + "/spot/v3/public/quote/ticker/price?symbol=" + symbol.toUpperCase();
            JSONObject json = new JSONObject(rest.getForObject(url, String.class));
            JSONObject r = json.optJSONObject("result");
            return r != null ? r.optDouble("price", 0.0) : 0.0;
        } catch (Exception e) {
            log.error("❌ getPrice Bybit: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) {
        try {
            String iv = mapInterval(interval);

            String url = MAIN +
                         "/spot/v3/public/quote/kline?symbol=" + symbol +
                         "&interval=" + iv +
                         "&limit=" + limit;

            JSONObject json = new JSONObject(rest.getForObject(url, String.class));
            JSONObject result = json.optJSONObject("result");
            if (result == null) return List.of();

            JSONArray arr = result.optJSONArray("list");
            if (arr == null) return List.of();

            List<Kline> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray k = arr.getJSONArray(i);
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

        } catch (Exception e) {
            log.error("❌ getKlines Bybit: {}", e.getMessage());
            return List.of();
        }
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
    public OrderResult placeOrder(Long chatId, String symbol, String side, String type, double qty, Double price) {

        ExchangeSettings s = resolve(chatId, NetworkType.MAINNET);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol.toUpperCase());
        p.put("side", side);
        p.put("orderType", type);
        p.put("qty", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            p.put("price", strip(price));
            p.put("timeInForce", "GTC");
        }

        String response = signed(s, "/spot/v3/private/order", p, HttpMethod.POST);

        JSONObject json = new JSONObject(response);
        JSONObject r = json.optJSONObject("result");

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
        OrderResult r = placeOrder(0L, symbol, side.name(), "MARKET", qty.doubleValue(), null);

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

        ExchangeSettings s = resolve(chatId, NetworkType.MAINNET);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol.toUpperCase());
        p.put("orderId", orderId);

        String res = signed(s, "/spot/v3/private/cancel-order", p, HttpMethod.POST);

        return res != null && res.contains("orderId");
    }

    // =============================================================
    // BALANCE (НОВАЯ ЛОГИКА)
    // =============================================================
    @Override
    public Balance getBalance(Long chatId, String asset) {
        return getBalance(chatId, asset, NetworkType.MAINNET);
    }

    @Override
    public Balance getBalance(Long chatId, String asset, NetworkType network) {
        try {
            Map<String, Balance> all = getFullBalance(chatId, network);
            return all.getOrDefault(asset, new Balance(asset, 0, 0));
        } catch (Exception e) {
            return new Balance(asset, 0, 0);
        }
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId) {
        return getFullBalance(chatId, NetworkType.MAINNET);
    }

    @Override
    public Map<String, Balance> getFullBalance(Long chatId, NetworkType network) {

        ExchangeSettings s = resolve(chatId, network);

        try {
            Map<String, String> params = Map.of("accountType", "UNIFIED");

            String response = signed(s, "/v5/account/wallet-balance", params, HttpMethod.GET);

            Map<String, Balance> out = new LinkedHashMap<>();

            JSONObject json = new JSONObject(response);
            JSONObject res = json.optJSONObject("result");
            if (res == null) return out;

            JSONArray list = res.optJSONArray("list");
            if (list == null || list.isEmpty()) return out;

            JSONArray coins = list.getJSONObject(0).optJSONArray("coin");
            if (coins == null) return out;

            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.getJSONObject(i);
                String coin = c.optString("coin");

                double free = c.optDouble("availableToWithdraw", 0.0);
                double total = c.optDouble("walletBalance", 0.0);
                double locked = Math.max(0, total - free);

                out.put(coin, new Balance(coin, free, locked));
            }

            return out;

        } catch (Exception e) {
            log.error("❌ getFullBalance Bybit: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // =============================================================
    // SIGNED REQUEST
    // =============================================================
    private String signed(ExchangeSettings s, String endpoint, Map<String, String> params, HttpMethod method) {

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

            String url = baseUrl(s.getNetwork()) + endpoint +
                         (method == HttpMethod.GET && !query.isEmpty() ? "?" + query : "");

            ResponseEntity<String> resp = rest.exchange(
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

    private String strip(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private String toQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
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
    // SYMBOLS
    // =============================================================
    @Override
    public List<String> getAllSymbols() {
        try {
            String url = MAIN + "/spot/v3/public/symbols";

            JSONObject json = new JSONObject(rest.getForObject(url, String.class));
            JSONObject result = json.optJSONObject("result");

            JSONArray arr = result != null ? result.optJSONArray("list") : null;
            if (arr == null) return List.of();

            List<String> out = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                if ("Trading".equalsIgnoreCase(o.optString("status"))) {
                    out.add(o.getString("name").toUpperCase());
                }
            }

            out.sort(String::compareTo);
            return out;

        } catch (Exception e) {
            log.error("❌ getAllSymbols Bybit: {}", e.getMessage());
            return List.of();
        }
    }

    // =============================================================
    // ACCOUNT INFO
    // =============================================================
    @Override
    public AccountInfo getAccountInfo(long chatId, NetworkType net) {
        try {
            ExchangeSettings s = resolve(chatId, net);

            Map<String, String> params = Map.of("accountType", "UNIFIED");
            String response = signed(s, "/v5/account/wallet-balance", params, HttpMethod.GET);

            JSONObject json = new JSONObject(response);
            JSONObject res = json.optJSONObject("result");

            if (res == null) return defaultFees();

            boolean hasBNB = false;

            JSONArray list = res.optJSONArray("list");
            if (list != null && !list.isEmpty()) {
                JSONArray coins = list.getJSONObject(0).optJSONArray("coin");

                if (coins != null) {
                    for (int i = 0; i < coins.length(); i++) {
                        JSONObject c = coins.getJSONObject(i);

                        if ("BNB".equalsIgnoreCase(c.optString("coin"))
                            && c.optDouble("walletBalance", 0) > 0.0001) {
                            hasBNB = true;
                        }
                    }
                }
            }

            int vip = res.optInt("feeTier", 0);

            double maker = bybitMaker(vip);
            double taker = bybitTaker(vip);

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
            log.error("❌ getAccountInfo Bybit: {}", e.getMessage());
            return defaultFees();
        }
    }

    private AccountInfo defaultFees() {
        return AccountInfo.builder()
                .makerFee(0.1)
                .takerFee(0.1)
                .makerFeeWithDiscount(0.1)
                .takerFeeWithDiscount(0.1)
                .vipLevel(0)
                .usingBnbDiscount(false)
                .build();
    }

    private double bybitMaker(int vip) {
        return switch (vip) {
            case 1 -> 0.1;
            case 2 -> 0.08;
            case 3 -> 0.06;
            default -> 0.1;
        };
    }

    private double bybitTaker(int vip) {
        return switch (vip) {
            case 1 -> 0.1;
            case 2 -> 0.1;
            case 3 -> 0.07;
            default -> 0.1;
        };
    }
}

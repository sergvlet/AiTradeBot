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

@Slf4j
@Component
public class BybitExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.bybit.com";
    private static final String DEMO = "https://api-demo.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final RestTemplate rest = new RestTemplate();
    private final ExchangeSettingsService settingsService;

    public BybitExchangeClient(ExchangeSettingsService settingsService) {
        this.settingsService = settingsService;
        log.info("⚠️ BYBIT: TESTNET работает как DEMO (api-demo.bybit.com)");
    }

    // =================================================================
    // META
    // =================================================================

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    /**
     * ❗ КЛИЕНТ НЕ ПРИВЯЗАН К СЕТИ
     * Реальная сеть берётся из ExchangeSettings
     */
    @Override
    public NetworkType getNetworkType() {
        return null;
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
                         "/spot/v3/public/quote/kline?symbol=" + symbol.toUpperCase() +
                         "&interval=" + mapInterval(interval) +
                         "&limit=" + limit;

            JSONArray arr = new JSONObject(rest.getForObject(url, String.class))
                    .getJSONObject("result")
                    .optJSONArray("list");

            if (arr == null) return List.of();

            List<Kline> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray k = arr.getJSONArray(i);
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
            log.error("❌ Bybit getKlines: {}", e.getMessage());
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

        ExchangeSettings s = settingsService
                .findAllByChatId(chatId)
                .stream()
                .filter(es -> "BYBIT".equals(es.getExchange()))
                .findFirst()
                .orElseThrow();

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol.toUpperCase());
        p.put("side", side);
        p.put("orderType", type);
        p.put("qty", strip(qty));

        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            p.put("price", strip(price));
            p.put("timeInForce", "GTC");
        }

        JSONObject r = new JSONObject(
                signed(s, "/spot/v3/private/order", p, HttpMethod.POST)
        ).optJSONObject("result");

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

        ExchangeSettings s = settingsService
                .findAllByChatId(chatId)
                .stream()
                .filter(es -> "BYBIT".equals(es.getExchange()))
                .findFirst()
                .orElseThrow();

        String res = signed(
                s,
                "/spot/v3/private/cancel-order",
                Map.of("symbol", symbol, "orderId", orderId),
                HttpMethod.POST
        );

        return res != null && res.contains("orderId");
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

            // 1️⃣ retCode check
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

                String asset = c.optString("coin");
                if (asset.isBlank()) continue;

                BigDecimal wallet   = safeDecimal(c.opt("walletBalance"));
                BigDecimal withdraw = safeDecimal(c.opt("availableToWithdraw"));

                // ✅ Bybit Spot логика
                BigDecimal free = withdraw.compareTo(BigDecimal.ZERO) > 0
                        ? withdraw
                        : wallet;

                BigDecimal locked = wallet.subtract(free).max(BigDecimal.ZERO);

                if (wallet.compareTo(BigDecimal.ZERO) > 0) {
                    out.put(
                            asset,
                            new Balance(
                                    asset,
                                    free.doubleValue(),
                                    locked.doubleValue()
                            )
                    );
                }
            }

            log.info("💰 BYBIT BALANCE chatId={} {} -> {}", chatId, network, out.keySet());
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
            String query = toQuery(params);
            String preSign = ts + s.getApiKey() + RECV_WINDOW + query;

            HttpHeaders h = new HttpHeaders();
            h.set("X-BAPI-API-KEY", s.getApiKey());
            h.set("X-BAPI-SIGN", sign(preSign, s.getApiSecret()));
            h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
            h.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String url = baseUrl(s.getNetwork()) + endpoint +
                         (method == HttpMethod.GET && !query.isEmpty() ? "?" + query : "");

            return rest.exchange(
                    url,
                    method,
                    new HttpEntity<>(method == HttpMethod.POST ? query : "", h),
                    String.class
            ).getBody();

        } catch (Exception e) {
            throw new RuntimeException("Bybit signed request error", e);
        }
    }

    private String toQuery(Map<String, String> p) {
        return p.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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

        return new BigDecimal(s);
    }

}

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
 * 🌐 BinanceExchangeClient — универсальный клиент для работы с Binance API
 * Поддерживает MAINNET и TESTNET
 */
@Slf4j
public class BinanceExchangeClient implements ExchangeClient {

    private static final String MAINNET = "https://api.binance.com";
    private static final String TESTNET = "https://testnet.binance.vision";

    private final boolean testnet;
    private final RestTemplate restTemplate;
    private final ExchangeSettingsService settingsService;

    // ✅ Добавлен кэш символов, чтобы не грузить каждый раз
    private List<String> cachedSymbols = new ArrayList<>();
    private long lastSymbolsFetch = 0L;

    public BinanceExchangeClient(ExchangeSettingsService settingsService) {
        this(false, settingsService);
    }

    public BinanceExchangeClient(boolean testnet, ExchangeSettingsService settingsService) {
        this.testnet = testnet;
        this.settingsService = settingsService;

        // ⚙️ Устанавливаем таймауты (чтобы не зависало на 18 секунд)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);

        log.info("✅ BinanceExchangeClient инициализирован [{}]", testnet ? "TESTNET" : "MAINNET");
    }

    private String baseUrl() {
        return testnet ? TESTNET : MAINNET;
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public NetworkType getNetworkType() {
        return testnet ? NetworkType.TESTNET : NetworkType.MAINNET;
    }

    // ===================== MARKET DATA =====================

    @Override
    public double getPrice(String symbol) {
        String url = baseUrl() + "/api/v3/ticker/price?symbol=" + symbol.toUpperCase();
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        JSONObject json = new JSONObject(response.getBody());
        return json.getDouble("price");
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, int limit) {
        String url = baseUrl() + "/api/v3/klines?symbol=" + symbol.toUpperCase()
                     + "&interval=" + interval + "&limit=" + limit;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        JSONArray arr = new JSONArray(response.getBody());
        List<Kline> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray c = arr.getJSONArray(i);
            list.add(new Kline(
                    c.getLong(0),
                    c.getDouble(1),
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getDouble(4),
                    c.getDouble(5)
            ));
        }
        return list;
    }

    // ===================== ORDERS =====================

    @Override
    public OrderResult placeOrder(Long chatId, String symbol, String side,
                                  String type, double qty, Double price) {
        ExchangeSettings s = resolveSettings(chatId);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("side", side.toUpperCase());
        params.put("type", type.toUpperCase());
        params.put("quantity", stripZeros(qty));
        if ("LIMIT".equalsIgnoreCase(type) && price != null) {
            params.put("price", stripZeros(price));
            params.put("timeInForce", "GTC");
        }

        String result = executeWithRetry(s, params, HttpMethod.POST, true);
        JSONObject json = new JSONObject(result);
        return new OrderResult(
                json.optString("orderId"),
                symbol, side, type, qty, price == null ? 0.0 : price,
                json.optString("status", "NEW"),
                System.currentTimeMillis()
        );
    }

    @Override
    public boolean cancelOrder(Long chatId, String symbol, String orderId) {
        ExchangeSettings s = resolveSettings(chatId);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol.toUpperCase());
        params.put("orderId", orderId);
        String res = executeWithRetry(s, params, HttpMethod.DELETE, false);
        return res != null && res.contains("orderId");
    }

    // ===================== BALANCES =====================

    @Override
    public Map<String, Balance> getFullBalance(Long chatId) {
        Map<String, Balance> result = new LinkedHashMap<>();
        try {
            ExchangeSettings settings = resolveSettings(chatId);
            String response = signedRequest(settings, "/api/v3/account", new LinkedHashMap<>(), HttpMethod.GET);
            if (response == null || response.isBlank()) {
                log.warn("⚠️ Binance getFullBalance: пустой ответ для chatId={}", chatId);
                return result;
            }

            JSONObject json = new JSONObject(response);
            JSONArray balances = json.getJSONArray("balances");
            for (int i = 0; i < balances.length(); i++) {
                JSONObject b = balances.getJSONObject(i);
                String asset = b.getString("asset");
                double free = b.getDouble("free");
                double locked = b.getDouble("locked");
                if (free + locked > 1e-7)
                    result.put(asset, new Balance(asset, free, locked));
            }

            log.info("💰 Загрузили {} активов Binance [{}]", result.size(), getNetworkType());
        } catch (Exception e) {
            log.error("❌ Ошибка получения баланса Binance: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Balance getBalance(Long chatId, String asset) {
        try {
            ExchangeSettings settings = resolveSettings(chatId);
            String response = signedRequest(settings, "/api/v3/account", new LinkedHashMap<>(), HttpMethod.GET);
            if (response == null || response.isBlank())
                return new Balance(asset, 0.0, 0.0);

            JSONObject json = new JSONObject(response);
            JSONArray balances = json.getJSONArray("balances");
            for (int i = 0; i < balances.length(); i++) {
                JSONObject b = balances.getJSONObject(i);
                if (asset.equalsIgnoreCase(b.getString("asset"))) {
                    double free = b.optDouble("free", 0.0);
                    double locked = b.optDouble("locked", 0.0);
                    return new Balance(asset, free, locked);
                }
            }
            return new Balance(asset, 0.0, 0.0);
        } catch (Exception e) {
            log.error("❌ Ошибка getBalance Binance: {}", e.getMessage());
            return new Balance(asset, 0.0, 0.0);
        }
    }

    // ===================== PRIVATE API CORE =====================

    private String executeWithRetry(ExchangeSettings s, Map<String, String> params,
                                    HttpMethod method, boolean retryOnNotional) {
        try {
            return signedRequest(s, "/api/v3/order", params, method);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (retryOnNotional && msg.contains("NOTIONAL")) {
                log.warn("⚠️ Binance NOTIONAL ошибка — пересчитываем количество...");
                BigDecimal qty = new BigDecimal(params.get("quantity"));
                BigDecimal newQty = qty.multiply(BigDecimal.valueOf(1.2));
                params.put("quantity", newQty.stripTrailingZeros().toPlainString());
                return signedRequest(s, "/api/v3/order", params, method);
            }
            throw ex;
        }
    }

    private String signedRequest(ExchangeSettings s, String endpoint, Map<String, String> params, HttpMethod method) {
        try {
            long timestamp = System.currentTimeMillis();
            params.put("recvWindow", "5000");
            params.put("timestamp", String.valueOf(timestamp));

            String query = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            String signature = sign(query, cleanSecret(s.getApiSecret()));
            String url = baseUrl() + endpoint + "?" + query + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", cleanKey(s.getApiKey()));
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            // 🔥 ЛОГИ ПЕРЕД ОТПРАВКОЙ
            log.warn("🌐 Binance REQUEST [{}] {} {}", getNetworkType(), method, url);
            log.warn("📤 Headers: {}", headers);
            log.warn("📤 Params: {}", params);
            log.warn("📤 Query: {}", query);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, method, new HttpEntity<>("", headers), String.class);

            // 🔥 ЛОГИ ПОСЛЕ ОТВЕТА
            log.warn("📥 Binance RESPONSE [{}]: {}", getNetworkType(), response.getBody());

            return response.getBody();

        } catch (HttpClientErrorException e) {
            log.error("❌ Binance HTTP ERROR: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Ошибка Binance HTTP: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("❌ signedRequest ERROR: {}", e.getMessage());
            throw new RuntimeException("Ошибка signedRequest Binance: " + e.getMessage(), e);
        }
    }

    // ===================== Подпись =====================
    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подписи Binance: " + e.getMessage(), e);
        }
    }

    // ===================== MARKET ORDER =====================
    @Override
    public Order placeMarketOrder(String symbol, OrderSide side, BigDecimal qty) {
        log.info("📤 MARKET ордер [{}]: {} {} qty={}", getNetworkType(), side, symbol, qty);
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

    // ===================== Helpers =====================
    private ExchangeSettings resolveSettings(Long chatId) {
        NetworkType need = getNetworkType();
        return settingsService.findAllByChatId(chatId).stream()
                .filter(ExchangeSettings::isEnabled)
                .filter(es -> "BINANCE".equalsIgnoreCase(es.getExchange()))
                .filter(es -> (es.getNetwork() == null ? NetworkType.MAINNET : es.getNetwork()) == need)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Не найдены активные ключи Binance для chatId=" + chatId + " и сети " + need));
    }

    private static String stripZeros(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String cleanKey(String k) {
        return k == null ? "" : k.trim();
    }

    private static String cleanSecret(String s) {
        return s == null ? "" : s.trim();
    }

    // ===================== SYMBOLS =====================
    @Override
    public List<String> getAllSymbols() {
        long now = System.currentTimeMillis();

        // ⚙️ Используем кэш (1 час)
        if (!cachedSymbols.isEmpty() && now - lastSymbolsFetch < 3600_000) {
            log.debug("♻️ Возвращаем кэшированные символы Binance [{}]: {} шт.", getNetworkType(), cachedSymbols.size());
            return cachedSymbols;
        }

        try {
            long start = System.currentTimeMillis();
            String url = baseUrl() + "/api/v3/exchangeInfo";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ Не удалось получить список символов Binance [{}]", getNetworkType());
                return cachedSymbols;
            }

            JSONObject json = new JSONObject(response.getBody());
            JSONArray symbols = json.getJSONArray("symbols");
            List<String> list = new ArrayList<>();
            for (int i = 0; i < symbols.length(); i++) {
                JSONObject obj = symbols.getJSONObject(i);
                if ("TRADING".equalsIgnoreCase(obj.optString("status")))
                    list.add(obj.getString("symbol"));
            }

            list.sort(String::compareTo);
            cachedSymbols = list;
            lastSymbolsFetch = now;
            log.info("📈 Binance: загружено {} символов [{}] за {} мс",
                    list.size(), getNetworkType(), System.currentTimeMillis() - start);
            return list;

        } catch (Exception e) {
            log.error("❌ Ошибка получения символов Binance [{}]: {}", getNetworkType(), e.getMessage());
            return cachedSymbols;
        }
    }
    @Override
    public List<String> getAvailableTimeframes() {
        try {
            // Binance возвращает интервалы в exchangeInfo.symbols[*].filters,
            // но мы можем получить их проще — через статическую карту, т.к. они фиксированы.
            // Если нужно динамически — можно парсить /api/v3/exchangeInfo.

            String url = baseUrl() + "/api/v3/exchangeInfo";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ Не удалось получить таймфреймы Binance ({}): пустой ответ", getNetworkType());
                return List.of();
            }

            // Binance имеет фиксированные интервалы, возвращаем их напрямую
            List<String> list = List.of(
                    "1s", "1m", "3m", "5m", "15m", "30m",
                    "1h", "2h", "4h", "6h", "8h", "12h",
                    "1d", "3d", "1w", "1M"
            );
            log.info("⏱ Binance: доступно {} таймфреймов [{}]", list.size(), getNetworkType());
            return list;

        } catch (Exception e) {
            log.error("❌ Ошибка получения таймфреймов Binance [{}]: {}", getNetworkType(), e.getMessage());
            return List.of("1m", "5m", "15m", "1h", "4h", "1d");
        }
    }

}

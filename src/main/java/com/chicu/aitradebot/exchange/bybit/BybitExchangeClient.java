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
import org.springframework.web.client.HttpClientErrorException;
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

        if (log.isDebugEnabled()) {
            log.debug("BYBIT client initialized. TESTNET is mapped to DEMO endpoint.");
        }
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
        String sym = normalizeSymbolOrThrow(symbol);

        try {
            // Публичная ручка — MAIN
            String url = MAIN + "/spot/v3/public/quote/ticker/price?symbol=" + sym;
            String raw = rest.getForObject(url, String.class);
            if (raw == null || raw.isBlank()) return 0;

            JSONObject json = new JSONObject(raw);
            JSONObject result = json.optJSONObject("result");
            if (result == null) return 0;

            return result.optDouble("price", 0);

        } catch (Exception e) {
            log.warn("⚠️ BYBIT getPrice failed symbol={} msg={}", sym, e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getPrice", e);
            return 0;
        }
    }

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

        String sym = normalizeSymbolOrThrow(symbol);
        String tf = mapIntervalV5(normalizeIntervalOrThrow(interval));
        int safeLimit = clamp(limit, 1, 1000);

        StringBuilder url = new StringBuilder(MAIN)
                .append("/v5/market/kline")
                .append("?category=spot")
                .append("&symbol=").append(sym)
                .append("&interval=").append(tf)
                .append("&limit=").append(safeLimit);

        if (startTimeMs > 0) url.append("&start=").append(startTimeMs);
        if (endTimeMs > 0) url.append("&end=").append(endTimeMs);

        String raw = rest.getForObject(url.toString(), String.class);
        if (raw == null || raw.isBlank()) return List.of();

        JSONObject root = new JSONObject(raw);

        if (root.optInt("retCode", -1) != 0) {
            log.warn("⚠️ BYBIT KLINES retCode={} msg={}",
                    root.optInt("retCode"), root.optString("retMsg"));
            return List.of();
        }

        JSONArray list = root.optJSONObject("result") != null
                ? root.getJSONObject("result").optJSONArray("list")
                : null;

        if (list == null || list.isEmpty()) return List.of();

        List<Kline> out = new ArrayList<>(list.length());

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

        out.sort(Comparator.comparingLong(Kline::openTime));
        return out;
    }

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
    // ORDERS (NEW INTERFACE)
    // =================================================================

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

        String sym = normalizeSymbolOrThrow(symbol);
        String sd = normalizeUpperOrThrow(side, "side");
        String tp = normalizeUpperOrThrow(type, "type");

        ExchangeSettings s = resolve(chatId, network);

        // ==== Spot order endpoint:
        // ВАЖНО: у тебя смешаны v3-private order и v5 подпись.
        // Поэтому делаем строго v5 spot order: /v5/order/create?category=spot
        // и не используем /spot/v3/private/order
        Map<String, String> p = new LinkedHashMap<>();
        p.put("category", "spot");
        p.put("symbol", sym);

        // Bybit v5: side = Buy/Sell, orderType = Market/Limit
        p.put("side", mapSideV5(sd));
        p.put("orderType", mapTypeV5(tp));

        // qty (только base qty в spot)
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity invalid (<=0)");
        }
        p.put("qty", strip(quantity));

        if ("LIMIT".equalsIgnoreCase(tp)) {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("LIMIT требует price > 0");
            }
            p.put("price", strip(price));
            // v5 timeInForce: GTC / IOC / FOK
            p.put("timeInForce", "GTC");
        }

        // extra params:
        // - orderLinkId (аналог clientOrderId)
        // - timeInForce, etc.
        if (extraParams != null && !extraParams.isEmpty()) {
            extraParams.forEach((k, v) -> {
                if (k == null || k.isBlank()) return;
                if (v == null) return;

                String kk = k.trim();
                String vv = String.valueOf(v).trim();
                if (kk.isEmpty() || vv.isEmpty()) return;

                // нормализуем "clientOrderId" -> "orderLinkId" (Bybit)
                if ("clientOrderId".equalsIgnoreCase(kk)) {
                    kk = "orderLinkId";
                }

                // чтобы не ломать контракт — не даём затереть критические поля
                if ("symbol".equalsIgnoreCase(kk) || "side".equalsIgnoreCase(kk) || "orderType".equalsIgnoreCase(kk) || "qty".equalsIgnoreCase(kk)) {
                    return;
                }

                p.put(kk, vv);
            });
        }

        String raw = signedV5(s, "/v5/order/create", p, HttpMethod.POST);
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("BYBIT order empty response");
        }

        JSONObject root = new JSONObject(raw);
        int rc = root.optInt("retCode", -1);
        if (rc != 0) {
            throw new RuntimeException("BYBIT order error: " + root.optString("retMsg") + " (retCode=" + rc + ")");
        }

        JSONObject result = root.optJSONObject("result");
        String orderId = result != null ? result.optString("orderId", null) : null;

        // Bybit create обычно возвращает только id, статус надо уточнять отдельной ручкой,
        // но для твоего пайплайна достаточно "NEW" / "FILLED" (MARKET иногда сразу Filled).
        String status = result != null ? result.optString("orderStatus", "NEW") : "NEW";

        // Для MARKET итоговая цена лучше уточняется по истории/деталям — но мы вернём:
        BigDecimal pxOut = (price != null ? price : BigDecimal.ZERO);

        return new OrderResult(
                orderId,
                sym,
                sd,
                tp,
                quantity,
                pxOut,
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

        // Bybit spot: qty — base qty.
        // QUOTE_QTY делаем конвертацией (как было), но теперь:
        // - если priceHint нет, берём getPrice()
        // - qty округляем DOWN, чтобы не превысить бюджет
        BigDecimal qtyBase;
        BigDecimal usedPrice = null;

        if (amountType == OrderAmountType.BASE_QTY) {
            qtyBase = amount;
        } else {
            if (side == OrderSide.SELL) {
                throw new IllegalArgumentException("BYBIT SPOT SELL не поддерживает QUOTE_QTY (нужен BASE_QTY)");
            }

            usedPrice = priceHint;
            if (usedPrice == null || usedPrice.signum() <= 0) {
                double p = getPrice(sym);
                if (p <= 0) throw new RuntimeException("BYBIT price unavailable for QUOTE_QTY conversion");
                usedPrice = BigDecimal.valueOf(p);
            }

            qtyBase = amount.divide(usedPrice, 12, RoundingMode.DOWN);
            if (qtyBase.signum() <= 0) {
                throw new RuntimeException("BYBIT QUOTE_QTY conversion produced qtyBase=0");
            }
        }

        OrderResult r = placeOrder(
                chatId,
                network,
                sym,
                side.name(),
                "MARKET",
                qtyBase,
                null,
                Map.of()
        );

        // Если Bybit вернул только id и NEW — это нормально, позже заполнится в ордер-сервисе.
        // Цена: если у нас есть usedPrice, используем её как hint.
        BigDecimal px = (r.price() != null && r.price().signum() > 0)
                ? r.price()
                : (usedPrice != null ? usedPrice : (priceHint != null ? priceHint : BigDecimal.ZERO));

        return Order.builder()
                .orderId(r.orderId())
                .chatId(chatId)
                .symbol(r.symbol())
                .side(r.side())
                .type(r.type())
                .price(px)
                .quantity(r.qty())
                .status(r.status())
                .filled("FILLED".equalsIgnoreCase(r.status()) || "FILLED".equalsIgnoreCase(String.valueOf(r.status())))
                .time(r.timestamp())
                .build();
    }

    @Override
    public boolean cancelOrder(Long chatId, NetworkType network, String symbol, String orderId) throws Exception {
        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId пустой");

        ExchangeSettings s = resolve(chatId, network);

        // v5 cancel:
        Map<String, String> p = new LinkedHashMap<>();
        p.put("category", "spot");
        p.put("symbol", normalizeSymbolOrThrow(symbol));
        p.put("orderId", orderId.trim());

        String raw = signedV5(s, "/v5/order/cancel", p, HttpMethod.POST);
        if (raw == null || raw.isBlank()) return false;

        JSONObject root = new JSONObject(raw);
        int rc = root.optInt("retCode", -1);
        if (rc != 0) {
            log.warn("⚠️ BYBIT cancel error: {}", root.optString("retMsg"));
            return false;
        }

        return true;
    }

    // =================================================================
    // BALANCE
    // =================================================================

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

        // ✅ Bybit: у разных аккаунтов баланс может быть в разных accountType
        List<String> accountTypes = List.of("UNIFIED", "SPOT", "CONTRACT");

        Map<String, Balance> merged = new LinkedHashMap<>();

        for (String accountType : accountTypes) {
            try {
                Map<String, Balance> part = loadWalletBalance(s, accountType);
                if (part == null || part.isEmpty()) continue;

                // ✅ merge: суммируем free/locked по активам
                part.forEach((asset, bal) -> merged.merge(asset, bal, (a, b) ->
                        new Balance(asset, a.free() + b.free(), a.locked() + b.locked())
                ));

            } catch (Exception e) {
                // не валим UI целиком
                log.debug("BYBIT getFullBalance accountType={} failed: {}", accountType, e.toString());
            }
        }

        return merged;
    }

    private Map<String, Balance> loadWalletBalance(ExchangeSettings s, String accountType) throws Exception {

        String raw = signedV5(
                s,
                "/v5/account/wallet-balance",
                Map.of("accountType", accountType),
                HttpMethod.GET
        );

        if (raw == null || raw.isBlank()) return Map.of();

        JSONObject root = new JSONObject(raw);

        if (root.optInt("retCode", -1) != 0) {
            log.debug("BYBIT BALANCE accountType={} retCode={} msg={}",
                    accountType, root.optInt("retCode"), root.optString("retMsg"));
            return Map.of();
        }

        JSONObject result = root.optJSONObject("result");
        if (result == null) return Map.of();

        JSONArray list = result.optJSONArray("list");
        if (list == null || list.isEmpty()) return Map.of();

        Map<String, Balance> out = new LinkedHashMap<>();

        // ✅ важно: list может быть несколько
        for (int li = 0; li < list.length(); li++) {
            JSONObject acc = list.optJSONObject(li);
            if (acc == null) continue;

            JSONArray coins = acc.optJSONArray("coin");
            if (coins == null || coins.isEmpty()) continue;

            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.optJSONObject(i);
                if (c == null) continue;

                String a = c.optString("coin", "").trim().toUpperCase(Locale.ROOT);
                if (a.isBlank()) continue;

                BigDecimal wallet = safeDecimal(c.opt("walletBalance"));
                BigDecimal withdraw = safeDecimal(c.opt("availableToWithdraw"));

                // ✅ если availableToWithdraw пусто/0, fallback на walletBalance
                BigDecimal free = (withdraw != null && withdraw.compareTo(BigDecimal.ZERO) > 0)
                        ? withdraw
                        : wallet;

                if (free == null) free = BigDecimal.ZERO;
                if (wallet == null) wallet = BigDecimal.ZERO;

                BigDecimal locked = wallet.subtract(free).max(BigDecimal.ZERO);

                // ✅ фильтруем total > 0
                if (wallet.compareTo(BigDecimal.ZERO) > 0 || free.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                    Balance bal = new Balance(a, free.doubleValue(), locked.doubleValue());
                    out.merge(a, bal, (x, y) -> new Balance(a, x.free() + y.free(), x.locked() + y.locked()));
                }
            }
        }

        return out;
    }

    // =================================================================
    // SYMBOLS
    // =================================================================

    @Override
    public List<String> getAllSymbols() {
        try {
            String raw = rest.getForObject(MAIN + "/spot/v3/public/symbols", String.class);
            if (raw == null || raw.isBlank()) return List.of();

            JSONObject root = new JSONObject(raw);
            JSONObject result = root.optJSONObject("result");
            if (result == null) return List.of();

            JSONArray arr = result.optJSONArray("list");
            if (arr == null || arr.isEmpty()) return List.of();

            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("Trading".equalsIgnoreCase(o.optString("status"))) {
                    String name = o.optString("name", "").trim().toUpperCase(Locale.ROOT);
                    if (!name.isBlank()) out.add(name);
                }
            }

            out.sort(String::compareTo);
            return out;

        } catch (Exception e) {
            log.warn("⚠️ BYBIT getAllSymbols failed msg={}", e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAllSymbols", e);
            return List.of();
        }
    }

    // =================================================================
    // ACCOUNT INFO / FEES
    // =================================================================

    @Override
    public AccountInfo getAccountInfo(long chatId, NetworkType network) {
        try {
            AccountFees fees = getAccountFees(chatId, network);
            BigDecimal makerPct = fees != null ? fees.getMakerPct() : null;
            BigDecimal takerPct = fees != null ? fees.getTakerPct() : null;

            // AccountInfo в твоей модели ожидает double.
            double maker = makerPct != null ? makerPct.doubleValue() : 0.10;
            double taker = takerPct != null ? takerPct.doubleValue() : 0.10;

            return AccountInfo.builder()
                    .makerFee(maker)
                    .takerFee(taker)
                    .makerFeeWithDiscount(maker)
                    .takerFeeWithDiscount(taker)
                    .vipLevel(0)
                    .usingBnbDiscount(false)
                    .build();

        } catch (Exception e) {
            log.warn("⚠️ BYBIT getAccountInfo failed chatId={} network={} msg={}",
                    chatId, network, e.getMessage());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAccountInfo", e);

            return AccountInfo.builder()
                    .makerFee(0.10)
                    .takerFee(0.10)
                    .makerFeeWithDiscount(0.10)
                    .takerFeeWithDiscount(0.10)
                    .vipLevel(0)
                    .usingBnbDiscount(false)
                    .build();
        }
    }

    @Override
    public AccountFees getAccountFees(long chatId, NetworkType networkType) {

        try {
            ExchangeSettings s = resolve(chatId, networkType);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("category", "spot");

            String raw = signedV5(
                    s,
                    "/v5/account/fee-rate",
                    params,
                    HttpMethod.GET
            );

            if (raw == null || raw.isBlank()) return null;

            JSONObject json = new JSONObject(raw);

            int retCode = json.optInt("retCode", -1);
            if (retCode != 0) {
                log.warn("⚠️ BYBIT getAccountFees retCode={} msg={}",
                        retCode, json.optString("retMsg"));
                return null;
            }

            JSONObject result = json.optJSONObject("result");
            if (result == null) return null;

            JSONArray list = result.optJSONArray("list");
            if (list == null || list.isEmpty()) return null;

            JSONObject fees = list.getJSONObject(0);

            // Bybit даёт долю (0.001 = 0.1%)
            BigDecimal makerRate = parseBd(fees.optString("makerFeeRate", null));
            BigDecimal takerRate = parseBd(fees.optString("takerFeeRate", null));

            // Переводим в проценты
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
            log.warn("⚠️ BYBIT getAccountFees failed (chatId={}, network={}): {}",
                    chatId, networkType, e.toString());
            if (log.isDebugEnabled()) log.debug("Stacktrace getAccountFees", e);
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
                : quoteAsset.trim().toUpperCase(Locale.ROOT);

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
            log.warn("⚠️ BYBIT tickers failed: asset={} err={}", qa, e.toString());
            tickerMap = Map.of();
        }

        List<SymbolDescriptor> out = new ArrayList<>(Math.min(instruments.length(), 2000));

        for (int i = 0; i < instruments.length(); i++) {

            JSONObject s = instruments.optJSONObject(i);
            if (s == null) continue;

            String symbol = s.optString("symbol", null);
            if (symbol == null || symbol.isBlank()) continue;

            String base = s.optString("baseCoin", null);
            String quote = s.optString("quoteCoin", null);
            String status = s.optString("status", "");

            if (!"Trading".equalsIgnoreCase(status)) continue;
            if (quote == null || !qa.equalsIgnoreCase(quote)) continue;

            JSONObject lotSize = s.optJSONObject("lotSizeFilter");
            JSONObject price = s.optJSONObject("priceFilter");

            BigDecimal minNotional = bdOrNull(lotSize != null ? lotSize.optString("minOrderAmt", null) : null);
            BigDecimal stepSize = bdOrNull(lotSize != null ? lotSize.optString("qtyStep", null) : null);
            BigDecimal tickSize = bdOrNull(price != null ? price.optString("tickSize", null) : null);

            Integer maxOrders = null;

            JSONObject t = tickerMap.get(symbol);

            BigDecimal lastPrice = (t != null) ? bdOrNull(t.optString("lastPrice", null)) : null;

            BigDecimal priceChangePct = null;
            if (t != null) {
                BigDecimal frac = bdOrNull(t.optString("price24hPcnt", null));
                if (frac != null) priceChangePct = frac.multiply(BigDecimal.valueOf(100));
            }

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

        if (log.isDebugEnabled()) {
            log.debug("BYBIT symbols loaded: asset={} total={}", qa, out.size());
        }
        return out;
    }

    // =================================================================
    // SIGN (Bybit HMAC) — ВАЖНО: stringToSign = ts + apiKey + recvWindow + query/body
    // =================================================================

    /**
     * Bybit v5 подпись:
     *  stringToSign = timestamp + apiKey + recvWindow + payload
     *
     * Для GET payload = queryString (без '?')
     * Для POST payload = bodyString (для JSON — json; для form — query)
     *
     * В нашем варианте мы используем application/x-www-form-urlencoded (query-string) и туда кладём body.
     */
    private String signedV5(
            ExchangeSettings s,
            String endpoint,
            Map<String, String> params,
            HttpMethod method
    ) throws Exception {

        long ts = System.currentTimeMillis();

        String payload = toQuery(params);

        String preSign = ts + s.getApiKey() + RECV_WINDOW + payload;
        String signature = sign(preSign, s.getApiSecret());

        HttpHeaders h = new HttpHeaders();
        h.set("X-BAPI-API-KEY", s.getApiKey());
        h.set("X-BAPI-SIGN", signature);
        h.set("X-BAPI-TIMESTAMP", String.valueOf(ts));
        h.set("X-BAPI-RECV-WINDOW", RECV_WINDOW);

        // Bybit допускает form-urlencoded; JSON тоже можно, но тогда payload другой.
        if (method == HttpMethod.POST) {
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        }

        String url = baseUrl(s.getNetwork()) + endpoint
                     + (method == HttpMethod.GET && !payload.isEmpty() ? "?" + payload : "");

        HttpEntity<String> entity = new HttpEntity<>(method == HttpMethod.POST ? payload : null, h);

        try {
            ResponseEntity<String> resp = rest.exchange(url, method, entity, String.class);
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            throw new RuntimeException("BYBIT http error: " + body, e);
        }
    }

    private String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String toQuery(Map<String, String> p) {
        if (p == null || p.isEmpty()) return "";
        return p.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null)
                .map(e -> e.getKey().trim() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    // =================================================================
    // helpers
    // =================================================================

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

    private static String normalizeInterval(String interval) {
        if (interval == null) return "";
        String s = interval.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "" : s;
    }

    private static String normalizeUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String strip(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal bdOrZero(String s) {
        BigDecimal v = bdOrNull(s);
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String mapSideV5(String sideUpper) {
        // вход может быть BUY/SELL
        if ("SELL".equalsIgnoreCase(sideUpper)) return "Sell";
        return "Buy";
    }

    private static String mapTypeV5(String typeUpper) {
        // вход может быть MARKET/LIMIT
        if ("LIMIT".equalsIgnoreCase(typeUpper)) return "Limit";
        return "Market";
    }
}

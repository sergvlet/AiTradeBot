package com.chicu.aitradebot.exchange.bybit;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClient.OrderAmountType;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BybitExchangeClient implements ExchangeClient {

    private static final String MAIN = "https://api.bybit.com";
    private static final String TESTNET = "https://api-testnet.bybit.com";

    private static final String RECV_WINDOW = "5000";
    private static final String DEFAULT_BALANCE_COINS = "USDT,USDC,BTC,ETH,BNB,SOL,XRP,ADA,DOGE,MNT";
    private static final int FALLBACK_QTY_SCALE = 8;

    private final Map<String, BigDecimal> qtyStepCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> quoteStepCache = new ConcurrentHashMap<>();

    private final RestTemplate rest;
    private final ExchangeSettingsService settingsService;

    public BybitExchangeClient(
            ExchangeSettingsService settingsService,
            @Qualifier("marketRestTemplate") RestTemplate rest
    ) {
        this.settingsService = settingsService;
        this.rest = rest;

        if (log.isDebugEnabled()) {
            log.debug("BYBIT client initialized. TESTNET is mapped to api-testnet.bybit.com.");
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
        return net == NetworkType.TESTNET ? TESTNET : MAIN;
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
            String url = MAIN + "/v5/market/tickers?category=spot&symbol=" + sym;
            String raw = rest.getForObject(url, String.class);
            if (raw == null || raw.isBlank()) return 0;

            JSONObject json = new JSONObject(raw);
            if (json.optInt("retCode", -1) != 0) {
                log.warn("⚠️ BYBIT getPrice retCode={} msg={}",
                        json.optInt("retCode"), json.optString("retMsg"));
                return 0;
            }

            JSONObject result = json.optJSONObject("result");
            JSONArray list = result != null ? result.optJSONArray("list") : null;
            if (list == null || list.isEmpty()) return 0;

            JSONObject ticker = list.optJSONObject(0);
            if (ticker == null) return 0;

            return safeDecimal(ticker.opt("lastPrice")).doubleValue();

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
    // ORDERS
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

        Map<String, String> p = new LinkedHashMap<>();
        p.put("category", "spot");
        p.put("symbol", sym);
        p.put("side", mapSideV5(sd));
        p.put("orderType", mapTypeV5(tp));

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity invalid (<=0)");
        }
        p.put("qty", strip(quantity));

        if ("LIMIT".equalsIgnoreCase(tp)) {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("LIMIT требует price > 0");
            }
            p.put("price", strip(price));
            p.put("timeInForce", "GTC");
        }

        if (extraParams != null && !extraParams.isEmpty()) {
            extraParams.forEach((k, v) -> {
                if (k == null || k.isBlank() || v == null) return;

                String kk = k.trim();
                String vv = String.valueOf(v).trim();
                if (kk.isEmpty() || vv.isEmpty()) return;

                if ("clientOrderId".equalsIgnoreCase(kk)) {
                    kk = "orderLinkId";
                }

                if ("symbol".equalsIgnoreCase(kk)
                    || "side".equalsIgnoreCase(kk)
                    || "orderType".equalsIgnoreCase(kk)
                    || "qty".equalsIgnoreCase(kk)) {
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
        String status = result != null ? result.optString("orderStatus", "NEW") : "NEW";
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

        BigDecimal usedPrice = positiveOrNull(priceHint);
        if (usedPrice == null) {
            double pxx = getPrice(sym);
            if (pxx > 0) {
                usedPrice = BigDecimal.valueOf(pxx);
            }
        }

        Map<String, String> extra = new LinkedHashMap<>();

        BigDecimal apiQty;
        BigDecimal dtoBaseQty;

        if (amountType == OrderAmountType.QUOTE_QTY) {
            if (side == OrderSide.SELL) {
                throw new IllegalArgumentException("BYBIT SPOT SELL не поддерживает QUOTE_QTY (нужен BASE_QTY)");
            }

            extra.put("marketUnit", "quoteCoin");

            apiQty = normalizeQuoteQty(amount);
            if (apiQty == null || apiQty.signum() <= 0) {
                throw new RuntimeException("BYBIT QUOTE_QTY normalization produced qtyQuote=0");
            }

            if (usedPrice == null || usedPrice.signum() <= 0) {
                throw new RuntimeException("BYBIT price unavailable for QUOTE_QTY conversion");
            }

            BigDecimal rawBaseQty = apiQty.divide(usedPrice, 16, RoundingMode.DOWN);
            dtoBaseQty = normalizeBaseQty(sym, network, rawBaseQty);

            if (dtoBaseQty == null || dtoBaseQty.signum() <= 0) {
                throw new RuntimeException("BYBIT QUOTE_QTY conversion produced qtyBase=0");
            }

            log.info("🔄 BYBIT MARKET BUY quote->quote sym={} quote={} marketQty={} estBaseQty={} priceHint={}",
                    sym,
                    strip(amount),
                    strip(apiQty),
                    strip(dtoBaseQty),
                    strip(usedPrice));

        } else {
            extra.put("marketUnit", "baseCoin");

            dtoBaseQty = normalizeBaseQty(sym, network, amount);
            if (dtoBaseQty == null || dtoBaseQty.signum() <= 0) {
                throw new RuntimeException("BYBIT BASE_QTY normalization produced qtyBase=0");
            }

            apiQty = dtoBaseQty;

            log.info("🔄 BYBIT MARKET {} base->base sym={} baseQty={} marketQty={}",
                    side.name(),
                    sym,
                    strip(amount),
                    strip(apiQty));
        }

        OrderResult r = placeOrder(
                chatId,
                network,
                sym,
                side.name(),
                "MARKET",
                apiQty,
                null,
                extra
        );

        String orderId = (r != null ? r.orderId() : null);
        FinalMarketState finalState = waitFinalMarketState(chatId, network, sym, orderId, dtoBaseQty, usedPrice);

        BigDecimal finalQty = positiveOrNull(finalState.executedQty()) != null
                ? finalState.executedQty()
                : dtoBaseQty;

        BigDecimal finalPrice = positiveOrNull(finalState.avgPrice()) != null
                ? finalState.avgPrice()
                : (usedPrice != null ? usedPrice : BigDecimal.ZERO);

        String finalStatus = finalState.status();
        boolean filled = "FILLED".equalsIgnoreCase(finalStatus);

        log.info("✅ BYBIT MARKET итог sym={} side={} status={} filled={} execQty={} avgPrice={} orderId={}",
                sym,
                side.name(),
                finalStatus,
                filled,
                strip(finalQty),
                strip(finalPrice),
                orderId);

        return Order.builder()
                .orderId(orderId)
                .chatId(chatId)
                .symbol(sym)
                .side(side.name())
                .type("MARKET")
                .price(finalPrice)
                .quantity(finalQty)
                .executedQty(finalQty)
                .avgPrice(finalPrice)
                .status(finalStatus)
                .filled(filled)
                .time(finalState.timeMs())
                .build();
    }

    private FinalMarketState waitFinalMarketState(Long chatId,
                                                  NetworkType network,
                                                  String symbol,
                                                  String orderId,
                                                  BigDecimal fallbackQty,
                                                  BigDecimal fallbackPrice) {
        if (orderId == null || orderId.isBlank()) {
            return new FinalMarketState(
                    normalizeFinalMarketStatus(null, fallbackQty, BigDecimal.ZERO),
                    positiveOrNull(fallbackQty) != null ? fallbackQty : BigDecimal.ZERO,
                    positiveOrNull(fallbackPrice) != null ? fallbackPrice : BigDecimal.ZERO,
                    System.currentTimeMillis()
            );
        }

        FinalMarketState lastSeen = null;

        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                FinalMarketState state = fetchFinalMarketState(chatId, network, symbol, orderId);
                if (state != null) {
                    lastSeen = state;
                    if (isFinalMarketStatus(state.status())) {
                        return state;
                    }
                }
                Thread.sleep(200L);
            } catch (Exception e) {
                log.debug("BYBIT final order poll skipped sym={} orderId={} attempt={} err={}",
                        symbol, orderId, attempt, e.toString());
            }
        }

        if (lastSeen != null) {
            return lastSeen;
        }

        return new FinalMarketState(
                normalizeFinalMarketStatus(null, fallbackQty, BigDecimal.ZERO),
                positiveOrNull(fallbackQty) != null ? fallbackQty : BigDecimal.ZERO,
                positiveOrNull(fallbackPrice) != null ? fallbackPrice : BigDecimal.ZERO,
                System.currentTimeMillis()
        );
    }

    private FinalMarketState fetchFinalMarketState(Long chatId,
                                                   NetworkType network,
                                                   String symbol,
                                                   String orderId) throws Exception {

        ExchangeSettings s = resolve(chatId, network);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("category", "spot");
        p.put("symbol", normalizeSymbolOrThrow(symbol));
        p.put("orderId", orderId);

        String raw = signedV5(s, "/v5/order/realtime", p, HttpMethod.GET);
        if (raw == null || raw.isBlank()) return null;

        JSONObject root = new JSONObject(raw);
        int rc = root.optInt("retCode", -1);
        if (rc != 0) {
            log.debug("BYBIT realtime order retCode={} msg={} sym={} orderId={}",
                    rc, root.optString("retMsg"), symbol, orderId);
            return null;
        }

        JSONObject result = root.optJSONObject("result");
        JSONArray list = result != null ? result.optJSONArray("list") : null;
        if (list == null || list.isEmpty()) return null;

        JSONObject item = list.optJSONObject(0);
        if (item == null) return null;

        BigDecimal execQty = safeDecimal(item.opt("cumExecQty"));
        BigDecimal avgPrice = safeDecimal(item.opt("avgPrice"));
        BigDecimal leavesQty = safeDecimal(item.opt("leavesQty"));

        long timeMs = 0L;
        try {
            timeMs = item.optLong("updatedTime", 0L);
        } catch (Exception ignored) {
            timeMs = 0L;
        }
        if (timeMs <= 0) {
            timeMs = System.currentTimeMillis();
        }

        String status = normalizeFinalMarketStatus(item.optString("orderStatus", null), execQty, leavesQty);

        return new FinalMarketState(
                status,
                execQty,
                avgPrice,
                timeMs
        );
    }

    private boolean isFinalMarketStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase(Locale.ROOT);
        return "FILLED".equals(s)
                || "CANCELED".equals(s)
                || "CANCELLED".equals(s)
                || "REJECTED".equals(s)
                || "PARTIALLY_FILLED_CANCELED".equals(s);
    }

    private String normalizeFinalMarketStatus(String rawStatus, BigDecimal executedQty, BigDecimal leavesQty) {
        String s = normalizeUpper(rawStatus);

        if (s == null) {
            if (positiveOrNull(executedQty) != null && safeDecimal(leavesQty).signum() == 0) {
                return "FILLED";
            }
            if (positiveOrNull(executedQty) != null) {
                return "PARTIALLY_FILLED";
            }
            return "NEW";
        }

        return switch (s) {
            case "FILLED" -> "FILLED";
            case "PARTIALLYFILLED", "PARTIALLY_FILLED" -> "PARTIALLY_FILLED";
            case "CANCELLED", "CANCELED" -> positiveOrNull(executedQty) != null
                    ? "PARTIALLY_FILLED_CANCELED"
                    : "CANCELED";
            case "REJECTED", "DEACTIVATED" -> "REJECTED";
            case "NEW", "OPEN", "PENDINGNEW", "PENDING_NEW", "CREATED" -> {
                if (positiveOrNull(executedQty) != null && safeDecimal(leavesQty).signum() == 0) {
                    yield "FILLED";
                }
                if (positiveOrNull(executedQty) != null) {
                    yield "PARTIALLY_FILLED";
                }
                yield "NEW";
            }
            default -> {
                if (positiveOrNull(executedQty) != null && safeDecimal(leavesQty).signum() == 0) {
                    yield "FILLED";
                }
                if (positiveOrNull(executedQty) != null) {
                    yield "PARTIALLY_FILLED";
                }
                yield s;
            }
        };
    }

    private record FinalMarketState(
            String status,
            BigDecimal executedQty,
            BigDecimal avgPrice,
            long timeMs
    ) {}


    private BigDecimal normalizeBaseQty(String symbol, NetworkType network, BigDecimal qty) {
        BigDecimal safeQty = positiveOrNull(qty);
        if (safeQty == null) return BigDecimal.ZERO;

        BigDecimal step = getQtyStep(symbol, network);
        if (positiveOrNull(step) != null) {
            BigDecimal normalized = safeQty.divide(step, 0, RoundingMode.DOWN).multiply(step);
            return normalized.setScale(Math.max(0, step.stripTrailingZeros().scale()), RoundingMode.DOWN).stripTrailingZeros();
        }

        return safeQty.setScale(FALLBACK_QTY_SCALE, RoundingMode.DOWN).stripTrailingZeros();
    }

    private BigDecimal getQtyStep(String symbol, NetworkType network) {
        String sym = normalizeSymbolOrThrow(symbol);
        String cacheKey = (network != null ? network.name() : "NA") + ":" + sym;
        BigDecimal cached = qtyStepCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String url = baseUrl(network) + "/v5/market/instruments-info?category=spot&symbol=" + sym;
            String raw = rest.getForObject(url, String.class);
            if (raw == null || raw.isBlank()) return null;

            JSONObject root = new JSONObject(raw);
            if (root.optInt("retCode", -1) != 0) return null;

            JSONObject result = root.optJSONObject("result");
            JSONArray list = result != null ? result.optJSONArray("list") : null;
            if (list == null || list.isEmpty()) return null;

            JSONObject item = list.optJSONObject(0);
            JSONObject lot = item != null ? item.optJSONObject("lotSizeFilter") : null;

            BigDecimal step = firstPositive(
                    bdOrNull(lot != null ? lot.optString("qtyStep", null) : null),
                    bdOrNull(lot != null ? lot.optString("basePrecision", null) : null),
                    bdOrNull(lot != null ? lot.optString("minOrderQty", null) : null)
            );

            if (positiveOrNull(step) != null) {
                qtyStepCache.put(cacheKey, step);
            }
            return step;

        } catch (Exception e) {
            log.debug("BYBIT qtyStep resolve failed sym={} net={} msg={}", sym, network, e.toString());
            return null;
        }
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        if (values == null) return null;
        for (BigDecimal value : values) {
            if (positiveOrNull(value) != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizeMarketStatus(String status) {
        String s = normalizeUpper(status);
        if (s == null) return "FILLED";

        return switch (s) {
            case "NEW", "OPEN", "PARTIALLY_FILLED" -> "FILLED";
            default -> s;
        };
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    @Override
    public boolean cancelOrder(Long chatId, NetworkType network, String symbol, String orderId) throws Exception {
        if (chatId == null) throw new IllegalArgumentException("chatId=null");
        if (network == null) throw new IllegalArgumentException("network=null");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId пустой");

        ExchangeSettings s = resolve(chatId, network);

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

        Map<String, Balance> merged = new LinkedHashMap<>();

        // 1) Основной UTA-кошелёк: wallet-balance возвращает только non-zero активы
        mergeBalances(merged, loadWalletBalanceUnified(s));

        // 2) Funding / дополнительные балансы
        mergeBalances(merged, loadAllCoinsBalanceV5(s, "FUND", DEFAULT_BALANCE_COINS));

        // 3) Если пока пусто — добираем single-coin probes по всем важным accountType
        if (merged.isEmpty()) {
            probeCommonCoins(merged, s, "UNIFIED");
            probeCommonCoins(merged, s, "FUND");
            probeCommonCoins(merged, s, "SPOT");
            probeCommonCoins(merged, s, "CONTRACT");
            probeCommonCoins(merged, s, "OPTION");
            probeCommonCoins(merged, s, "INVESTMENT");
        }

        // 4) Если всё ещё пусто — пробуем all-coins для остальных accountType
        if (merged.isEmpty()) {
            mergeBalances(merged, loadAllCoinsBalanceV5(s, "SPOT", null));
            mergeBalances(merged, loadAllCoinsBalanceV5(s, "CONTRACT", null));
        }

        if (merged.isEmpty()) {
            log.warn("⚠️ BYBIT balance is empty chatId={} net={}", chatId, network);
        } else {
            String preview = merged.entrySet().stream()
                    .limit(10)
                    .map(e -> e.getKey() + "[free=" + e.getValue().free() + ", locked=" + e.getValue().locked() + "]")
                    .collect(Collectors.joining(", "));
            log.info("💰 BYBIT balances chatId={} net={} -> {}", chatId, network, preview);
        }

        return merged;
    }

    private void probeCommonCoins(Map<String, Balance> merged, ExchangeSettings s, String accountType) {
        String[] coins = {"USDT", "USDC", "BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "MNT"};
        for (String coin : coins) {
            try {
                Balance b = loadSingleCoinBalanceV5(s, accountType, coin);
                if (b != null && (b.free() > 0.0d || b.locked() > 0.0d)) {
                    merged.merge(coin, b, (x, y) ->
                            new Balance(coin, x.free() + y.free(), x.locked() + y.locked()));
                }
            } catch (Exception e) {
                log.debug("BYBIT single-coin probe skipped accountType={} coin={} msg={}",
                        accountType, coin, e.getMessage());
            }
        }
    }

    private Balance loadSingleCoinBalanceV5(ExchangeSettings s, String accountType, String coin) throws Exception {

        Map<String, String> params = new LinkedHashMap<>();
        params.put("accountType", accountType);
        params.put("coin", coin);
        params.put("withTransferSafeAmount", "1");

        String raw = signedV5(
                s,
                "/v5/asset/transfer/query-account-coin-balance",
                params,
                HttpMethod.GET
        );

        if (raw == null || raw.isBlank()) return null;

        JSONObject root = new JSONObject(raw);
        int retCode = root.optInt("retCode", -1);
        if (retCode != 0) {
            log.debug("BYBIT SINGLE_BALANCE accountType={} coin={} retCode={} msg={}",
                    accountType, coin, retCode, root.optString("retMsg"));
            return null;
        }

        JSONObject result = root.optJSONObject("result");
        if (result == null) return null;

        String asset = normalizeUpper(result.optString("coin", coin));
        if (asset == null) asset = coin;

        BigDecimal wallet = safeDecimal(result.opt("walletBalance"));
        BigDecimal transferBalance = safeDecimal(result.opt("transferBalance"));
        BigDecimal transferSafeAmount = safeDecimal(result.opt("transferSafeAmount"));
        BigDecimal locked = safeDecimal(result.opt("locked"));
        BigDecimal bonus = safeDecimal(result.opt("bonus"));

        BigDecimal free = BigDecimal.ZERO;

        if (transferBalance.compareTo(BigDecimal.ZERO) > 0) {
            free = transferBalance;
        } else if (transferSafeAmount.compareTo(BigDecimal.ZERO) > 0) {
            free = transferSafeAmount;
        } else if (wallet.compareTo(BigDecimal.ZERO) > 0) {
            free = wallet.subtract(locked).max(BigDecimal.ZERO);
        }

        BigDecimal finalLocked = locked.max(BigDecimal.ZERO);
        BigDecimal total = free.add(finalLocked);

        if (total.compareTo(BigDecimal.ZERO) <= 0 && wallet.compareTo(BigDecimal.ZERO) > 0) {
            total = wallet;
            free = wallet.subtract(finalLocked).max(BigDecimal.ZERO);
        }

        if (total.compareTo(BigDecimal.ZERO) <= 0 && bonus.compareTo(BigDecimal.ZERO) > 0) {
            free = bonus;
        }

        if (free.compareTo(BigDecimal.ZERO) <= 0 && finalLocked.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        return new Balance(asset, free.doubleValue(), finalLocked.doubleValue());
    }
    private void mergeBalances(Map<String, Balance> target, Map<String, Balance> part) {
        if (part == null || part.isEmpty()) return;

        part.forEach((asset, bal) -> {
            String a = normalizeUpper(asset);
            if (a == null || bal == null) return;

            target.merge(a, new Balance(a, bal.free(), bal.locked()),
                    (x, y) -> new Balance(a, x.free() + y.free(), x.locked() + y.locked()));
        });
    }

    /**
     * Unified wallet: берём все ненулевые активы напрямую.
     */
    private Map<String, Balance> loadWalletBalanceUnified(ExchangeSettings s) throws Exception {

        Map<String, String> params = new LinkedHashMap<>();
        params.put("accountType", "UNIFIED");

        String raw = signedV5(
                s,
                "/v5/account/wallet-balance",
                params,
                HttpMethod.GET
        );

        if (raw == null || raw.isBlank()) return Map.of();

        JSONObject root = new JSONObject(raw);
        int retCode = root.optInt("retCode", -1);
        if (retCode != 0) {
            log.debug("BYBIT WALLET_BALANCE UNIFIED retCode={} msg={}",
                    retCode, root.optString("retMsg"));
            return Map.of();
        }

        JSONObject result = root.optJSONObject("result");
        if (result == null) return Map.of();

        JSONArray list = result.optJSONArray("list");
        if (list == null || list.isEmpty()) return Map.of();

        Map<String, Balance> out = new LinkedHashMap<>();

        for (int li = 0; li < list.length(); li++) {
            JSONObject acc = list.optJSONObject(li);
            if (acc == null) continue;

            JSONArray coins = acc.optJSONArray("coin");
            if (coins == null || coins.isEmpty()) continue;

            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.optJSONObject(i);
                if (c == null) continue;

                String asset = normalizeUpper(c.optString("coin", null));
                if (asset == null) continue;

                BigDecimal wallet = safeDecimal(c.opt("walletBalance"));
                BigDecimal equity = safeDecimal(c.opt("equity"));
                BigDecimal locked = safeDecimal(c.opt("locked"));
                BigDecimal borrowAmount = safeDecimal(c.opt("borrowAmount"));
                BigDecimal spotBorrow = safeDecimal(c.opt("spotBorrow"));

                BigDecimal free = wallet
                        .subtract(locked)
                        .subtract(borrowAmount)
                        .subtract(spotBorrow)
                        .max(BigDecimal.ZERO);

                BigDecimal total = wallet.max(BigDecimal.ZERO);
                if (total.signum() <= 0 && equity.signum() > 0) {
                    total = equity;
                    if (free.signum() <= 0) {
                        free = equity.max(BigDecimal.ZERO);
                    }
                }

                BigDecimal finalLocked = total.subtract(free).max(BigDecimal.ZERO);

                if (total.signum() <= 0 && finalLocked.signum() <= 0 && free.signum() <= 0) {
                    continue;
                }

                out.put(asset, new Balance(
                        asset,
                        free.doubleValue(),
                        finalLocked.doubleValue()
                ));
            }
        }
        if (out.isEmpty()) {
            log.warn("⚠️ BYBIT wallet-balance UNIFIED returned no non-zero coins");
        } else {
            log.info("💰 BYBIT wallet-balance UNIFIED coins={}", out.keySet());
        }
        return out;
    }

    /**
     * Get All Coins Balance:
     * - UNIFIED/FUND -> coin обязателен/желателен
     * - SPOT/CONTRACT можно без coin
     */
    private Map<String, Balance> loadAllCoinsBalanceV5(
            ExchangeSettings s,
            String accountType,
            String coinCsv
    ) throws Exception {

        Map<String, String> params = new LinkedHashMap<>();
        params.put("accountType", accountType);

        if (coinCsv != null && !coinCsv.isBlank()) {
            params.put("coin", coinCsv);
        }

        String raw = signedV5(
                s,
                "/v5/asset/transfer/query-account-coins-balance",
                params,
                HttpMethod.GET
        );

        if (raw == null || raw.isBlank()) return Map.of();

        JSONObject root = new JSONObject(raw);
        int retCode = root.optInt("retCode", -1);
        if (retCode != 0) {
            log.debug("BYBIT ALL_BALANCE accountType={} retCode={} msg={}",
                    accountType, retCode, root.optString("retMsg"));
            return Map.of();
        }

        JSONObject result = root.optJSONObject("result");
        if (result == null) return Map.of();

        JSONArray list = result.optJSONArray("balance");
        if (list == null || list.isEmpty()) {
            list = result.optJSONArray("coin");
        }
        if (list == null || list.isEmpty()) return Map.of();

        Map<String, Balance> out = new LinkedHashMap<>();

        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;

            String asset = normalizeUpper(c.optString("coin", null));
            if (asset == null) continue;

            BigDecimal wallet = safeDecimal(c.opt("walletBalance"));
            BigDecimal transferBalance = safeDecimal(c.opt("transferBalance"));
            BigDecimal transferSafeAmount = safeDecimal(c.opt("transferSafeAmount"));
            BigDecimal locked = safeDecimal(c.opt("locked"));
            BigDecimal borrowAmount = safeDecimal(c.opt("borrowAmount"));

            BigDecimal free = BigDecimal.ZERO;

            if (transferBalance.compareTo(BigDecimal.ZERO) > 0) {
                free = transferBalance;
            } else if (transferSafeAmount.compareTo(BigDecimal.ZERO) > 0) {
                free = transferSafeAmount;
            } else if (wallet.compareTo(BigDecimal.ZERO) > 0) {
                free = wallet.subtract(locked).subtract(borrowAmount).max(BigDecimal.ZERO);
            }

            BigDecimal total = wallet.max(BigDecimal.ZERO);
            BigDecimal finalLocked = total.subtract(free).max(BigDecimal.ZERO);

            if (total.signum() <= 0 && free.signum() <= 0 && finalLocked.signum() <= 0) {
                continue;
            }

            out.put(asset, new Balance(
                    asset,
                    free.doubleValue(),
                    finalLocked.doubleValue()
            ));
        }

        return out;
    }

    // =================================================================
    // SYMBOLS
    // =================================================================

    @Override
    public List<String> getAllSymbols() {
        try {
            String raw = rest.getForObject(MAIN + "/v5/market/instruments-info?category=spot", String.class);
            if (raw == null || raw.isBlank()) return List.of();

            JSONObject root = new JSONObject(raw);
            if (root.optInt("retCode", -1) != 0) {
                log.warn("⚠️ BYBIT getAllSymbols retCode={} msg={}",
                        root.optInt("retCode"), root.optString("retMsg"));
                return List.of();
            }

            JSONObject result = root.optJSONObject("result");
            JSONArray arr = result != null ? result.optJSONArray("list") : null;
            if (arr == null || arr.isEmpty()) return List.of();

            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("Trading".equalsIgnoreCase(o.optString("status"))) {
                    String name = o.optString("symbol", "").trim().toUpperCase(Locale.ROOT);
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
    // TRADABLE SYMBOLS
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
    // SIGN
    // =================================================================

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
        h.set("X-BAPI-SIGN-TYPE", "2");

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

    private static String mapSideV5(String sideUpper) {
        if ("SELL".equalsIgnoreCase(sideUpper)) return "Sell";
        return "Buy";
    }

    private static String mapTypeV5(String typeUpper) {
        if ("LIMIT".equalsIgnoreCase(typeUpper)) return "Limit";
        return "Market";
    }
    private BigDecimal normalizeQuoteQty(BigDecimal quoteQty) {
        BigDecimal safe = positiveOrNull(quoteQty);
        if (safe == null) return BigDecimal.ZERO;

        // Для spot BUY по quoteCoin держим аккуратную точность и режем вниз,
        // чтобы не выйти за бюджет.
        return safe.setScale(4, RoundingMode.DOWN).stripTrailingZeros();
    }
}


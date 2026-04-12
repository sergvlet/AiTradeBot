package com.chicu.aitradebot.market.provider;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.dto.SymbolInfoDto;
import com.chicu.aitradebot.market.service.MarketInfoProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 📈 Bybit Spot Market Provider (V5)
 *
 * Исправления:
 * - убран мёртвый Open API V3
 * - используется актуальный V5 market API
 * - добавлен кэш, чтобы settings/dashboard не долбили Bybit на каждый polling
 * - таймфреймы приводятся к внутреннему формату бота
 */
@Slf4j
@Component
public class BybitMarketInfoProvider implements MarketInfoProvider {

    private static final String MAIN = "https://api.bybit.com";
    private static final String TEST = "https://api-testnet.bybit.com";

    /**
     * Нормальный кэш: 10 сек.
     * Этого хватает, чтобы не спамить биржу при частых GET /config/state.
     */
    private static final long CACHE_TTL_OK_MS = 10_000L;

    /**
     * Если Bybit временно вернул пусто — короткий антиспам кэш.
     */
    private static final long CACHE_TTL_EMPTY_MS = 5_000L;

    private final RestTemplate http = new RestTemplate();
    private final Map<NetworkType, CachedMarket> cache = new ConcurrentHashMap<>();

    private static class CachedMarket {
        long lastUpdate;
        long ttlMs;
        Map<String, JSONObject> instruments = Collections.emptyMap();
        Map<String, JSONObject> stats = Collections.emptyMap();
        List<SymbolInfoDto> symbols = Collections.emptyList();

        boolean isFresh() {
            return (System.currentTimeMillis() - lastUpdate) < ttlMs;
        }

        boolean hasData() {
            return symbols != null && !symbols.isEmpty();
        }
    }

    @Override
    public boolean supports(String exchange) {
        return "BYBIT".equalsIgnoreCase(exchange);
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public MarketOverviewDto getOverview(NetworkType network) {
        CachedMarket market = loadCached(network);

        return MarketOverviewDto.builder()
                .symbols(market.symbols)
                .timeframes(getBybitTimeframes())
                .lastUpdate(market.lastUpdate)
                .build();
    }

    @Override
    public List<SymbolInfoDto> searchSymbols(NetworkType network, String query) {
        CachedMarket market = loadCached(network);
        String q = normalizeUpper(query);

        if (q == null) {
            return market.symbols.stream()
                    .limit(20)
                    .collect(Collectors.toList());
        }

        return market.symbols.stream()
                .filter(s -> normalizeUpper(s.getSymbol()) != null && normalizeUpper(s.getSymbol()).contains(q))
                .sorted(Comparator.comparing(SymbolInfoDto::getVolume, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .collect(Collectors.toList());
    }

    @Override
    public SymbolInfoDto getSymbolInfo(NetworkType network, String symbol) {
        String sym = normalizeUpper(symbol);
        if (sym == null) {
            return SymbolInfoDto.builder()
                    .symbol(symbol)
                    .price(0)
                    .changePct(0)
                    .volume(0)
                    .status("UNKNOWN")
                    .build();
        }

        CachedMarket market = loadCached(network);
        JSONObject instrument = market.instruments.get(sym);
        JSONObject ticker = market.stats.get(sym);

        if (instrument == null && ticker == null) {
            return SymbolInfoDto.builder()
                    .symbol(sym)
                    .price(0)
                    .changePct(0)
                    .volume(0)
                    .status("UNKNOWN")
                    .build();
        }

        double price = ticker != null ? parseDouble(ticker.opt("lastPrice")) : 0.0;
        double changePct = ticker != null ? parseDouble(ticker.opt("price24hPcnt")) * 100.0 : 0.0;
        double volume = ticker != null ? firstPositive(
                parseDouble(ticker.opt("turnover24h")),
                parseDouble(ticker.opt("volume24h"))
        ) : 0.0;
        String status = instrument != null ? normalizeStatus(instrument.optString("status", "TRADING")) : "TRADING";

        return SymbolInfoDto.builder()
                .symbol(sym)
                .price(price)
                .changePct(changePct)
                .volume(volume)
                .status(status)
                .build();
    }

    @Override
    public List<String> getAllSymbols(NetworkType network) {
        return loadCached(network).symbols.stream()
                .map(SymbolInfoDto::getSymbol)
                .collect(Collectors.toList());
    }

    private CachedMarket loadCached(NetworkType network) {
        NetworkType net = network != null ? network : NetworkType.TESTNET;
        CachedMarket cached = cache.computeIfAbsent(net, n -> new CachedMarket());

        if (cached.isFresh()) {
            return cached;
        }

        try {
            Map<String, JSONObject> instruments = loadInstruments(net);
            Map<String, JSONObject> tickers = loadTickers(net);
            List<SymbolInfoDto> symbols = buildSymbols(instruments, tickers);

            if (!symbols.isEmpty()) {
                cached.instruments = instruments;
                cached.stats = tickers;
                cached.symbols = symbols;
                cached.lastUpdate = System.currentTimeMillis();
                cached.ttlMs = CACHE_TTL_OK_MS;

                log.debug("⚡ Bybit V5 cache updated: {} symbols @{}", symbols.size(), net);
                return cached;
            }

            if (cached.hasData()) {
                log.warn("⚠️ BYBIT V5 вернул пустой список, оставляю предыдущий кэш @{}", net);
                return cached;
            }

            cached.instruments = Collections.emptyMap();
            cached.stats = Collections.emptyMap();
            cached.symbols = Collections.emptyList();
            cached.lastUpdate = System.currentTimeMillis();
            cached.ttlMs = CACHE_TTL_EMPTY_MS;
            return cached;

        } catch (Exception e) {
            if (cached.hasData()) {
                log.warn("⚠️ BYBIT market cache refresh failed, использую старый кэш @{} err={}", net, e.toString());
                return cached;
            }

            log.error("❌ Ошибка обновления Bybit V5 кэша @{}: {}", net, e.getMessage());
            cached.instruments = Collections.emptyMap();
            cached.stats = Collections.emptyMap();
            cached.symbols = Collections.emptyList();
            cached.lastUpdate = System.currentTimeMillis();
            cached.ttlMs = CACHE_TTL_EMPTY_MS;
            return cached;
        }
    }

    private Map<String, JSONObject> loadInstruments(NetworkType network) {
        String url = baseUrl(network) + "/v5/market/instruments-info?category=spot";

        ResponseEntity<String> response = http.getForEntity(url, String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }

        JSONObject root = new JSONObject(body);
        int retCode = root.optInt("retCode", -1);
        if (retCode != 0) {
            log.warn("⚠️ BYBIT instruments-info retCode={} msg={}", retCode, root.optString("retMsg"));
            return Collections.emptyMap();
        }

        JSONObject result = root.optJSONObject("result");
        JSONArray list = result != null ? result.optJSONArray("list") : null;
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;

            String symbol = normalizeUpper(item.optString("symbol", null));
            if (symbol == null) continue;

            out.put(symbol, item);
        }
        return out;
    }

    private Map<String, JSONObject> loadTickers(NetworkType network) {
        String url = baseUrl(network) + "/v5/market/tickers?category=spot";

        ResponseEntity<String> response = http.getForEntity(url, String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }

        JSONObject root = new JSONObject(body);
        int retCode = root.optInt("retCode", -1);
        if (retCode != 0) {
            log.warn("⚠️ BYBIT tickers retCode={} msg={}", retCode, root.optString("retMsg"));
            return Collections.emptyMap();
        }

        JSONObject result = root.optJSONObject("result");
        JSONArray list = result != null ? result.optJSONArray("list") : null;
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, JSONObject> out = new HashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;

            String symbol = normalizeUpper(item.optString("symbol", null));
            if (symbol == null) continue;

            out.put(symbol, item);
        }
        return out;
    }

    private List<SymbolInfoDto> buildSymbols(Map<String, JSONObject> instruments, Map<String, JSONObject> tickers) {
        if (instruments == null || instruments.isEmpty()) {
            return Collections.emptyList();
        }

        List<SymbolInfoDto> out = new ArrayList<>(instruments.size());

        for (Map.Entry<String, JSONObject> entry : instruments.entrySet()) {
            String symbol = entry.getKey();
            JSONObject instrument = entry.getValue();
            if (instrument == null) continue;

            String statusRaw = instrument.optString("status", "Trading");
            if (!"Trading".equalsIgnoreCase(statusRaw)) {
                continue;
            }

            JSONObject ticker = tickers.get(symbol);

            double price = ticker != null ? parseDouble(ticker.opt("lastPrice")) : 0.0;
            double changePct = ticker != null ? parseDouble(ticker.opt("price24hPcnt")) * 100.0 : 0.0;
            double volume = ticker != null ? firstPositive(
                    parseDouble(ticker.opt("turnover24h")),
                    parseDouble(ticker.opt("volume24h"))
            ) : 0.0;

            out.add(SymbolInfoDto.builder()
                    .symbol(symbol)
                    .price(price)
                    .changePct(changePct)
                    .volume(volume)
                    .status(normalizeStatus(statusRaw))
                    .build());
        }

        out.sort(Comparator.comparing(SymbolInfoDto::getVolume, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private String baseUrl(NetworkType network) {
        return network == NetworkType.TESTNET ? TEST : MAIN;
    }

    private List<String> getBybitTimeframes() {
        return List.of(
                "1m",
                "3m",
                "5m",
                "15m",
                "30m",
                "1h",
                "2h",
                "4h",
                "6h",
                "12h",
                "1d",
                "1w",
                "1mo"
        );
    }

    private static String normalizeStatus(String status) {
        return "Trading".equalsIgnoreCase(status) ? "TRADING" : "BREAK";
    }

    private static String normalizeUpper(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static double parseDouble(Object value) {
        if (value == null) return 0.0;
        try {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) return 0.0;
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double firstPositive(double first, double second) {
        if (Double.isFinite(first) && first > 0.0) {
            return first;
        }
        if (Double.isFinite(second) && second > 0.0) {
            return second;
        }
        return 0.0;
    }
}

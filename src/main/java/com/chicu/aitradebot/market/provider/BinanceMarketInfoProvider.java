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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ⚡ Оптимизированный Binance MarketInfo Provider
 * - кэш exchangeInfo + stats
 * - обновляется раз в 10 сек
 * - searchSymbols работает мгновенно
 */
@Slf4j
@Component
public class BinanceMarketInfoProvider implements MarketInfoProvider {

    private static final String MAIN = "https://api.binance.com";
    private static final String TEST = "https://testnet.binance.vision";

    private final RestTemplate http = new RestTemplate();

    // ================================================================
    // КЭШЫ
    // ================================================================
    private final Map<NetworkType, CachedMarket> cache = new ConcurrentHashMap<>();

    private static class CachedMarket {
        long lastUpdate;
        Map<String, String> exchangeInfo = Collections.emptyMap();
        Map<String, JSONObject> stats = Collections.emptyMap();
        List<SymbolInfoDto> symbols = Collections.emptyList();
    }

    private String baseUrl(NetworkType net) {
        return net == NetworkType.TESTNET ? TEST : MAIN;
    }

    // ================================================================
    // API MarketInfoProvider
    // ================================================================
    @Override
    public boolean supports(String exchange) {
        return "BINANCE".equalsIgnoreCase(exchange);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public MarketOverviewDto getOverview(NetworkType network) {
        CachedMarket m = loadCached(network);

        return MarketOverviewDto.builder()
                .symbols(m.symbols)
                .timeframes(getBinanceTimeframes())
                .lastUpdate(m.lastUpdate)
                .build();
    }

    @Override
    public List<SymbolInfoDto> searchSymbols(NetworkType network, String query) {

        CachedMarket m = loadCached(network);
        final String q = (query == null ? "" : query.trim().toUpperCase());

        if (q.isEmpty()) {
            return m.symbols.stream().limit(20).collect(Collectors.toList());
        }

        return m.symbols.stream()
                .filter(s -> s.getSymbol().toUpperCase().contains(q))
                .sorted(Comparator.comparing(SymbolInfoDto::getVolume).reversed())
                .limit(20)
                .collect(Collectors.toList());
    }

    @Override
    public SymbolInfoDto getSymbolInfo(NetworkType network, String symbol) {

        CachedMarket m = loadCached(network);
        JSONObject t = m.stats.get(symbol);

        if (t == null) {
            return SymbolInfoDto.builder()
                    .symbol(symbol)
                    .price(0)
                    .changePct(0)
                    .volume(0)
                    .status("UNKNOWN")
                    .build();
        }

        return SymbolInfoDto.builder()
                .symbol(symbol)
                .price(t.optDouble("lastPrice", 0.0))
                .changePct(t.optDouble("priceChangePercent", 0.0))
                .volume(t.optDouble("quoteVolume", 0.0))
                .status("TRADING")
                .build();
    }

    @Override
    public List<String> getAllSymbols(NetworkType network) {
        CachedMarket m = loadCached(network);
        return m.symbols.stream()
                .map(SymbolInfoDto::getSymbol)
                .collect(Collectors.toList());
    }

    // ================================================================
    // ЗАГРУЗКА + КЭШ
    // ================================================================
    private CachedMarket loadCached(NetworkType network) {

        CachedMarket m = cache.computeIfAbsent(network, n -> new CachedMarket());

        long now = System.currentTimeMillis();

        // обновляем кэш не чаще чем 1 раз в 10 секунд
        if (now - m.lastUpdate < 10_000 && !m.symbols.isEmpty()) {
            return m;
        }

        try {
            Map<String, String> info = loadExchangeInfo(network);
            Map<String, JSONObject> stats = load24hStats(network);

            List<SymbolInfoDto> list = new ArrayList<>();

            for (var entry : info.entrySet()) {
                String symbol = entry.getKey();
                String status = entry.getValue();

                JSONObject t = stats.get(symbol);

                double price = t != null ? t.optDouble("lastPrice", 0.0) : 0.0;
                double change = t != null ? t.optDouble("priceChangePercent", 0.0) : 0.0;
                double volume = t != null ? t.optDouble("quoteVolume", 0.0) : 0.0;

                list.add(SymbolInfoDto.builder()
                        .symbol(symbol)
                        .status(status)
                        .price(price)
                        .changePct(change)
                        .volume(volume)
                        .build());
            }

            list.sort(Comparator.comparing(SymbolInfoDto::getVolume).reversed());

            m.exchangeInfo = info;
            m.stats = stats;
            m.symbols = list;
            m.lastUpdate = now;

            log.debug("⚡ Binance cache updated: {} symbols", list.size());

        } catch (Exception e) {
            log.error("❌ Ошибка обновления Binance кэша: {}", e.getMessage());
        }

        return m;
    }

    // ================================================================
    // LOADERS
    // ================================================================
    private Map<String, String> loadExchangeInfo(NetworkType net) {

        String url = baseUrl(net) + "/api/v3/exchangeInfo";

        try {
            ResponseEntity<String> response = http.getForEntity(url, String.class);
            JSONArray arr = new JSONObject(response.getBody()).optJSONArray("symbols");

            Map<String, String> out = new LinkedHashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.put(o.optString("symbol"), o.optString("status"));
            }

            return out;

        } catch (Exception e) {
            log.error("❌ exchangeInfo error: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, JSONObject> load24hStats(NetworkType net) {

        String url = baseUrl(net) + "/api/v3/ticker/24hr";

        try {
            ResponseEntity<String> response = http.getForEntity(url, String.class);
            JSONArray arr = new JSONArray(response.getBody());

            Map<String, JSONObject> out = new HashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                out.put(t.getString("symbol"), t);
            }

            return out;

        } catch (Exception e) {
            log.error("❌ ticker/24hr error: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> getBinanceTimeframes() {
        return List.of("1m", "3m", "5m", "15m", "30m",
                "1h", "2h", "4h", "1d", "1w", "1M");
    }
}

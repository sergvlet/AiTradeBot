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
import java.util.stream.Collectors;

/**
 * 📈 Bybit Spot Market Provider (PUBLIC v3)
 */
@Slf4j
@Component
public class BybitMarketInfoProvider implements MarketInfoProvider {

    private static final String MAIN = "https://api.bybit.com";
    private static final String TEST = "https://api-testnet.bybit.com";

    private final RestTemplate http = new RestTemplate();

    // =====================================================================
    // ОБЯЗАТЕЛЬНЫЕ МЕТОДЫ ИНТЕРФЕЙСА
    // =====================================================================

    @Override
    public boolean supports(String exchange) {
        return "BYBIT".equalsIgnoreCase(exchange);
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public List<String> getAllSymbols(NetworkType network) {
        // используем уже существующий loader
        Map<String, JSONObject> map = loadSymbols(network);
        return new ArrayList<>(map.keySet());
    }

    private String baseUrl(NetworkType network) {
        return network == NetworkType.TESTNET ? TEST : MAIN;
    }

    // =====================================================================
    // OVERVIEW
    // =====================================================================
    @Override
    public MarketOverviewDto getOverview(NetworkType network) {

        log.debug("📊 [Bybit] Загрузка рыночных данных @{}", network);

        Map<String, JSONObject> symbols = loadSymbols(network);
        Map<String, JSONObject> stats   = load24hStats(network);

        List<SymbolInfoDto> list = new ArrayList<>();

        for (var entry : symbols.entrySet()) {

            String symbol = entry.getKey();
            JSONObject info = entry.getValue();

            boolean trading = "Trading".equalsIgnoreCase(info.optString("status", "Trading"));

            JSONObject t = stats.get(symbol);

            double price = t != null ? t.optDouble("lastPrice", 0.0) : 0.0;
            double change = t != null ? t.optDouble("price24hPcnt", 0.0) * 100.0 : 0.0; // Bybit даёт 0.01 = 1%
            double volume = t != null ? t.optDouble("quoteVolume24h", 0.0) : 0.0;

            list.add(SymbolInfoDto.builder()
                    .symbol(symbol)
                    .price(price)
                    .changePct(change)
                    .volume(volume)
                    .status(trading ? "TRADING" : "BREAK")
                    .build());
        }

        list.sort(Comparator.comparing(SymbolInfoDto::getVolume).reversed());

        return MarketOverviewDto.builder()
                .symbols(list)
                .timeframes(getBybitTimeframes())
                .lastUpdate(System.currentTimeMillis())
                .build();
    }

    // =====================================================================
    // SEARCH
    // =====================================================================
    @Override
    public List<SymbolInfoDto> searchSymbols(NetworkType network, String query) {

        final String q = (query != null ? query.toUpperCase() : "");

        MarketOverviewDto all = getOverview(network);

        if (q.isEmpty()) {
            return all.getSymbols().stream()
                    .limit(20)
                    .collect(Collectors.toList());
        }

        return all.getSymbols().stream()
                .filter(s -> s.getSymbol().toUpperCase().contains(q))
                .limit(20)
                .collect(Collectors.toList());
    }

    // =====================================================================
    // SYMBOL DETAIL
    // =====================================================================
    @Override
    public SymbolInfoDto getSymbolInfo(NetworkType network, String symbol) {

        Map<String, JSONObject> stats = load24hStats(network);

        JSONObject t = stats.getOrDefault(symbol, null);

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
                .changePct(t.optDouble("price24hPcnt", 0.0) * 100.0)
                .volume(t.optDouble("quoteVolume24h", 0.0))
                .status("TRADING")
                .build();
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    /** 📌 Загружает список всех SymbolSpot */
    private Map<String, JSONObject> loadSymbols(NetworkType net) {

        String url = baseUrl(net) + "/spot/v3/public/symbols";

        try {
            ResponseEntity<String> r = http.getForEntity(url, String.class);
            JSONObject json = new JSONObject(r.getBody());
            JSONObject result = json.optJSONObject("result");

            JSONArray arr = result != null ? result.optJSONArray("list") : null;
            if (arr == null) return Collections.emptyMap();

            Map<String, JSONObject> out = new LinkedHashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.put(o.getString("name"), o);
            }

            return out;

        } catch (Exception e) {
            log.error("❌ Bybit loadSymbols error: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 📌 24h статистика Spot */
    private Map<String, JSONObject> load24hStats(NetworkType net) {

        String url = baseUrl(net) + "/spot/v3/public/quote/ticker/24hr";

        try {
            ResponseEntity<String> r = http.getForEntity(url, String.class);
            JSONObject json = new JSONObject(r.getBody());
            JSONArray arr = json.optJSONArray("result");

            if (arr == null) return Collections.emptyMap();

            Map<String, JSONObject> out = new HashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                out.put(t.getString("symbol"), t);
            }

            return out;

        } catch (Exception e) {
            log.error("❌ Bybit ticker/24hr error: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> getBybitTimeframes() {
        return List.of("1", "3", "5", "15", "30", "60", "240", "D", "W", "M");
    }
}

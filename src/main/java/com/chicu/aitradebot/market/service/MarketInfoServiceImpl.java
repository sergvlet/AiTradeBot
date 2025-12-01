package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.dto.SymbolInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🧠 Реализация MarketInfoService с кэшированием,
 * чтобы поиск работал мгновенно и не дергал Binance 40 секунд.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketInfoServiceImpl implements MarketInfoService {

    /** Все провайдеры (Binance, Bybit...) */
    private final List<MarketInfoProvider> providers;

    /** Кеш overview → обновляем раз в 5 секунд */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL = 5000; // 5 секунд

    private record CacheEntry(MarketOverviewDto dto, long ts) {}

    private MarketInfoProvider getProviderOrThrow(String exchange) {
        return providers.stream()
                .filter(p -> p.supports(exchange))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Нет провайдера рынка для биржи: " + exchange
                ));
    }

    // --------------------------------------------------------------------
    // OVERVIEW + CACHE
    // --------------------------------------------------------------------
    @Override
    public MarketOverviewDto getOverview(String exchange, NetworkType network) {

        String key = exchange + "_" + network;

        CacheEntry entry = cache.get(key);

        long now = System.currentTimeMillis();

        // ♻ берём из кеша, если не старый
        if (entry != null && now - entry.ts < CACHE_TTL) {
            return entry.dto;
        }

        try {
            MarketInfoProvider provider = getProviderOrThrow(exchange);

            log.debug("📊 [MARKET] refresh overview {} @ {}", exchange, network);

            MarketOverviewDto dto = provider.getOverview(network);

            cache.put(key, new CacheEntry(dto, now));

            return dto;

        } catch (Exception e) {
            log.error("❌ Ошибка getOverview: {}", e.getMessage());

            // ⚠ Если ошибка — отдаём старые данные, если есть
            if (entry != null) {
                return entry.dto;
            }

            // иначе пустой объект
            return MarketOverviewDto.builder()
                    .symbols(List.of())
                    .timeframes(List.of())
                    .lastUpdate(now)
                    .build();
        }
    }

    // --------------------------------------------------------------------
    // FAST SEARCH (мгновенно, без запроса на Binance)
    // --------------------------------------------------------------------
    @Override
    public List<SymbolInfoDto> searchSymbols(String exchange,
                                             NetworkType network,
                                             String query) {

        MarketOverviewDto data = getOverview(exchange, network);

        if (query == null || query.isBlank()) {
            return data.getSymbols().stream()
                    .limit(20)
                    .toList();
        }

        String q = query.trim().toUpperCase();

        return data.getSymbols().stream()
                .filter(s -> {
                    String sym = s.getSymbol();
                    return sym != null && sym.toUpperCase().contains(q);
                })
                .sorted(Comparator.comparing(SymbolInfoDto::getVolume).reversed())
                .limit(20)
                .toList();
    }

    // --------------------------------------------------------------------
    // SYMBOL INFO (быстро, из кеша)
    // --------------------------------------------------------------------
    @Override
    public SymbolInfoDto getSymbolInfo(String exchange,
                                       NetworkType network,
                                       String symbol) {

        MarketOverviewDto data = getOverview(exchange, network);

        return data.getSymbols().stream()
                .filter(s -> s.getSymbol().equalsIgnoreCase(symbol))
                .findFirst()
                .orElse(SymbolInfoDto.builder()
                        .symbol(symbol)
                        .status("UNKNOWN")
                        .price(0)
                        .volume(0)
                        .changePct(0)
                        .build());
    }
}

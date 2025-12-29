package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketSymbolServiceImpl implements MarketSymbolService {

    private final ExchangeClientFactory exchangeClientFactory;

    // ⏱ cache на 10 минут
    private static final long CACHE_TTL_MS = 10 * 60_000;

    // 📦 exchange|network|asset -> symbols
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public List<SymbolDescriptor> getSymbols(
            String exchange,
            NetworkType network,
            String accountAsset,
            SymbolListMode mode
    ) {

        String key = exchange + "|" + network + "|" + accountAsset;

        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return applyMode(cached.data(), mode);
        }

        ExchangeClient client = exchangeClientFactory.get(exchange, network);
        List<SymbolDescriptor> list = client.getTradableSymbols(accountAsset);

        cache.put(key, new CacheEntry(list));

        return applyMode(list, mode);
    }

    // =====================================================================
    // 🔀 MODE SORTING
    // =====================================================================
    private List<SymbolDescriptor> applyMode(
            List<SymbolDescriptor> list,
            SymbolListMode mode
    ) {

        SymbolListMode safeMode =
                mode != null ? mode : SymbolListMode.POPULAR;

        return switch (safeMode) {

            case GAINERS -> list.stream()
                    .sorted(Comparator.comparing(
                            SymbolDescriptor::priceChangePct24h,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ).reversed())
                    .toList();

            case LOSERS -> list.stream()
                    .sorted(Comparator.comparing(
                            SymbolDescriptor::priceChangePct24h,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
                    .toList();

            case VOLUME, POPULAR -> list.stream()
                    .sorted(Comparator.comparing(
                            SymbolDescriptor::volume24h,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ).reversed())
                    .toList();

            case ALL -> list;
        };
    }

    // =====================================================================
    // 📦 CACHE ENTRY
    // =====================================================================
    private record CacheEntry(
            List<SymbolDescriptor> data,
            long createdAt
    ) {
        CacheEntry(List<SymbolDescriptor> data) {
            this(data, System.currentTimeMillis());
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }

    @Override
    public SymbolDescriptor getSymbolInfo(
            String exchange,
            NetworkType network,
            String accountAsset,
            String symbol
    ) {
        if (symbol == null || symbol.isBlank()) return null;

        List<SymbolDescriptor> list =
                getSymbols(exchange, network, accountAsset, SymbolListMode.POPULAR);

        return list.stream()
                .filter(s -> symbol.equalsIgnoreCase(s.symbol()))
                .findFirst()
                .orElse(null);
    }
}

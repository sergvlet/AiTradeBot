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
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;

    // 🧯 чтобы не спамить WARN (на один key раз в 30 сек)
    private static final long WARN_COOLDOWN_MS = 30_000L;

    // 📦 key -> symbols (храним сырые данные, сортируем по mode)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // 🧯 key -> lastWarnAt
    private final Map<String, Long> warnCooldown = new ConcurrentHashMap<>();

    @Override
    public List<SymbolDescriptor> getSymbols(
            String exchange,
            NetworkType network,
            String accountAsset,
            SymbolListMode mode
    ) {

        // ===== safe defaults =====
        String safeExchange = (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase();

        NetworkType safeNetwork = (network != null ? network : NetworkType.MAINNET);

        SymbolListMode safeMode = (mode != null ? mode : SymbolListMode.POPULAR);

        String safeAsset = (accountAsset == null || accountAsset.isBlank())
                ? "USDT"
                : accountAsset.trim().toUpperCase();

        String key = safeExchange + "|" + safeNetwork + "|" + safeAsset;

        // 1) свежий кэш
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return applyMode(cached.data(), safeMode);
        }

        // 2) пробуем обновить с биржи
        try {
            ExchangeClient client = exchangeClientFactory.get(safeExchange, safeNetwork);

            List<SymbolDescriptor> list = client.getTradableSymbols(safeAsset);

            // ✅ не кешируем null
            if (list != null) {

                // ✅ если биржа вернула пусто — НЕ затираем старый кэш (если он был)
                // чтобы UI не схлопывался из-за временного сбоя.
                if (!list.isEmpty()) {
                    cache.put(key, new CacheEntry(list));
                    return applyMode(list, safeMode);
                }

                if (cached != null && cached.data() != null && !cached.data().isEmpty()) {
                    warnOnce(key, "⚠️ symbols пустые (fallback на старый кеш): exchange={} network={} asset={}",
                            safeExchange, safeNetwork, safeAsset);
                    return applyMode(cached.data(), safeMode);
                }

                // пусто и кэша нет — отдаём пусто
                warnOnce(key, "⚠️ symbols пустые (кэша нет): exchange={} network={} asset={}",
                        safeExchange, safeNetwork, safeAsset);
                cache.put(key, new CacheEntry(List.of())); // можно кешировать пусто, но это не критично
                return List.of();
            }

            // null — странно, fallback
            warnOnce(key, "⚠️ getTradableSymbols вернул null: exchange={} network={} asset={}",
                    safeExchange, safeNetwork, safeAsset);

        } catch (Exception e) {
            // ✅ не ломаем UI
            warnOnce(key, "⚠️ Не удалось получить symbols: exchange={} network={} asset={} mode={} err={}",
                    safeExchange, safeNetwork, safeAsset, safeMode, e.toString());
        }

        // 3) fallback: старый кэш даже если TTL истёк
        if (cached != null && cached.data() != null && !cached.data().isEmpty()) {
            return applyMode(cached.data(), safeMode);
        }

        return List.of();
    }

    // =====================================================================
    // 🔀 MODE SORTING
    // =====================================================================
    private List<SymbolDescriptor> applyMode(List<SymbolDescriptor> list, SymbolListMode mode) {
        if (list == null || list.isEmpty()) return List.of();

        return switch (mode) {

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
    private record CacheEntry(List<SymbolDescriptor> data, long createdAt) {
        CacheEntry(List<SymbolDescriptor> data) {
            this(data, System.currentTimeMillis());
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }

    // =====================================================================
    // 🧯 WARN COOLDOWN
    // =====================================================================
    private void warnOnce(String key, String pattern, Object... args) {
        long now = System.currentTimeMillis();
        Long last = warnCooldown.get(key);
        if (last != null && now - last < WARN_COOLDOWN_MS) {
            return;
        }
        warnCooldown.put(key, now);
        log.warn(pattern, args);
    }

    @Override
    public SymbolDescriptor getSymbolInfo(
            String exchange,
            NetworkType network,
            String accountAsset,
            String symbol
    ) {
        if (symbol == null || symbol.isBlank()) return null;

        List<SymbolDescriptor> list = getSymbols(exchange, network, accountAsset, SymbolListMode.ALL);

        String s = symbol.trim();
        return list.stream()
                .filter(it -> it != null && it.symbol() != null && it.symbol().equalsIgnoreCase(s))
                .findFirst()
                .orElse(null);
    }
}

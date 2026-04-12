package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.market.model.SymbolListMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketSymbolServiceImpl implements MarketSymbolService {

    private final ExchangeClientFactory exchangeClientFactory;

    // ⏱ cache на 10 минут (нормальный ответ)
    private static final long CACHE_TTL_OK_MS = 10L * 60L * 1000L;

    // ⏱ cache на 20 секунд (пустой ответ, чтобы UI не спамил биржу)
    private static final long CACHE_TTL_EMPTY_MS = 20_000L;

    // 🧯 чтобы не спамить WARN (на один key раз в 30 сек)
    private static final long WARN_COOLDOWN_MS = 30_000L;

    // 📦 key -> entry
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
        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null ? network : NetworkType.TESTNET); // ✅ важный фикс
        SymbolListMode m = (mode != null ? mode : SymbolListMode.POPULAR);
        String asset = normalizeAsset(accountAsset);

        String key = ex + "|" + net.name() + "|" + asset;

        // 1) свежий кэш
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return applyMode(cached.dataList(), m);
        }

        // 2) обновить с биржи
        try {
            ExchangeClient client = exchangeClientFactory.get(ex, net);

            // ⚠️ ВАЖНО: Bybit может вернуть пусто если getTradableSymbols не учитывает market/category.
            List<SymbolDescriptor> list = client.getTradableSymbols(asset);
            list = (list == null) ? nullSafeEmpty() : list;

            // если получили НЕ пусто — кешируем как OK
            if (!list.isEmpty()) {
                cache.put(key, CacheEntry.ok(list));
                return applyMode(list, m);
            }

            // если биржа вернула пусто:
            //  - если есть старый кэш — НЕ затираем, отдаём старый (лучше чем UI=пусто)
            if (cached != null && cached.hasData()) {
                warnOnce(key,
                        "⚠️ symbols пустые (fallback на кеш): ex={} net={} asset={} mode={}",
                        ex, net, asset, m);
                return applyMode(cached.dataList(), m);
            }

            //  - если кэша нет — кешируем пусто на короткий TTL
            warnOnce(key,
                    "⚠️ symbols пустые (кэша нет): ex={} net={} asset={} mode={}",
                    ex, net, asset, m);
            cache.put(key, CacheEntry.empty());
            return List.of();

        } catch (Exception e) {
            warnOnce(key,
                    "⚠️ Не удалось получить symbols: ex={} net={} asset={} mode={} err={}",
                    ex, net, asset, m, e.toString());
        }

        // 3) fallback: старый кэш даже если TTL истёк
        if (cached != null && cached.hasData()) {
            return applyMode(cached.dataList(), m);
        }

        return List.of();
    }

    @Override
    public SymbolDescriptor getSymbolInfo(
            String exchange,
            NetworkType network,
            String accountAsset,
            String symbol
    ) {
        String sym = normalizeUpperOrNull(symbol);
        if (sym == null) return null;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null ? network : NetworkType.TESTNET);
        String asset = normalizeAsset(accountAsset);

        String key = ex + "|" + net.name() + "|" + asset;

        // сначала пробуем из кеша (даже просроченного — лучше чем “—”)
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.bySymbol() != null) {
            SymbolDescriptor hit = cached.bySymbol().get(sym);
            if (hit != null) return hit;
        }

        // иначе — подгружаем ALL (без сортировок), и ищем
        List<SymbolDescriptor> list = getSymbols(ex, net, asset, SymbolListMode.ALL);
        if (list.isEmpty()) return null;

        // в кеше уже собрана bySymbol, можно взять ещё раз
        CacheEntry cached2 = cache.get(key);
        if (cached2 != null && cached2.bySymbol() != null) {
            return cached2.bySymbol().get(sym);
        }

        // fallback
        for (SymbolDescriptor d : list) {
            if (d != null && d.symbol() != null && d.symbol().equalsIgnoreCase(sym)) {
                return d;
            }
        }
        return null;
    }

    // =====================================================================
    // 🔀 MODE SORTING
    // =====================================================================
    private List<SymbolDescriptor> applyMode(List<SymbolDescriptor> list, SymbolListMode mode) {
        if (list == null || list.isEmpty()) return List.of();
        if (mode == null || mode == SymbolListMode.ALL) return list;

        // сортируем копию (чтобы не мутировать исходный list из кеша)
        List<SymbolDescriptor> copy = new ArrayList<>(list);

        return switch (mode) {
            case GAINERS -> {
                copy.sort(Comparator.comparing(
                        SymbolDescriptor::priceChangePct24h,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).reversed());
                yield copy;
            }
            case LOSERS -> {
                copy.sort(Comparator.comparing(
                        SymbolDescriptor::priceChangePct24h,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ));
                yield copy;
            }
            case VOLUME, POPULAR -> {
                copy.sort(Comparator.comparing(
                        SymbolDescriptor::volume24h,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).reversed());
                yield copy;
            }
            case ALL -> copy;
        };
    }

    // =====================================================================
    // 📦 CACHE ENTRY
    // =====================================================================
    private record CacheEntry(
            List<SymbolDescriptor> dataList,
            Map<String, SymbolDescriptor> bySymbol,
            long createdAt,
            long ttlMs
    ) {
        static CacheEntry ok(List<SymbolDescriptor> list) {
            return new CacheEntry(
                    List.copyOf(list),
                    buildBySymbol(list),
                    System.currentTimeMillis(),
                    CACHE_TTL_OK_MS
            );
        }

        static CacheEntry empty() {
            return new CacheEntry(
                    List.of(),
                    Map.of(),
                    System.currentTimeMillis(),
                    CACHE_TTL_EMPTY_MS
            );
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > ttlMs;
        }

        boolean hasData() {
            return dataList != null && !dataList.isEmpty();
        }

        private static Map<String, SymbolDescriptor> buildBySymbol(List<SymbolDescriptor> list) {
            if (list == null || list.isEmpty()) return Map.of();
            Map<String, SymbolDescriptor> m = new HashMap<>();
            for (SymbolDescriptor d : list) {
                if (d == null || d.symbol() == null) continue;
                m.put(d.symbol().trim().toUpperCase(Locale.ROOT), d);
            }
            return m;
        }
    }

    // =====================================================================
    // 🧯 WARN COOLDOWN
    // =====================================================================
    private void warnOnce(String key, String pattern, Object... args) {
        long now = System.currentTimeMillis();
        Long last = warnCooldown.get(key);
        if (last != null && now - last < WARN_COOLDOWN_MS) return;
        warnCooldown.put(key, now);
        log.warn(pattern, args);
    }

    // =====================================================================
    // helpers
    // =====================================================================
    private static String normalizeExchange(String exchange) {
        if (exchange == null || exchange.isBlank()) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    private static String normalizeAsset(String accountAsset) {
        if (accountAsset == null || accountAsset.isBlank()) return "USDT";
        String a = accountAsset.trim().toUpperCase(Locale.ROOT);
        return a.isEmpty() ? "USDT" : a;
    }

    private static String normalizeUpperOrNull(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static List<SymbolDescriptor> nullSafeEmpty() {
        return List.of();
    }
}

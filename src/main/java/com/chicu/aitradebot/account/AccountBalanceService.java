package com.chicu.aitradebot.account;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClient.Balance;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.AccountFees;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountBalanceService {

    private static final String PREFERRED_ASSET = "USDT";

    private final StrategySettingsService strategySettingsService;
    private final ExchangeClientFactory exchangeClientFactory;

    @Value("${trade.balance-cache.ttl-ms:5000}")
    private long balanceCacheTtlMs;

    @Value("${trade.balance-snapshot-log-throttle-ms:300000}")
    private long balanceSnapshotLogThrottleMs;

    private final Map<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> snapshotLogTimes = new ConcurrentHashMap<>();

    private record CachedSnapshot(AccountBalanceSnapshot snapshot, Instant expiresAt) {}

    public AccountBalanceSnapshot getSnapshot(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    ) {
        return getSnapshot(chatId, type, exchangeName, networkType, null);
    }

    public AccountBalanceSnapshot getSnapshot(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType,
            String selectedAssetHint
    ) {
        String ex = normalize(exchangeName);
        NetworkType net;
        net = networkType;
        String selectedHint = normalize(selectedAssetHint);

        String cacheKey = cacheKey(chatId, type, ex, net, selectedHint);
        AccountBalanceSnapshot cached = getCachedSnapshot(cacheKey);
        if (cached != null) {
            return cached;
        }

        StrategySettings settings = null;
        try {
            settings = strategySettingsService.getOrCreateAndPatchContext(chatId, type, ex, net);
        } catch (Exception e) {
            log.warn("⚠️ Не удалось загрузить StrategySettings (chatId={}, type={}, ex={}, net={}): {}",
                    chatId, type, ex, net, e.toString());
        }

        String selectedFromSettings = settings != null ? normalize(settings.getAccountAsset()) : null;
        String selectedFallback = firstNonBlank(selectedHint, selectedFromSettings);

        if (ex == null) {
            return buildErrorSnapshot(
                    selectedFallback,
                    "exchangeName не задан"
            );
        }

        if (net == null) {
            return buildErrorSnapshot(
                    selectedFallback,
                    "networkType не задан"
            );
        }

        try {
            ExchangeClient client = exchangeClientFactory.get(ex, net);

            Map<String, Balance> full = safeMap(client.getFullBalance(chatId, net));
            Map<String, Balance> normalized = normalizeBalanceMap(full);

            if (normalized.isEmpty()) {
                log.warn("⚠️ Пустой баланс от биржи (chatId={}, type={}, ex={}, net={})",
                        chatId, type, ex, net);

                String msg = "Пустой баланс от биржи";
                if ("BYBIT".equals(ex)) {
                    msg = "BYBIT вернул пустой баланс. Проверь API key, права Wallet/Account Transfer и тип аккаунта UNIFIED/SPOT.";
                }

                return buildErrorSnapshot(selectedFallback, msg);
            }

            Map<String, AccountBalanceSnapshot.AssetBalance> balances = new LinkedHashMap<>();
            for (Map.Entry<String, Balance> e : normalized.entrySet()) {
                String asset = normalize(e.getKey());
                Balance b = e.getValue();
                if (asset == null || b == null) continue;

                balances.put(asset, AccountBalanceSnapshot.AssetBalance.of(
                        bdFromDouble(b.free()),
                        bdFromDouble(b.locked())
                ));
            }

            List<String> availableAssets = new ArrayList<>(balances.keySet());
            availableAssets.sort(String.CASE_INSENSITIVE_ORDER);

            if (availableAssets.remove(PREFERRED_ASSET)) {
                availableAssets.addFirst(PREFERRED_ASSET);
            }

            String selected = firstNonBlank(selectedHint, selectedFromSettings);
            boolean changed = false;

            if (selected == null || !balances.containsKey(selected)) {
                selected = pickDefaultAsset(normalized, availableAssets);
                changed = !Objects.equals(selected, selectedFromSettings);
            }

            if (changed && settings != null && selected != null) {
                settings.setAccountAsset(selected);
                strategySettingsService.save(settings);
                log.info("💰 accountAsset синхронизирован: chatId={}, type={}, ex={}, net={}, asset={}",
                        chatId, type, ex, net, selected);
            }

            AccountBalanceSnapshot.AssetBalance selectedBalance = balances.get(selected);

            String snapshotLogKey = chatId + "|" + type + "|" + ex + "|" + net + "|" + selected;
            if (shouldLogSnapshot(snapshotLogKey)) {
                log.info("💰 SNAPSHOT chatId={} type={} ex={} net={} selected={} free={} locked={} total={} assets={}",
                        chatId,
                        type,
                        ex,
                        net,
                        selected,
                        selectedBalance != null ? selectedBalance.getFreeSafe() : BigDecimal.ZERO,
                        selectedBalance != null ? selectedBalance.getLockedSafe() : BigDecimal.ZERO,
                        selectedBalance != null ? selectedBalance.getTotalSafe() : BigDecimal.ZERO,
                        availableAssets);
            }

            AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.builder()
                    .availableAssets(availableAssets)
                    .selectedAsset(selected)
                    .selectedBalance(selectedBalance)
                    .balances(Collections.unmodifiableMap(balances))
                    .connectionOk(true)
                    .error(null)
                    .build();

            cacheSnapshot(cacheKey, snapshot);
            return snapshot;

        } catch (Exception exx) {
            log.warn("⚠️ Не удалось получить баланс (chatId={}, type={}, ex={}, net={}): {}",
                    chatId, type, ex, net, exx.toString());

            String msg = exx.getMessage();
            if (msg == null || msg.isBlank()) msg = exx.toString();

            AccountBalanceSnapshot snapshot = buildErrorSnapshot(selectedFallback, msg);
            cacheSnapshot(cacheKey, snapshot);
            return snapshot;
        }
    }


    private String cacheKey(long chatId,
                            StrategyType type,
                            String exchangeName,
                            NetworkType networkType,
                            String selectedAssetHint) {
        return chatId + ":"
                + (type != null ? type.name() : "NA") + ":"
                + (exchangeName != null ? exchangeName : "NA") + ":"
                + (networkType != null ? networkType.name() : "NA") + ":"
                + (selectedAssetHint != null ? selectedAssetHint : "NA");
    }

    private AccountBalanceSnapshot getCachedSnapshot(String key) {
        if (key == null) return null;
        CachedSnapshot cached = snapshotCache.get(key);
        if (cached == null || cached.snapshot() == null || cached.expiresAt() == null) return null;
        if (Instant.now().isAfter(cached.expiresAt())) {
            snapshotCache.remove(key);
            return null;
        }
        return cached.snapshot();
    }

    private void cacheSnapshot(String key, AccountBalanceSnapshot snapshot) {
        if (key == null || snapshot == null) return;
        long ttlMs = Math.max(250L, balanceCacheTtlMs);
        snapshotCache.put(key, new CachedSnapshot(snapshot, Instant.now().plusMillis(ttlMs)));
    }

    public AccountFees getAccountFees(long chatId, String exchangeName, NetworkType networkType) {
        String ex = normalize(exchangeName);
        NetworkType net;
        net = networkType;

        if (ex == null || net == null) {
            return null;
        }

        try {
            ExchangeClient client = exchangeClientFactory.get(ex, net);
            return client.getAccountFees(chatId, net);
        } catch (Exception e) {
            log.warn("⚠️ Не удалось получить AccountFees (chatId={}, ex={}, net={}): {}",
                    chatId, ex, net, e.toString());
            return null;
        }
    }

    private boolean shouldLogSnapshot(String key) {
        if (key == null || key.isBlank()) return true;
        Instant now = Instant.now();
        Instant prev = snapshotLogTimes.get(key);
        long throttleMs = Math.max(5_000L, balanceSnapshotLogThrottleMs);
        if (prev != null) {
            long ageMs = java.time.Duration.between(prev, now).toMillis();
            if (ageMs >= 0 && ageMs < throttleMs) {
                return false;
            }
        }
        snapshotLogTimes.put(key, now);
        return true;
    }

    private AccountBalanceSnapshot buildErrorSnapshot(String selectedAsset, String error) {
        String selected = normalize(selectedAsset);

        Map<String, AccountBalanceSnapshot.AssetBalance> balances =
                selected != null
                        ? Map.of(selected, AccountBalanceSnapshot.AssetBalance.of(BigDecimal.ZERO, BigDecimal.ZERO))
                        : Map.of();

        return AccountBalanceSnapshot.builder()
                .availableAssets(selected != null ? List.of(selected) : List.of())
                .selectedAsset(selected)
                .selectedBalance(selected != null
                        ? AccountBalanceSnapshot.AssetBalance.of(BigDecimal.ZERO, BigDecimal.ZERO)
                        : null)
                .balances(balances)
                .connectionOk(false)
                .error(error)
                .build();
    }

    private Map<String, Balance> normalizeBalanceMap(Map<String, Balance> full) {
        if (full == null || full.isEmpty()) {
            return Collections.emptyMap();
        }

        return full.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .filter(e -> e.getValue() != null)
                .map(e -> Map.entry(normalize(e.getKey()), sanitizeBalance(e.getKey(), e.getValue())))
                .filter(e -> e.getKey() != null)
                .filter(e -> e.getValue() != null)
                .filter(e -> (e.getValue().free() + e.getValue().locked()) > 0.0d)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> new Balance(a.asset(), a.free() + b.free(), a.locked() + b.locked()),
                        LinkedHashMap::new
                ));
    }

    private Balance sanitizeBalance(String asset, Balance b) {
        if (b == null) return null;

        double free = sanitizeDouble(b.free());
        double locked = sanitizeDouble(b.locked());

        if (free < 0) free = 0.0d;
        if (locked < 0) locked = 0.0d;

        return new Balance(normalize(asset), free, locked);
    }

    private double sanitizeDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0d;
        return v;
    }

    private String pickDefaultAsset(Map<String, Balance> positiveTotal, List<String> availableAssets) {
        if (positiveTotal.containsKey(PREFERRED_ASSET)) {
            return PREFERRED_ASSET;
        }

        String best = null;
        double bestTotal = -1.0d;

        for (Map.Entry<String, Balance> e : positiveTotal.entrySet()) {
            Balance b = e.getValue();
            if (b == null) continue;

            double total = b.free() + b.locked();
            if (total > bestTotal) {
                bestTotal = total;
                best = e.getKey();
            }
        }

        if (best != null) return best;
        return availableAssets.isEmpty() ? null : availableAssets.getFirst();
    }

    private String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String a, String b) {
        String x = normalize(a);
        if (x != null) return x;
        return normalize(b);
    }

    private <T> Map<String, T> safeMap(Map<String, T> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }

    private BigDecimal bdFromDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }
}




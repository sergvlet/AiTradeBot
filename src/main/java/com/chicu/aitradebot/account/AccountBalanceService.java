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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountBalanceService {

    private static final String PREFERRED_ASSET = "USDT";

    private final StrategySettingsService strategySettingsService;
    private final ExchangeClientFactory exchangeClientFactory;

    public AccountBalanceSnapshot getSnapshot(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    ) {
        String ex = normalize(exchangeName);
        NetworkType net = networkType;

        // ✅ StrategySettings: 1 строка на (chatId,type) + патчим контекст
        StrategySettings settings = null;
        try {
            settings = strategySettingsService.getOrCreateAndPatchContext(chatId, type, ex, net);
        } catch (Exception e) {
            log.warn("⚠️ Не удалось загрузить StrategySettings (chatId={}, type={}, ex={}, net={}): {}",
                    chatId, type, ex, net, e.toString());
        }

        // если контекст не передан — не идём в биржу
        if (ex == null) {
            return AccountBalanceSnapshot.builder()
                    .availableAssets(List.of())
                    .selectedAsset(settings != null ? normalize(settings.getAccountAsset()) : null)
                    .selectedBalance(null)
                    .connectionOk(false)
                    .error("exchangeName не задан")
                    .build();
        }
        if (net == null) {
            return AccountBalanceSnapshot.builder()
                    .availableAssets(List.of())
                    .selectedAsset(settings != null ? normalize(settings.getAccountAsset()) : null)
                    .selectedBalance(null)
                    .connectionOk(false)
                    .error("networkType не задан")
                    .build();
        }

        try {
            ExchangeClient client = exchangeClientFactory.get(ex, net);

            Map<String, Balance> full = safeMap(client.getFullBalance(chatId, net));

            Map<String, Balance> positiveTotal = full.entrySet().stream()
                    .filter(e -> e.getKey() != null)
                    .filter(e -> e.getValue() != null)
                    .filter(e -> (e.getValue().free() + e.getValue().locked()) > 0.0d)
                    .collect(Collectors.toMap(
                            e -> normalize(e.getKey()),
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            List<String> availableAssets = new ArrayList<>(positiveTotal.keySet());
            availableAssets.sort(String.CASE_INSENSITIVE_ORDER);

            // USDT — первый в списке (если есть)
            if (availableAssets.remove(PREFERRED_ASSET)) {
                availableAssets.add(0, PREFERRED_ASSET);
            }

            String selected = (settings != null) ? normalize(settings.getAccountAsset()) : null;

            if (availableAssets.isEmpty()) {
                if (settings != null && selected != null) {
                    settings.setAccountAsset(null);
                    strategySettingsService.save(settings);
                }

                return AccountBalanceSnapshot.builder()
                        .availableAssets(List.of())
                        .selectedAsset(null)
                        .selectedBalance(null)
                        .connectionOk(true)
                        .build();
            }

            boolean changed = false;
            if (selected == null || !positiveTotal.containsKey(selected)) {
                selected = pickDefaultAsset(positiveTotal, availableAssets);
                changed = true;
            }

            if (changed && settings != null) {
                settings.setAccountAsset(selected);
                strategySettingsService.save(settings);
                log.info("💰 accountAsset синхронизирован: chatId={}, type={}, ex={}, net={}, asset={}",
                        chatId, type, ex, net, selected);
            }

            Balance b = positiveTotal.get(selected);

            BigDecimal free = bdFromDouble(b != null ? b.free() : 0.0d);
            BigDecimal locked = bdFromDouble(b != null ? b.locked() : 0.0d);

            return AccountBalanceSnapshot.builder()
                    .availableAssets(availableAssets)
                    .selectedAsset(selected)
                    .selectedBalance(AccountBalanceSnapshot.AssetBalance.of(free, locked))
                    .connectionOk(true)
                    .build();

        } catch (Exception exx) {
            String selectedFallback = (settings != null) ? normalize(settings.getAccountAsset()) : null;

            log.warn("⚠️ Не удалось получить баланс (chatId={}, type={}, ex={}, net={}): {}",
                    chatId, type, ex, net, exx.toString());

            String msg = exx.getMessage();
            if (msg == null || msg.isBlank()) msg = exx.toString();

            return AccountBalanceSnapshot.builder()
                    .availableAssets(List.of())
                    .selectedAsset(selectedFallback)
                    .selectedBalance(null)
                    .connectionOk(false)
                    .error(msg)
                    .build();
        }
    }

    public AccountFees getAccountFees(long chatId, String exchangeName, NetworkType networkType) {
        String ex = normalize(exchangeName);
        NetworkType net = networkType;

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

        // крайний fallback
        return availableAssets.get(0);
    }

    private String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private <T> Map<String, T> safeMap(Map<String, T> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }

    private BigDecimal bdFromDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }
}
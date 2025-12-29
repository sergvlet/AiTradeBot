package com.chicu.aitradebot.account;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.client.ExchangeClient.Balance;
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

    private final StrategySettingsService strategySettingsService;
    private final ExchangeClientFactory exchangeClientFactory;

    public AccountBalanceSnapshot getSnapshot(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    ) {

        StrategySettings settings =
                strategySettingsService.getOrCreate(chatId, type, exchangeName, networkType);

        try {
            // ✅ корректная сигнатура
            ExchangeClient client =
                    exchangeClientFactory.get(exchangeName, networkType);

            // ✅ реальный тип данных
            Map<String, Balance> full =
                    safeMap(client.getFullBalance(chatId, networkType));

            // 1) только free > 0
            Map<String, Balance> positiveFree = full.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().free() > 0.0)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            List<String> availableAssets = positiveFree.keySet().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();

            String selected = normalize(settings.getAccountAsset());

            if (availableAssets.isEmpty()) {
                if (selected != null) {
                    settings.setAccountAsset(null);
                    strategySettingsService.save(settings);
                }
                return AccountBalanceSnapshot.builder()
                        .availableAssets(List.of())
                        .selectedAsset(null)
                        .selectedFreeBalance(null)
                        .connectionOk(true)
                        .build();
            }

            boolean changed = false;
            if (selected == null || !positiveFree.containsKey(selected)) {
                selected = availableAssets.getFirst();
                settings.setAccountAsset(selected);
                changed = true;
            }

            if (changed) {
                strategySettingsService.save(settings);
                log.info("💰 accountAsset синхронизирован: chatId={}, type={}, asset={}",
                        chatId, type, selected);
            }

            BigDecimal selectedFree =
                    BigDecimal.valueOf(positiveFree.get(selected).free());

            return AccountBalanceSnapshot.builder()
                    .availableAssets(availableAssets)
                    .selectedAsset(selected)
                    .selectedFreeBalance(selectedFree)
                    .connectionOk(true)
                    .build();

        } catch (Exception ex) {
            log.warn(
                    "⚠️ Не удалось получить баланс (chatId={}, type={}, exchange={}, network={}): {}",
                    chatId, type, exchangeName, networkType, ex.toString()
            );

            return AccountBalanceSnapshot.builder()
                    .availableAssets(List.of())
                    .selectedAsset(normalize(settings.getAccountAsset()))
                    .selectedFreeBalance(null)
                    .connectionOk(false)
                    .error(ex.getMessage())
                    .build();
        }
    }

    public AccountFees getAccountFees(long chatId, String exchangeName, NetworkType networkType) {
        ExchangeClient client = exchangeClientFactory.get(exchangeName, networkType);
        return client.getAccountFees(chatId, networkType);
    }



    // =====================================================================
    // helpers
    // =====================================================================

    private String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private <T> Map<String, T> safeMap(Map<String, T> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }


}

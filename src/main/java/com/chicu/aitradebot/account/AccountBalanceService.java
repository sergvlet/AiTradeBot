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

    /**
     * Источник истины:
     * - networkType/exchangeName приходят СВЕРХУ (из StrategySettings / UI / оркестратора).
     * - баланс берём С БИРЖИ, строго через клиента (exchange + network).
     * - выбранный asset (accountAsset) хранится В БАЗЕ в StrategySettings и синхронизируется с тем,
     *   что реально доступно на бирже.
     */
    public AccountBalanceSnapshot getSnapshot(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    ) {
        String ex = normalize(exchangeName);
        NetworkType net = networkType;

        // ✅ StrategySettings берём/создаём строго в разрезе (chatId, type, exchange, network)
        StrategySettings settings = null;
        try {
            settings = strategySettingsService.getOrCreate(chatId, type, ex, net);
        } catch (Exception e) {
            // если settings не удалось получить — всё равно попробуем баланс (биржа может быть доступна)
            log.warn("⚠️ Не удалось загрузить StrategySettings (chatId={}, type={}, ex={}, net={}): {}",
                    chatId, type, ex, net, e.toString());
        }

        // ✅ дальше всё — с биржи, строго в контексте (exchange, network)
        try {
            ExchangeClient client = exchangeClientFactory.get(ex, net);

            Map<String, Balance> full = safeMap(client.getFullBalance(chatId, net));

            // ✅ оставляем только те активы, где total > 0 (free + locked)
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

            List<String> availableAssets = positiveTotal.keySet().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();

            String selected = (settings != null) ? normalize(settings.getAccountAsset()) : null;

            // ✅ если на бирже нет положительных балансов
            if (availableAssets.isEmpty()) {
                // в базе очищаем только если settings реально есть
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

            // ✅ если выбранный актив отсутствует на бирже — выбираем первый из доступных
            boolean changed = false;
            if (selected == null || !positiveTotal.containsKey(selected)) {
                selected = availableAssets.getFirst();
                changed = true;
            }

            // ✅ сохраняем выбранный актив в БД только если settings существует
            if (changed && settings != null) {
                settings.setAccountAsset(selected);
                strategySettingsService.save(settings);
                log.info("💰 accountAsset синхронизирован: chatId={}, type={}, ex={}, net={}, asset={}",
                        chatId, type, ex, net, selected);
            }

            Balance b = positiveTotal.get(selected);

            // ✅ переводим из double в BigDecimal безопасно
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

            return AccountBalanceSnapshot.builder()
                    .availableAssets(List.of())
                    .selectedAsset(selectedFallback)
                    .selectedBalance(null)
                    .connectionOk(false)
                    .error(exx.getMessage())
                    .build();
        }
    }

    public AccountFees getAccountFees(long chatId, String exchangeName, NetworkType networkType) {
        String ex = normalize(exchangeName);
        NetworkType net = networkType;
        ExchangeClient client = exchangeClientFactory.get(ex, net);
        return client.getAccountFees(chatId, net);
    }

    // =====================================================
    // helpers
    // =====================================================

    private String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private <T> Map<String, T> safeMap(Map<String, T> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }

    /**
     * BigDecimal.valueOf(double) использует Double.toString(double) и обычно безопаснее, чем new BigDecimal(double),
     * но на биржевых балансах могут прилетать NaN/Inf — страхуемся.
     */
    private BigDecimal bdFromDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(v);
    }
}

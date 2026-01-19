package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.web.facade.WebBalanceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebBalanceFacadeImpl implements WebBalanceFacade {

    private final ExchangeClientFactory clientFactory;
    private final ExchangeSettingsService settingsService;

    @Override
    public BalanceInfo getTotalBalance(Long chatId) {

        try {
            // ⭐ Получаем активные настройки exchange + network
            ExchangeSettings es = settingsService.findAllByChatId(chatId)
                    .stream()

                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Нет активных настроек биржи для chatId=" + chatId));

            ExchangeClient client = clientFactory.get(es.getExchange(), es.getNetwork());
            Map<String, ExchangeClient.Balance> balances =
                    client.getFullBalance(chatId, es.getNetwork());

            BigDecimal free = BigDecimal.ZERO;
            BigDecimal locked = BigDecimal.ZERO;

            for (ExchangeClient.Balance b : balances.values()) {
                free = free.add(BigDecimal.valueOf(b.free()));
                locked = locked.add(BigDecimal.valueOf(b.locked()));
            }

            return new BalanceInfo(
                    free.add(locked),
                    free,
                    locked
            );

        } catch (Exception e) {
            log.error("❌ Ошибка getTotalBalance chatId={}: {}", chatId, e.getMessage(), e);
            return new BalanceInfo(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    @Override
    public List<AssetBalance> getAssets(Long chatId) {

        try {
            ExchangeSettings es = settingsService.findAllByChatId(chatId)
                    .stream()

                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Нет активных exchange settings chatId=" + chatId));

            ExchangeClient client = clientFactory.get(es.getExchange(), es.getNetwork());

            Map<String, ExchangeClient.Balance> map =
                    client.getFullBalance(chatId, es.getNetwork());

            List<AssetBalance> result = new ArrayList<>();

            for (var entry : map.entrySet()) {
                var b = entry.getValue();
                result.add(new AssetBalance(
                        entry.getKey(),
                        BigDecimal.valueOf(b.free()),
                        BigDecimal.valueOf(b.locked())
                ));
            }

            return result;

        } catch (Exception e) {
            log.error("❌ Ошибка getAssets chatId={}: {}", chatId, e.getMessage(), e);
            return List.of();
        }
    }
}

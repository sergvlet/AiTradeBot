package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    @Override
    public StrategySettings save(StrategySettings s) {
        return repo.save(s);
    }

    @Override
    public List<StrategySettings> findAllByChatId(long chatId) {
        return repo.findByChatId(chatId);
    }

    @Override
    public StrategySettings getSettings(long chatId, StrategyType type) {

        List<StrategySettings> list = repo.findByChatIdAndType(chatId, type);

        if (list.isEmpty()) return null;

        if (list.size() > 1) {
            log.error("❌ НАЙДЕНО {} StrategySettings для chatId={} type={}. Лишние будут удалены.",
                    list.size(), chatId, type);

            StrategySettings keep = list.get(0);

            for (int i = 1; i < list.size(); i++) {
                repo.delete(list.get(i));
            }

            return keep;
        }

        return list.get(0);
    }

    @Override
    public StrategySettings getOrCreate(long chatId, StrategyType type) {

        StrategySettings existing = getSettings(chatId, type);
        if (existing != null) return existing;

        log.warn("⚠ Создаём новый StrategySettings (chatId={}, type={})", chatId, type);

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)

                .symbol("BTCUSDT")
                .timeframe("1m")
                .cachedCandlesLimit(500)

                .capitalUsd(BigDecimal.valueOf(100))
                .commissionPct(BigDecimal.valueOf(0.05))

                .takeProfitPct(BigDecimal.valueOf(1))
                .stopLossPct(BigDecimal.valueOf(1))
                .riskPerTradePct(BigDecimal.valueOf(1))
                .dailyLossLimitPct(BigDecimal.valueOf(20))

                .reinvestProfit(false)
                .leverage(1)
                .active(false)

                .build();

        return repo.save(s);
    }
}

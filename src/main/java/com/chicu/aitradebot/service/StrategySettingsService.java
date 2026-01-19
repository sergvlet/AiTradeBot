package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.util.List;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings s);

    // может вернуть null, если настроек ещё нет
    StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // гарантированно не null (и НЕ плодит записи из-за UNIQUE)
    StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    );

    List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange
    );

    void updateRiskFromUi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal dailyLossLimitPct,
            BigDecimal riskPerTradePct
    );

    void updateRiskFromAi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal newRiskPerTradePct
    );
}

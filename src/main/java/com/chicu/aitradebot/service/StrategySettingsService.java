package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface StrategySettingsService {

    // =====================================================================
    // SAVE
    // =====================================================================
    StrategySettings save(StrategySettings s);

    // =====================================================================
    // GET (может вернуть null, если настроек ещё нет)
    StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // =====================================================================
    // GET OR CREATE (ГАРАНТИРОВАНО не null)
    StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // =====================================================================
    // UI: список стратегий для Dashboard / Settings
    // exchange / network могут быть null (фильтр)
    List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    );

    // ✅ ✅ ✅ ДОБАВЛЕННЫЙ МЕТОД — БЕЗ NETWORK
    List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange
    );

    // =====================================================================
    // ЧИСТЫЙ метод — для orchestrator / runner
    Optional<StrategySettings> findLatest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // =====================================================================
    // RISK MANAGEMENT — UI
    void updateRiskFromUi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal dailyLossLimitPct,
            BigDecimal riskPerTradePct
    );

    // =====================================================================
    // RISK MANAGEMENT — AI
    void updateRiskFromAi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal newRiskPerTradePct
    );
}

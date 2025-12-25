package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;

import java.util.List;

public interface WebStrategyFacade {

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ (ДЛЯ КОНКРЕТНОЙ БИРЖИ / СЕТИ)
    // ================================================================
    List<StrategyUi> getStrategies(
            Long chatId,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // ▶️ START
    // ================================================================
    StrategyRunInfo start(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // ⏹ STOP
    // ================================================================
    StrategyRunInfo stop(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    StrategyRunInfo toggle(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // 🔁 TOGGLE + UPDATE PARAMS
    // ================================================================
    StrategyRunInfo toggleStrategy(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe
    );

    // ================================================================
    // ℹ STATUS
    // ================================================================
    StrategyRunInfo getRunInfo(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );
}

package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;

import java.util.List;

public interface WebStrategyFacade {

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ (UI / Dashboard)
    // ================================================================
    List<StrategyUi> getStrategies(
            Long chatId,
            String exchange,
            NetworkType network
    );





    // ================================================================
    // 🔁 TOGGLE
    // ЕДИНСТВЕННАЯ точка управления из UI / API
    // ================================================================
    StrategyRunInfo toggle(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
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

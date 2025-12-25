package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.util.List;
import java.util.Optional;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings s);

    // ✅ UI / Live / Runner — ВСЕГДА через exchange + network
    StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ✅ для списка стратегий в UI
    List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    );

    Optional<StrategySettings> findLatest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );
}

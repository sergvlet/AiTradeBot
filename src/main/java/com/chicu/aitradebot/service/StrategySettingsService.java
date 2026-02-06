package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.util.List;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings s);

    /**
     * Может вернуть null, если настроек ещё нет.
     */
    StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    /**
     * Гарантированно не null (и НЕ плодит записи из-за UNIQUE).
     */
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
}

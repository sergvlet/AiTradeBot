package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.util.List;

public interface StrategySettingsService {

    StrategySettings save(StrategySettings s);

    /**
     * Источник истины: одна строка на (chatId, type).
     * Может вернуть null, если настроек ещё нет.
     */
    StrategySettings getSettings(long chatId, StrategyType type);

    /**
     * Гарантированно не null: одна строка на (chatId, type).
     * НЕ плодит записи.
     */
    StrategySettings getOrCreate(long chatId, StrategyType type);

    /**
     * Контекст исполнения (exchange/network) — НЕ ключ.
     * Патчит exchange/network в той же строке и возвращает сущность.
     */
    StrategySettings getOrCreateAndPatchContext(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    Long getVersion(Long chatId, StrategyType type);


    /**
     * Патч контекста в уже существующей сущности.
     */
    void patchContext(StrategySettings settings, String exchange, NetworkType network);

    List<StrategySettings> findAllByChatId(long chatId);
}

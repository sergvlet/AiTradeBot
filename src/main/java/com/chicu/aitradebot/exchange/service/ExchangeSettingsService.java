package com.chicu.aitradebot.exchange.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;

import java.util.List;

public interface ExchangeSettingsService {

    // === Основные CRUD ===
    ExchangeSettings save(ExchangeSettings settings);


    ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network);


    void delete(Long chatId, String exchange, NetworkType network);

    // === REST методы (для контроллера) ===
    List<ExchangeSettings> findAllByChatId(Long chatId);



    boolean testConnection(ExchangeSettings settings);


    BinanceConnectionStatus testConnectionDetailed(ExchangeSettings settings);

}

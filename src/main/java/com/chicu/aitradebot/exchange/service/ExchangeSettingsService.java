package com.chicu.aitradebot.exchange.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.BinanceConnectionStatus;

import java.util.List;
import java.util.Optional;

public interface ExchangeSettingsService {

    // === Основные CRUD ===
    ExchangeSettings save(ExchangeSettings settings);

    ExchangeSettings getOrThrow(Long chatId, String exchange);

    ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network);


    void delete(Long chatId, String exchange, NetworkType network);

    // === REST методы (для контроллера) ===
    List<ExchangeSettings> findAllByChatId(Long chatId);

    Optional<ExchangeSettings> findByChatIdAndExchange(Long chatId, String exchange);

    void deleteById(Long id);

    boolean testConnection(ExchangeSettings settings);

    Optional<ExchangeSettings> findByChatIdAndExchangeAndNetwork(Long chatId, String exchange, NetworkType network);

    BinanceConnectionStatus testConnectionDetailed(ExchangeSettings settings);

}

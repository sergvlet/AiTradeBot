package com.chicu.aitradebot.exchange.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;

import java.util.List;

public interface ExchangeSettingsService {

    ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network);

    /**
     * Обновляет ключи в СУЩЕСТВУЮЩЕЙ строке (chatId+exchange+network).
     * Новых строк не создаёт (кроме самого первого раза, когда записи нет).
     *
     * ВАЖНО: если поле пришло пустым — старое значение сохраняем.
     */
    ExchangeSettings saveKeys(Long chatId,
                             String exchange,
                             NetworkType network,
                             String apiKey,
                             String apiSecret,
                             String passphrase,
                             String subAccount);

    List<ExchangeSettings> findAllByChatId(Long chatId);

    void delete(Long chatId, String exchange, NetworkType network);

    boolean testConnection(ExchangeSettings settings);

    ApiKeyDiagnostics testConnectionDetailed(ExchangeSettings settings);

    ApiKeyDiagnostics diagnose(Long chatId, String exchange, NetworkType network);
}

package com.chicu.aitradebot.exchange.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.model.ApiKeyDiagnostics;

import java.util.List;

public interface ExchangeSettingsService {

    // =====================================================================
    // NETWORK TAB (биржа + сеть)
    // =====================================================================

    /**
     * Получить или создать настройки биржи/сети для chatId.
     * Ключи всегда создаются как "" (не null).
     */
    ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network);

    /**
     * Обновить ТОЛЬКО биржу и сеть.
     * ❗ НЕ трогает API ключи.
     */
    ExchangeSettings saveNetwork(Long chatId, String exchange, NetworkType network);

    // =====================================================================
    // API KEYS (без затирания)
    // =====================================================================

    /**
     * Сохранить API ключи.
     * ❗ Пустые значения ИГНОРИРУЮТСЯ.
     * ❗ Существующие ключи НЕ ЗАТИРАЮТСЯ.
     */
    ExchangeSettings saveKeys(
            Long chatId,
            String exchange,
            NetworkType network,
            String apiKey,
            String apiSecret,
            String passphrase
    );

    // =====================================================================
    // FIND / DELETE
    // =====================================================================

    List<ExchangeSettings> findAllByChatId(Long chatId);

    void delete(Long chatId, String exchange, NetworkType network);

    /**
     * Прямое сохранение entity (редко используется, для внутренних нужд).
     */
    ExchangeSettings save(ExchangeSettings settings);

    // =====================================================================
    // DIAGNOSTICS
    // =====================================================================

    /**
     * Быстрая проверка: true / false.
     */
    boolean testConnection(ExchangeSettings settings);

    /**
     * Детальная диагностика (Binance / Bybit).
     */
    ApiKeyDiagnostics testConnectionDetailed(ExchangeSettings settings);

    /**
     * Диагностика по chatId + exchange + network (для AJAX / UI).
     */
    ApiKeyDiagnostics diagnose(Long chatId, String exchange, NetworkType network);
}

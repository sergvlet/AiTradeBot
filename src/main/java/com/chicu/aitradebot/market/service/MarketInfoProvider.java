package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.dto.SymbolInfoDto;

import java.util.List;

/**
 * 🔌 Провайдер рыночных данных для конкретной биржи (Binance, Bybit и т.д.)
 * Реализации:
 *  - BinanceMarketInfoProvider
 *  - BybitMarketInfoProvider
 *  - OkxMarketInfoProvider (в будущем)
 */
public interface MarketInfoProvider {

    /**
     * Поддерживает ли провайдер указанную биржу.
     * Например: "BINANCE", "BYBIT", "OKX"
     */
    boolean supports(String exchange);

    /**
     * Получить сводку по рынку:
     *  - список символов
     *  - доступные таймфреймы
     */
    MarketOverviewDto getOverview(NetworkType network);

    /**
     * Поиск символов по подстроке (например, "eth" -> ETHUSDT, ETHBTC).
     */
    List<SymbolInfoDto> searchSymbols(NetworkType network, String query);

    /**
     * Получить детальную информацию по одному символу.
     */
    SymbolInfoDto getSymbolInfo(NetworkType network, String symbol);

    /** Название биржи (BINANCE, BYBIT, OKX...) */
    String getExchangeName();

    /**
     * Лёгкий список символов для поиска (БЕЗ цен и объёмов).
     * Должен быть быстрым: данные кэшируются.
     */
    List<String> getAllSymbols(NetworkType network);


}

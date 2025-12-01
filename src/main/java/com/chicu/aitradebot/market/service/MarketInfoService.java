package com.chicu.aitradebot.market.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.market.dto.MarketOverviewDto;
import com.chicu.aitradebot.market.dto.SymbolInfoDto;

import java.util.List;

/**
 * 🌍 Унифицированный сервис рыночной информации для веб-UI.
 * Оборачивает несколько MarketInfoProvider (Binance, Bybit, ...).
 */
public interface MarketInfoService {

    /**
     * Получить сводку по рынку для вкладки "Торговля":
     *  - список символов
     *  - таймфреймы
     */
    MarketOverviewDto getOverview(String exchange, NetworkType network);

    /**
     * Поиск символов по строке (для строки поиска).
     */
    List<SymbolInfoDto> searchSymbols(String exchange,
                                      NetworkType network,
                                      String query);

    /**
     * Информация по одному символу (для лайв-обновления цены и т.п.).
     */
    SymbolInfoDto getSymbolInfo(String exchange,
                                NetworkType network,
                                String symbol);
}

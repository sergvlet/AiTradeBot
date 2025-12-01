package com.chicu.aitradebot.market.dto;

import lombok.*;

import java.util.List;

/**
 * 📈 Сводная информация по рынку для вкладки "Торговля".
 * Содержит список символов и доступные таймфреймы.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MarketOverviewDto {

    private String exchange;
    private String network;

    /**
     * Список символов с ценой / изменением / объёмом.
     */
    private List<SymbolInfoDto> symbols;

    /** Список топ-символов (можешь расширить при желании) */
    private List<SymbolInfoDto> topSymbols;

    /**
     * Доступные таймфреймы для выбранной биржи.
     * Например: ["1m","5m","15m","1h","4h","1d"]
     */
    private List<String> timeframes;

    /**
     * Время последнего обновления данных (millis since epoch).
     * Можно использовать на фронте для кэша / отображения.
     */
    private long lastUpdate;


}

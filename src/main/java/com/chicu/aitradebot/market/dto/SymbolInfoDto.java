package com.chicu.aitradebot.market.dto;

import lombok.*;

/**
 * 📊 Краткая информация по торговому инструменту (символу)
 * Используется во вкладке "Торговля" и в API /api/market/...
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SymbolInfoDto {

    /**
     * Имя символа, например BTCUSDT
     */
    private String symbol;

    /**
     * Текущая цена
     */
    private double price;

    /**
     * Изменение цены за 24 часа, в процентах.
     * Например: +1.24 -> 1.24; -0.56 -> -0.56
     */
    private double changePct;

    /**
     * Объём торгов за 24 часа (в котируемой валюте, например USDT)
     */
    private double volume;

    /**
     * Статус символа (TRADING / BREAK / HALT / ...)
     */
    private String status;
}

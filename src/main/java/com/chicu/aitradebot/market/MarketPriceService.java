package com.chicu.aitradebot.market;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * MarketPriceService — единый источник "последней цены" по символу.
 *
 * Источники данных (Binance WS, Bybit WS) вызывают updatePrice().
 * Потребители (StrategyEngine, UI, риск-модуль) читают getLastPrice().
 */
public interface MarketPriceService {

    /**
     * Обновить последнюю цену по символу.
     */
    void updatePrice(String symbol, BigDecimal price);

    /**
     * Получить последнюю цену, если она есть.
     */
    Optional<BigDecimal> getLastPrice(String symbol);

    /**
     * Упрощённый метод: кидает ошибку, если цены нет.
     */
    default BigDecimal getLastPriceOrThrow(String symbol) {
        return getLastPrice(symbol)
                .orElseThrow(() -> new IllegalStateException("Нет цены для символа: " + symbol));
    }
}

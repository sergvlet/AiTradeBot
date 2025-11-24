package com.chicu.aitradebot.market;

import java.math.BigDecimal;

/**
 * Live-рынок:
 *  - хранит последнюю цену по символу
 *  - обновляется потоками Binance/Bybit
 *  - используется Web-дашбордом и стратегиями
 */
public interface MarketLiveService {

    /** Логическая подписка (если нужно что-то инициализировать под символ) */
    void subscribe(String symbol, String timeframe);

    /** Обновить цену по символу (tick из WebSocket) */
    void updatePrice(String symbol, BigDecimal price);

    /** Получить последнюю цену (для /api/chart/price и т.п.) */
    PricePoint getLastPrice(String symbol);

    /** DTO для последней цены */
    record PricePoint(
            long time,
            BigDecimal price
    ) {}
}

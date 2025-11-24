package com.chicu.aitradebot.web.facade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 🌐 WebMarketFacade
 * Единственная точка доступа web-слоя к рыночным данным:
 * - текущая цена
 * - свечи для графика
 * - простые метаданные по рынку
 *
 * ВАЖНО:
 *  - никаких бинансов / байбитов / окх здесь не светится
 *  - никаких сущностей JPA
 *  - только простые DTO для UI
 */
public interface WebMarketFacade {

    /**
     * Загрузить стартовый набор свечей для графика.
     *
     * @param chatId   пользователь / сессия
     * @param symbol   торговая пара, например "BTCUSDT"
     * @param timeframe таймфрейм, например "1m", "5m", "1h"
     * @param limit    сколько свечей вернуть (например 500)
     */
    List<CandlePoint> loadInitialCandles(Long chatId,
                                         String symbol,
                                         String timeframe,
                                         int limit);

    /**
     * Догрузить свечи "назад" во времени (скролл влево на графике).
     *
     * @param chatId   пользователь / сессия
     * @param symbol   торговая пара
     * @param timeframe таймфрейм
     * @param to       до какого времени (эксклюзивно), обычно самая ранняя свеча на графике
     * @param limit    максимум свечей
     */
    List<CandlePoint> loadMoreCandles(Long chatId,
                                      String symbol,
                                      String timeframe,
                                      Instant to,
                                      int limit);

    /**
     * Получить последнюю цену для "живого" графика.
     */
    PricePoint getLastPrice(Long chatId, String symbol);

    /**
     * Упрощённый тренд для UI (стрелочка вверх/вниз и процент).
     */
    TrendInfo getTrendInfo(Long chatId, String symbol, String timeframe);

    // =============================================================
    // Внутренние DTO (простые, под web)
    // =============================================================

    /**
     * Одна свеча для графика.
     */
    record CandlePoint(
            long time,          // millis epoch
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume
    ) {
    }

    /**
     * Точка последней цены.
     */
    record PricePoint(
            long time,
            BigDecimal price
    ) {
    }

    /**
     * Короткая информация о тренде.
     */
    record TrendInfo(
            boolean up,             // true = растёт, false = падает/флэт
            BigDecimal changePct    // изменение % за выбранный таймфрейм
    ) {
    }
}

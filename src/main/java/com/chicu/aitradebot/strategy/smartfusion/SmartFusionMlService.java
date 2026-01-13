// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionMlService.java
package com.chicu.aitradebot.strategy.smartfusion;

/**
 * ML сервис для SmartFusion.
 * Сейчас это контракт (интерфейс). Реализация может ходить в Python/XGBoost,
 * либо быть заглушкой (см. SmartFusionMlServiceStub).
 */
public interface SmartFusionMlService {

    /**
     * Вернуть уверенность BUY [0..1].
     *
     * ВАЖНО: сигнатура сделана ровно под вызов из SmartFusionStrategyV4:
     * (Long, String, String, String, SmartFusionFeatures)
     */
    double predictBuyConfidence(
            Long chatId,
            String symbol,
            String timeframe,
            String modelKey,
            SmartFusionFeatures features
    );

    /**
     * (Опционально) SELL уверенность [0..1].
     * Если сейчас не используешь — пусть будет 0.
     */
    default double predictSellConfidence(
            Long chatId,
            String symbol,
            String timeframe,
            String modelKey,
            SmartFusionFeatures features
    ) {
        return 0.0;
    }
}

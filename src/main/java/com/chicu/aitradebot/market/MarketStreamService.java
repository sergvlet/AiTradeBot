package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.UnifiedKline;

import java.math.BigDecimal;

public interface MarketStreamService {

    void ensureSubscribed(long chatId,
                          StrategyType type,
                          String symbol,
                          String timeframe,
                          String exchange,
                          NetworkType networkType);

    /**
     * ✅ Правильный тик: aggTrade не зависит от timeframe.
     * Timeframe берётся из контекста подписки/стратегии (или не нужен для UI-tick).
     */
    void onAggTrade(long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType networkType,
                    String symbol,
                    BigDecimal price,
                    BigDecimal qty,
                    long tradeTsMs);


    /**
     * ⚠️ Legacy (оставляем для обратной совместимости).
     * Старый код может передавать timeframe, но он НЕ должен влиять на ключ aggTrade.
     */
    @Deprecated
    default void onAggTrade(long chatId,
                            StrategyType type,
                            String exchange,
                            NetworkType networkType,
                            String symbol,
                            String timeframe,
                            BigDecimal price,
                            BigDecimal qty,
                            long tradeTsMs) {
        onAggTrade(chatId, type, exchange, networkType, symbol, price, qty, tradeTsMs);
    }

    /**
     * ✅ Строгий вход: symbol + timeframe обязательны.
     * Иначе оркестратор не сможет гарантировать отсутствие смешений.
     */
    void onKline(long chatId,
                 StrategyType type,
                 String exchange,
                 NetworkType networkType,
                 String symbol,
                 String timeframe,
                 UnifiedKline kline);
}

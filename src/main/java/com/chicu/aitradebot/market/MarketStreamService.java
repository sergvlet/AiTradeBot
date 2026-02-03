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

    void onAggTrade(long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType networkType,
                    String symbol,
                    String timeframe,
                    BigDecimal price,
                    BigDecimal qty,
                    long tradeTsMs);

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

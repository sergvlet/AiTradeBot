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

    void unsubscribe(long chatId, StrategyType type);

    void onAggTrade(long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType networkType,
                    String symbol,
                    BigDecimal price,
                    BigDecimal qty,
                    long tradeTsMs);

    void onAggTrade(long chatId,
                    StrategyType type,
                    String exchange,
                    NetworkType networkType,
                    String symbol,
                    String timeframeIgnored,
                    BigDecimal price,
                    BigDecimal qty,
                    long tradeTsMs);

    void onBookTicker(long chatId,
                      StrategyType type,
                      String exchange,
                      NetworkType networkType,
                      String symbol,
                      BigDecimal bid,
                      BigDecimal ask,
                      long eventTsMs);

    void onKline(long chatId,
                 StrategyType type,
                 String exchange,
                 NetworkType networkType,
                 String symbol,
                 UnifiedKline kline);

    void onKline(long chatId,
                 StrategyType type,
                 String exchange,
                 NetworkType networkType,
                 String symbol,
                 String timeframe,
                 UnifiedKline kline);
}

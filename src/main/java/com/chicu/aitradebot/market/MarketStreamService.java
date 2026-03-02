package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.market.model.UnifiedKline;

import java.math.BigDecimal;

/**
 * MarketStreamService
 * - поднимает/держит WS подписки на рынок (через MarketDataStreamService)
 * - маршрутизирует тики/свечи в AiStrategyOrchestrator с жёстким контекстом (ex/net/symbol/tf)
 *
 * Важно:
 * - ensureSubscribed вызывается из ORCH (а не из UI), чтобы бот работал даже если страницу закрыли.
 * - есть default overload-ы для обратной совместимости (старые сигнатуры не ломаем).
 */
public interface MarketStreamService {

    // =====================================================
    // ✅ LIFECYCLE (подписки на рынок)
    // =====================================================

    /**
     * Канонический вариант (✅ предпочтительный).
     */
    void ensureSubscribed(long chatId,
                          StrategyType type,
                          String symbol,
                          String timeframe,
                          String exchange,
                          NetworkType networkType);

    /**
     * Совместимость: иногда приходил order (exchange, network, symbol, tf, limit).
     * limit пока игнорируем в MarketStreamServiceImpl (кэш свечей — задача MarketDataStreamService).
     */
    default void ensureSubscribed(long chatId,
                                  StrategyType type,
                                  String exchange,
                                  NetworkType networkType,
                                  String symbol,
                                  String timeframe,
                                  Integer cachedCandlesLimit) {
        ensureSubscribed(chatId, type, symbol, timeframe, exchange, networkType);
    }

    default void ensureSubscribed(Long chatId,
                                  StrategyType type,
                                  String symbol,
                                  String timeframe,
                                  String exchange,
                                  NetworkType networkType) {
        if (chatId == null) return;
        ensureSubscribed(chatId.longValue(), type, symbol, timeframe, exchange, networkType);
    }

    void unsubscribe(long chatId, StrategyType type);

    default void unsubscribe(Long chatId, StrategyType type) {
        if (chatId == null) return;
        unsubscribe(chatId.longValue(), type);
    }

    // =====================================================
    // ✅ EVENTS (из WS)
    // =====================================================

    /**
     * Канонический aggTrade для цены (timeframe берём из binding у ORCH).
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
     * Совместимость: где-то пробрасывали timeframe в tick — он не обязателен.
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
     * Канонический kline (timeframe нужен для строгого роутинга).
     */
    void onKline(long chatId,
                 StrategyType type,
                 String exchange,
                 NetworkType networkType,
                 String symbol,
                 String timeframe,
                 UnifiedKline kline);

    /**
     * Совместимость: старый вызов без timeframe.
     */
    @Deprecated
    default void onKline(long chatId,
                         StrategyType type,
                         String exchange,
                         NetworkType networkType,
                         String symbol,
                         UnifiedKline kline) {
        onKline(chatId, type, exchange, networkType, symbol, null, kline);
    }
}

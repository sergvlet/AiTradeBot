package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.time.Instant;

public interface TradeExecutionService {

    /**
     * ✅ Backward compatible (старые стратегии).
     * TP/SL будут рассчитаны внутри имплементации,
     * либо будет fail с понятной причиной.
     */
    EntryResult executeEntry(Long chatId,
                             StrategyType strategyType,
                             String symbol,
                             BigDecimal price,
                             BigDecimal diffPct,
                             Instant time,
                             StrategySettings strategySettings);

    /**
     * ✅ PROD контракт: TP/SL берутся из настроек КОНКРЕТНОЙ стратегии.
     */
    EntryResult executeEntry(Long chatId,
                             StrategyType strategyType,
                             String symbol,
                             BigDecimal price,
                             BigDecimal diffPct,
                             Instant time,
                             StrategySettings strategySettings,
                             BigDecimal takeProfitPct,
                             BigDecimal stopLossPct);

    /**
     * ✅ Старый EXIT (оставляем для совместимости).
     * НЕ умеет закрывать PositionStore без exchange/network.
     */
    default ExitResult executeExitIfHit(Long chatId,
                                        StrategyType strategyType,
                                        String symbol,
                                        BigDecimal price,
                                        Instant time,
                                        boolean isLong,
                                        BigDecimal entryQty,
                                        BigDecimal tp,
                                        BigDecimal sl) {
        return executeExitIfHit(chatId, strategyType, symbol, price, time, isLong, entryQty, tp, sl, null, null);
    }

    /**
     * ✅ Новый EXIT: добавили exchange/network, чтобы:
     * - закрывать PositionStore
     * - триггерить автотюнинг после закрытия позиции
     */
    ExitResult executeExitIfHit(Long chatId,
                                StrategyType strategyType,
                                String symbol,
                                BigDecimal price,
                                Instant time,
                                boolean isLong,
                                BigDecimal entryQty,
                                BigDecimal tp,
                                BigDecimal sl,
                                String exchange,
                                NetworkType network);

    /**
     * Минимальный TP% для входа с учётом round-trip fee, edge и net RR.
     */
    default BigDecimal resolveMinHealthyTpPct(Long chatId,
                                              String exchange,
                                              NetworkType network,
                                              BigDecimal slPct) {
        return BigDecimal.ZERO;
    }

    /**
     * Оценка round-trip fee% (в процентах, не в долях).
     */
    default BigDecimal estimateRoundTripFeePct(Long chatId,
                                               String exchange,
                                               NetworkType network) {
        return BigDecimal.ZERO;
    }
}



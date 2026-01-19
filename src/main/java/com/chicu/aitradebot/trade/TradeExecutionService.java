package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.time.Instant;

public interface TradeExecutionService {

    /**
     * ✅ Backward compatible (старые стратегии).
     * TP/SL будут рассчитаны внутри имплементации,
     * либо (если ты так решил) будет fail с понятной причиной.
     */
    EntryResult executeEntry(Long chatId,
                             StrategyType strategyType,
                             String symbol,
                             BigDecimal price,
                             BigDecimal diffPct,
                             Instant time,
                             StrategySettings strategySettings);

    /**
     * ✅ PROD контракт: TP/SL берутся из настроек КОНКРЕТНОЙ стратегии
     * (например WindowScalpingStrategySettings / ScalpingStrategySettings и т.д.)
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

    ExitResult executeExitIfHit(Long chatId,
                                StrategyType strategyType,
                                String symbol,
                                BigDecimal price,
                                Instant time,
                                boolean isLong,
                                BigDecimal entryQty,
                                BigDecimal tp,
                                BigDecimal sl);
}

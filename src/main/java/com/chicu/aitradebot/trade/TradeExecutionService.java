package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;

import java.math.BigDecimal;
import java.time.Instant;

public interface TradeExecutionService {

    EntryResult executeEntry(Long chatId,
                             StrategyType strategyType,
                             String symbol,
                             BigDecimal price,
                             BigDecimal diffPct,
                             Instant time,
                             StrategySettings strategySettings);

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

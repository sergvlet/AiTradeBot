package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;

public interface PositionResolver {

    PositionResolution resolve(Long chatId,
                               StrategyType strategyType,
                               String exchange,
                               NetworkType network,
                               String symbol,
                               BigDecimal marketPrice);
}

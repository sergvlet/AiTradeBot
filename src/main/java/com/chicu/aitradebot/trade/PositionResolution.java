package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.domain.StrategyPositionEntity;

import java.math.BigDecimal;

public record PositionResolution(
        PositionResolutionState state,
        StrategyPositionEntity ownPosition,
        BigDecimal walletBaseQty,
        BigDecimal walletBaseNotional,
        BigDecimal minTradableNotional,
        String details
) {
    public boolean blocksNewEntry() {
        return state == PositionResolutionState.OWN_OPEN_POSITION
                || state == PositionResolutionState.EXTERNAL_POSITION
                || state == PositionResolutionState.LOCAL_EXCHANGE_MISMATCH;
    }

    public static PositionResolution noPosition(BigDecimal minTradableNotional) {
        return new PositionResolution(PositionResolutionState.NO_POSITION, null, BigDecimal.ZERO, BigDecimal.ZERO, minTradableNotional, "ok");
    }
}

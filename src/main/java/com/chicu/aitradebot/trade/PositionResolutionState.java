package com.chicu.aitradebot.trade;

public enum PositionResolutionState {
    NO_POSITION,
    OWN_OPEN_POSITION,
    EXTERNAL_POSITION,
    DUST_ONLY,
    LOCAL_EXCHANGE_MISMATCH
}

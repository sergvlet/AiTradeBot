package com.chicu.aitradebot.strategy.core;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SettingsSnapshot {

    private final long chatId;
    private final String symbol;
    private final String timeframe;

    // scalping
    private final int windowSize;
    private final double priceChangeThreshold;
    private final double spreadThreshold;
    private final double takeProfitPct;
    private final double stopLossPct;
    private final double orderVolume;
}

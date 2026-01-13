package com.chicu.aitradebot.common.enums;

/** Типы стратегий, поддерживаемые ботом */
public enum StrategyType {

    // SYSTEM / META
    GLOBAL,

    // I) MOMENTUM / TREND
    MOMENTUM,
    TREND_FOLLOWING,
    EMA_CROSSOVER,
    TREND,

    // II) MEAN REVERSION
    MEAN_REVERSION,

    RSI_OBOS,

    // III) SCALPING
    SCALPING,
    WINDOW_SCALPING,

    // IV) BREAKOUT
    BREAKOUT,
    VOLATILITY_BREAKOUT,

    // V) LEVELS / STRUCTURE
    SUPPORT_RESISTANCE,
    FIBONACCI_RETRACE,
    PRICE_ACTION,

    // VI) GRIDS
    GRID,
    FIBONACCI_GRID,

    // VII) VOLUME
    VOLUME_PROFILE,
    VWAP,
    ORDER_FLOW,

    // VIII) AI
    ML_CLASSIFICATION,
    RL_AGENT,
    HYBRID,

    DCA,
    SMART_FUSION,


}

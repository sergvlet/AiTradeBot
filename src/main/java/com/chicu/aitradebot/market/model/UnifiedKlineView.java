package com.chicu.aitradebot.market.model;

import java.math.BigDecimal;

public interface UnifiedKlineView {
    String getSymbol();
    String getTimeframe();

    BigDecimal getOpen();
    BigDecimal getHigh();
    BigDecimal getLow();
    BigDecimal getClose();
    BigDecimal getVolume();

    Long getOpenTimeMs(); // или getTimeMs — главное единообразие
}

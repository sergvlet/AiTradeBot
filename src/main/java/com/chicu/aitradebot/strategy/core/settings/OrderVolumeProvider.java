package com.chicu.aitradebot.strategy.core.settings;

import java.math.BigDecimal;

/**
 * Контракт: стратегия обязана указать объём ордера
 */
public interface OrderVolumeProvider {
    BigDecimal getOrderVolume();
}

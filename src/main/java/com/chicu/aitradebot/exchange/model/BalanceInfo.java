package com.chicu.aitradebot.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 💰 BalanceInfo — DTO для хранения баланса актива с биржи.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceInfo {

    @Builder.Default
    private BigDecimal free = BigDecimal.ZERO;   // доступно

    @Builder.Default
    private BigDecimal locked = BigDecimal.ZERO; // в ордерах

    public BigDecimal getTotal() {
        return free.add(locked);
    }
}

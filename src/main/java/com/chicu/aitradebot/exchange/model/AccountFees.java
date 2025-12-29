package com.chicu.aitradebot.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountFees {

    /** Комиссия maker в процентах (0.1 = 0.1%) */
    private BigDecimal makerPct;

    /** Комиссия taker в процентах (0.1 = 0.1%) */
    private BigDecimal takerPct;
}

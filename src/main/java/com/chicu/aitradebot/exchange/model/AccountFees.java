package com.chicu.aitradebot.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountFees {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /**
     * Исторический защитный порог для старого бага, где часть клиентов отдавала fee как rate:
     * 0.001 = 0.1%, 0.0006 = 0.06%.
     *
     * Нормальный контракт класса ниже — ВСЕГДА проценты:
     * 0.1 = 0.1%, 0.06 = 0.06%, 0.18 = 0.18%.
     */
    private static final BigDecimal LEGACY_RATE_TO_PCT_THRESHOLD = new BigDecimal("0.01");

    /** Комиссия maker в процентах (0.1 = 0.1%) */
    private BigDecimal makerPct;

    /** Комиссия taker в процентах (0.1 = 0.1%) */
    private BigDecimal takerPct;

    public BigDecimal makerPctNormalized() {
        return normalizePct(makerPct);
    }

    public BigDecimal takerPctNormalized() {
        return normalizePct(takerPct);
    }

    public BigDecimal makerRate() {
        return pctToRate(makerPct);
    }

    public BigDecimal takerRate() {
        return pctToRate(takerPct);
    }

    public static BigDecimal normalizePct(BigDecimal rawPct) {
        if (rawPct == null || rawPct.signum() <= 0) {
            return null;
        }

        BigDecimal normalized = rawPct.stripTrailingZeros();

        // Защита от старой шкалы rate -> pct.
        // После починки клиентов сюда обычно уже приходят корректные pct-значения.
        if (normalized.compareTo(LEGACY_RATE_TO_PCT_THRESHOLD) < 0) {
            normalized = normalized.multiply(HUNDRED);
        }

        return normalized.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static BigDecimal pctToRate(BigDecimal pctValue) {
        BigDecimal pct = normalizePct(pctValue);
        if (pct == null || pct.signum() <= 0) {
            return null;
        }
        return pct.divide(HUNDRED, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}

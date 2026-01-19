package com.chicu.aitradebot.trade;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * Результат входа в позицию.
 * executed=false -> причина в reason.
 */
@Builder
public record EntryResult(
        boolean executed,
        boolean isLong,
        String side,              // "BUY" / "SELL"
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal tp,
        BigDecimal sl,
        Long orderId,
        String reason
) {

    public static EntryResult ok(boolean isLong,
                                 String side,
                                 BigDecimal qty,
                                 BigDecimal entryPrice,
                                 BigDecimal tp,
                                 BigDecimal sl,
                                 Long orderId) {
        return EntryResult.builder()
                .executed(true)
                .isLong(isLong)
                .side(side)
                .qty(qty)
                .entryPrice(entryPrice)
                .tp(tp)
                .sl(sl)
                .orderId(orderId)
                .reason("executed")
                .build();
    }

    public static EntryResult fail(String reason) {
        return EntryResult.builder()
                .executed(false)
                .reason(reason)
                .build();
    }

    /**
     * Совместимость со старыми реализациями (у тебя в логах было skipped()).
     */
    public static EntryResult skipped(String reason) {
        return fail(reason);
    }
}

package com.chicu.aitradebot.trade;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * Результат выхода из позиции.
 */
@Builder
public record ExitResult(
        boolean executed,
        boolean tpHit,
        boolean slHit,
        String reason,
        BigDecimal exitPrice,
        BigDecimal pnlPercent
) {

    public static ExitResult ok(boolean tpHit,
                                boolean slHit,
                                BigDecimal exitPrice,
                                BigDecimal pnlPercent) {
        return ExitResult.builder()
                .executed(true)
                .tpHit(tpHit)
                .slHit(slHit)
                .exitPrice(exitPrice)
                .pnlPercent(pnlPercent)
                .reason("executed")
                .build();
    }

    public static ExitResult fail(String reason) {
        return ExitResult.builder()
                .executed(false)
                .reason(reason)
                .build();
    }

    public static ExitResult skipped(String reason) {
        return fail(reason);
    }
}

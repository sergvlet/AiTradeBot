package com.chicu.aitradebot.trade.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QtyMath {

    private QtyMath() {}

    /** Округление ВНИЗ к шагу (обычно для безопасного уменьшения) */
    public static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return value;
        if (step.signum() <= 0) return value;
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }

    /** Округление ВВЕРХ к шагу (нужно, чтобы не провалиться в 0 и пройти фильтры) */
    public static BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return value;
        if (step.signum() <= 0) return value;
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal steps = value.divide(step, 0, RoundingMode.UP);
        return steps.multiply(step);
    }
}

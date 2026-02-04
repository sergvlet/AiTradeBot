package com.chicu.aitradebot.trade.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QtyMath {

    private QtyMath() {}

    /**
     * Нормальный scale для шага:
     *  step=0.001        -> 3
     *  step=1            -> 0
     *  step=0.00001000   -> 5
     */
    public static int scaleOfStep(BigDecimal step) {
        if (step == null) return 8;
        BigDecimal s = step.stripTrailingZeros();
        int sc = s.scale();
        return Math.max(0, sc);
    }

    /** Привести value к scale шага (вниз) */
    public static BigDecimal normalizeToStepScale(BigDecimal value, BigDecimal step) {
        if (value == null) return null;
        int sc = scaleOfStep(step);
        return value.setScale(sc, RoundingMode.DOWN);
    }

    /** value > 0 */
    public static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Удобный printable */
    public static String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    /**
     * Проверка кратности step (биржевой формат).
     */
    public static boolean isMultipleOfStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return false;
        if (value.signum() <= 0 || step.signum() <= 0) return false;

        // приводим к одному scale, иначе remainder может вести себя неожиданно
        int sc = Math.max(value.stripTrailingZeros().scale(), step.stripTrailingZeros().scale());
        BigDecimal v = value.setScale(sc, RoundingMode.DOWN);
        BigDecimal s = step.setScale(sc, RoundingMode.DOWN);

        return v.remainder(s).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Округление ВНИЗ к шагу:
     * floor(value / step) * step
     *
     * Возвращает число со scale шага (важно для биржи).
     */
    public static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return value;
        if (step.signum() <= 0) return value;
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        BigDecimal out = steps.multiply(step);

        int sc = scaleOfStep(step);
        return out.setScale(sc, RoundingMode.DOWN);
    }

    /**
     * Округление ВВЕРХ к шагу:
     * ceil(value / step) * step
     *
     * Возвращает число со scale шага (важно для биржи).
     *
     * ⚠️ Важно: финальный setScale делаем DOWN, потому что кратность step уже гарантирована.
     */
    public static BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return value;
        if (step.signum() <= 0) return value;
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal steps = value.divide(step, 0, RoundingMode.UP);
        BigDecimal out = steps.multiply(step);

        int sc = scaleOfStep(step);
        return out.setScale(sc, RoundingMode.DOWN);
    }

    /**
     * floorToStep, но если получилось 0 — возвращаем 0 (явно).
     */
    public static BigDecimal floorToStepOrZero(BigDecimal value, BigDecimal step) {
        BigDecimal out = floorToStep(value, step);
        return isPositive(out) ? out : BigDecimal.ZERO;
    }

    /**
     * ceilToStep, но гарантирует минимум step (если value > 0, но меньше шага).
     * Удобно для requiredQty.
     */
    public static BigDecimal ceilToStepAtLeastStep(BigDecimal value, BigDecimal step) {
        if (!isPositive(value)) return BigDecimal.ZERO;
        if (!isPositive(step)) return value;

        BigDecimal out = ceilToStep(value, step);
        BigDecimal st = step.setScale(scaleOfStep(step), RoundingMode.DOWN);

        if (!isPositive(out)) return st;
        if (out.compareTo(st) < 0) return st;
        return out;
    }

    /**
     * Минимальное qty, чтобы выполнить minNotional при цене price, с учётом step.
     * requiredQty = ceil( (minNotional / price) to step )
     */
    public static BigDecimal requiredMinQtyForNotional(BigDecimal minNotional, BigDecimal price, BigDecimal step) {
        if (!isPositive(minNotional) || !isPositive(price) || !isPositive(step)) return null;

        BigDecimal raw = minNotional.divide(price, 32, RoundingMode.UP);
        return ceilToStepAtLeastStep(raw, step);
    }

    /** Умножение без NPE */
    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.multiply(b);
    }

    /** Деление с защитой */
    public static BigDecimal div(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        if (a == null || b == null) return null;
        if (b.signum() == 0) return null;
        return a.divide(b, Math.max(0, scale), mode == null ? RoundingMode.DOWN : mode);
    }
}

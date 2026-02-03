package com.chicu.aitradebot.trade.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QtyMath {

    private QtyMath() {}

    /**
     * Нормальный scale для шага:
     *  step=0.001  -> 3
     *  step=1      -> 0
     *  step=0.00001000 -> 5
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
     */
    public static BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return value;
        if (step.signum() <= 0) return value;
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal steps = value.divide(step, 0, RoundingMode.UP);
        BigDecimal out = steps.multiply(step);

        int sc = scaleOfStep(step);
        return out.setScale(sc, RoundingMode.UP);
    }

    /**
     * floorToStep, но если получилось 0 — возвращаем 0 (явно).
     * Удобно, когда хочешь отловить "qty слишком маленький".
     */
    public static BigDecimal floorToStepOrZero(BigDecimal value, BigDecimal step) {
        BigDecimal out = floorToStep(value, step);
        return isPositive(out) ? out : BigDecimal.ZERO;
    }

    /**
     * ceilToStep, но гарантирует минимум step (если value > 0, но меньше шага).
     * Удобно для подсказки requiredQty.
     */
    public static BigDecimal ceilToStepAtLeastStep(BigDecimal value, BigDecimal step) {
        if (!isPositive(value)) return BigDecimal.ZERO;
        if (!isPositive(step)) return value;

        BigDecimal out = ceilToStep(value, step);
        if (!isPositive(out)) return step.setScale(scaleOfStep(step), RoundingMode.UP);
        if (out.compareTo(step) < 0) return step.setScale(scaleOfStep(step), RoundingMode.UP);
        return out;
    }

    /**
     * Умножение с нормальным поведением (не кидает NPE).
     */
    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.multiply(b);
    }

    /**
     * Деление с защитой.
     */
    public static BigDecimal div(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        if (a == null || b == null) return null;
        if (b.signum() == 0) return null;
        return a.divide(b, Math.max(0, scale), mode == null ? RoundingMode.DOWN : mode);
    }
}

package com.chicu.aitradebot.trade.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class QtyMath {

    private static final int DEFAULT_SCALE = 16;

    private QtyMath() {}

    /**
     * Нормальный scale для шага:
     *  step=0.001        -> 3
     *  step=1            -> 0
     *  step=0.00001000   -> 5
     */
    public static int scaleOfStep(BigDecimal step) {
        if (step == null) return DEFAULT_SCALE;
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

    /** Округление вниз по scale, когда step неизвестен */
    public static BigDecimal normalizeDown(BigDecimal value, int scale) {
        if (value == null) return null;
        if (value.signum() <= 0) return BigDecimal.ZERO;
        return value.setScale(Math.max(0, scale), RoundingMode.DOWN);
    }

    /** Округление вверх по scale, когда step неизвестен */
    public static BigDecimal normalizeUp(BigDecimal value, int scale) {
        if (value == null) return null;
        if (value.signum() <= 0) return BigDecimal.ZERO;
        return value.setScale(Math.max(0, scale), RoundingMode.UP);
    }

    /**
     * Проверка кратности step (биржевой формат).
     */
    public static boolean isMultipleOfStep(BigDecimal value, BigDecimal step) {
        if (value == null || step == null) return false;
        if (value.signum() <= 0 || step.signum() <= 0) return false;

        int sc = Math.max(value.stripTrailingZeros().scale(), step.stripTrailingZeros().scale());
        BigDecimal v = value.setScale(sc, RoundingMode.DOWN);
        BigDecimal s = step.setScale(sc, RoundingMode.DOWN);

        return v.remainder(s).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Округление ВНИЗ к шагу:
     * floor(value / step) * step
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

    public static BigDecimal floorToStepOrZero(BigDecimal value, BigDecimal step) {
        BigDecimal out = floorToStep(value, step);
        return isPositive(out) ? out : BigDecimal.ZERO;
    }

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
     * Универсальное округление вниз:
     * - если step есть -> floorToStep
     * - если step нет  -> scale DOWN
     */
    public static BigDecimal floorSmart(BigDecimal value, BigDecimal step, int fallbackScale) {
        if (!isPositive(value)) return BigDecimal.ZERO;
        if (isPositive(step)) return floorToStepOrZero(value, step);
        return normalizeDown(value, Math.max(DEFAULT_SCALE, fallbackScale));
    }

    /**
     * Универсальное округление вверх:
     * - если step есть -> ceilToStepAtLeastStep
     * - если step нет  -> scale UP
     */
    public static BigDecimal ceilSmart(BigDecimal value, BigDecimal step, int fallbackScale) {
        if (!isPositive(value)) return BigDecimal.ZERO;
        if (isPositive(step)) return ceilToStepAtLeastStep(value, step);
        return normalizeUp(value, Math.max(DEFAULT_SCALE, fallbackScale));
    }

    /**
     * Минимальное qty, чтобы выполнить minNotional при цене price.
     * Работает и когда step неизвестен.
     */
    public static BigDecimal requiredMinQtyForNotional(BigDecimal minNotional,
                                                       BigDecimal price,
                                                       BigDecimal step,
                                                       int fallbackScale) {
        if (!isPositive(minNotional) || !isPositive(price)) return null;

        BigDecimal raw = minNotional.divide(
                price,
                Math.max(DEFAULT_SCALE, fallbackScale + 4),
                RoundingMode.UP
        );

        return ceilSmart(raw, step, fallbackScale);
    }

    public static BigDecimal requiredMinQtyForNotional(BigDecimal minNotional,
                                                       BigDecimal price,
                                                       BigDecimal step) {
        return requiredMinQtyForNotional(minNotional, price, step, DEFAULT_SCALE);
    }

    /** value + max(relativeBuffer, absoluteBuffer) */
    public static BigDecimal addBuffer(BigDecimal value,
                                       BigDecimal relativePct,
                                       BigDecimal absoluteMin,
                                       int scale) {
        if (!isPositive(value)) return value;

        BigDecimal rel = BigDecimal.ZERO;
        if (isPositive(relativePct)) {
            rel = value.multiply(relativePct);
        }

        BigDecimal abs = isPositive(absoluteMin) ? absoluteMin : BigDecimal.ZERO;
        BigDecimal bump = rel.max(abs);

        return value.add(bump).setScale(Math.max(0, scale), RoundingMode.HALF_UP);
    }

    /** Вычитание без ухода ниже 0 */
    public static BigDecimal subtractFloorZero(BigDecimal value, BigDecimal minus) {
        if (value == null) return null;
        if (minus == null) return value;
        BigDecimal out = value.subtract(minus);
        return out.signum() > 0 ? out : BigDecimal.ZERO;
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
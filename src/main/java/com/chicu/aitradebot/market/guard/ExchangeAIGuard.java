package com.chicu.aitradebot.market.guard;

import com.chicu.aitradebot.market.model.ExchangeLimitScope;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ExchangeAIGuard {

    /**
     * По умолчанию: НЕ увеличиваем qty, только округляем вниз.
     * Если хочешь автоподнятие qty до minNotional — используй overload ниже.
     */
    public GuardResult validateAndAdjust(
            String exchange,
            SymbolDescriptor d,
            BigDecimal qty,
            BigDecimal price,          // null для MARKET (если нет оценки)
            boolean isMarketOrder
    ) {
        return validateAndAdjust(exchange, d, qty, price, isMarketOrder, false);
    }

    /**
     * allowIncreaseQtyToMinNotional=true:
     * - если известна price
     * - и известны minNotional + stepSize
     * - и notional < minNotional
     * тогда qty поднимается до минимально допустимого (вверх по stepSize)
     */
    public GuardResult validateAndAdjust(
            String exchange,
            SymbolDescriptor d,
            BigDecimal qty,
            BigDecimal price,
            boolean isMarketOrder,
            boolean allowIncreaseQtyToMinNotional
    ) {

        final String ex = exchange != null ? exchange.trim().toUpperCase() : "UNKNOWN";

        List<String> warnings = new ArrayList<>();
        List<String> errors   = new ArrayList<>();

        BigDecimal finalQty   = qty;
        BigDecimal finalPrice = price;

        // =====================================================
        // 1) DESCRIPTOR ОТСУТСТВУЕТ
        // =====================================================
        if (d == null) {
            warnings.add("SymbolDescriptor отсутствует — проверка биржевых ограничений невозможна.");

            if (!isPositive(finalQty)) {
                errors.add("Количество (qty) должно быть > 0.");
            }

            if (!isMarketOrder && !isPositive(finalPrice)) {
                errors.add("Цена (price) должна быть > 0 для LIMIT ордера.");
            }

            GuardResult res = GuardResult.builder()
                    .ok(errors.isEmpty())
                    .adjusted(false)
                    .finalQty(finalQty)
                    .finalPrice(finalPrice)
                    .minNotional(null)
                    .computedNotional(computeNotional(finalQty, finalPrice))
                    .minNotionalScope(ExchangeLimitScope.UNKNOWN)
                    .stepSizeScope(ExchangeLimitScope.UNKNOWN)
                    .tickSizeScope(ExchangeLimitScope.UNKNOWN)
                    .maxOrdersScope(ExchangeLimitScope.UNKNOWN)
                    .warnings(warnings)
                    .errors(errors)
                    .build();

            if (!res.ok()) log.warn("🛡️ AI-GUARD BLOCK exchange={} errors={}", ex, res.errors());
            else          log.info("🛡️ AI-GUARD PASS exchange={} warnings={}", ex, res.warnings());

            return res;
        }

        // =====================================================
        // 2) SCOPES — ТОЛЬКО ИЗ SymbolDescriptor
        // =====================================================
        ExchangeLimitScope minNotionalScope = d.minNotionalScope();
        ExchangeLimitScope stepScope        = d.stepSizeScope();
        ExchangeLimitScope tickScope        = d.tickSizeScope();
        ExchangeLimitScope maxOrdersScope   = d.maxOrdersScope();

        // =====================================================
        // 3) SANITY CHECKS
        // =====================================================
        if (!isPositive(finalQty)) {
            errors.add("Количество (qty) должно быть > 0.");
            return build(false, false, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        if (!isMarketOrder && !isPositive(finalPrice)) {
            errors.add("Цена (price) должна быть > 0 для LIMIT ордера.");
            return build(false, false, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        boolean adjusted = false;

        // =====================================================
        // 4) TICK SIZE (PRICE) — округляем ВНИЗ (только если не MARKET)
        // =====================================================
        if (!isMarketOrder) {
            if (isPositive(d.tickSize())) {
                BigDecimal snapped = snapDownToStep(finalPrice, d.tickSize());
                if (snapped != null && snapped.compareTo(finalPrice) != 0) {
                    warnings.add("Цена округлена под tickSize: " + strip(finalPrice) + " → " + strip(snapped));
                    finalPrice = snapped;
                    adjusted = true;
                }
                if (!isPositive(finalPrice)) {
                    errors.add("После округления под tickSize цена стала 0 — увеличь price.");
                }
            } else {
                warnings.add("tickSize отсутствует (" + tickScope + ") — округление цены невозможно.");
            }
        }

        if (!errors.isEmpty()) {
            return build(false, adjusted, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        // =====================================================
        // 5) STEP SIZE (QTY) — округляем ВНИЗ
        // =====================================================
        if (isPositive(d.stepSize())) {
            BigDecimal snapped = snapDownToStep(finalQty, d.stepSize());
            if (snapped != null && snapped.compareTo(finalQty) != 0) {
                warnings.add("Количество округлено под stepSize: " + strip(finalQty) + " → " + strip(snapped));
                finalQty = snapped;
                adjusted = true;
            }
            if (!isPositive(finalQty)) {
                errors.add("После округления под stepSize количество стало 0 — увеличь qty.");
            }
        } else {
            warnings.add("stepSize отсутствует (" + stepScope + ") — округление qty невозможно.");
        }

        if (!errors.isEmpty()) {
            return build(false, adjusted, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        // =====================================================
        // 6) MIN NOTIONAL
        // =====================================================
        BigDecimal notional = computeNotional(finalQty, finalPrice);

        if (isPositive(d.minNotional())) {
            if (notional == null) {
                warnings.add("minNotional задан, но цена неизвестна — точная проверка невозможна.");
            } else if (notional.compareTo(d.minNotional()) < 0) {

                // Попытка авто-поднятия qty (опционально)
                if (allowIncreaseQtyToMinNotional && isPositive(d.stepSize())) {
                    BigDecimal requiredQty = computeRequiredQty(finalPrice, d.minNotional(), d.stepSize());
                    if (requiredQty != null && requiredQty.compareTo(finalQty) > 0) {
                        warnings.add("qty повышен для прохождения minNotional: "
                                + strip(finalQty) + " → " + strip(requiredQty)
                                + " (minNotional=" + strip(d.minNotional()) + ")");
                        finalQty = requiredQty;
                        adjusted = true;
                        notional = computeNotional(finalQty, finalPrice);
                    }
                }

                if (notional != null && notional.compareTo(d.minNotional()) < 0) {
                    errors.add("Сумма сделки (qty*price=" + strip(notional) +
                            ") меньше minNotional=" + strip(d.minNotional()));
                }
            }
        } else {
            warnings.add("minNotional отсутствует (" + minNotionalScope + ") — биржа может отклонить ордер.");
        }

        // =====================================================
        // 7) RESULT
        // =====================================================
        boolean ok = errors.isEmpty();

        GuardResult res = GuardResult.builder()
                .ok(ok)
                .adjusted(adjusted)
                .finalQty(finalQty)
                .finalPrice(finalPrice)
                .minNotional(d.minNotional())
                .computedNotional(notional)
                .minNotionalScope(minNotionalScope)
                .stepSizeScope(stepScope)
                .tickSizeScope(tickScope)
                .maxOrdersScope(maxOrdersScope)
                .warnings(warnings)
                .errors(errors)
                .build();

        if (!res.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK exchange={} symbol={} errors={}", ex, d.symbol(), res.errors());
        } else if (res.adjusted()) {
            log.info("🛡️ AI-GUARD ADJUST exchange={} symbol={} qty={} price={}",
                    ex, d.symbol(), strip(finalQty), strip(finalPrice));
        } else {
            log.debug("🛡️ AI-GUARD PASS exchange={} symbol={}", ex, d.symbol());
        }

        return res;
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private GuardResult build(
            boolean ok,
            boolean adjusted,
            BigDecimal qty,
            BigDecimal price,
            SymbolDescriptor d,
            List<String> warnings,
            List<String> errors,
            ExchangeLimitScope minNotionalScope,
            ExchangeLimitScope stepScope,
            ExchangeLimitScope tickScope,
            ExchangeLimitScope maxOrdersScope
    ) {
        return GuardResult.builder()
                .ok(ok)
                .adjusted(adjusted)
                .finalQty(qty)
                .finalPrice(price)
                .minNotional(d != null ? d.minNotional() : null)
                .computedNotional(computeNotional(qty, price))
                .minNotionalScope(minNotionalScope)
                .stepSizeScope(stepScope)
                .tickSizeScope(tickScope)
                .maxOrdersScope(maxOrdersScope)
                .warnings(warnings)
                .errors(errors)
                .build();
    }

    /**
     * Округление ВНИЗ под шаг:
     * floor(v / step) * step
     * (так устойчивее, чем remainder() для биржевых шагов)
     */
    private BigDecimal snapDownToStep(BigDecimal v, BigDecimal step) {
        if (v == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) return v;

        BigDecimal steps = v.divide(step, 0, RoundingMode.DOWN);
        BigDecimal snapped = steps.multiply(step);

        if (snapped.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;

        // Держим масштаб примерно как у шага (для красоты/совместимости)
        int scale = Math.max(0, step.stripTrailingZeros().scale());
        return snapped.setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Минимальный qty, чтобы price*qty >= minNotional, с округлением ВВЕРХ по stepSize.
     */
    private BigDecimal computeRequiredQty(BigDecimal price, BigDecimal minNotional, BigDecimal stepSize) {
        if (!isPositive(price) || !isPositive(minNotional) || !isPositive(stepSize)) return null;

        BigDecimal raw = minNotional.divide(price, 18, RoundingMode.UP);

        BigDecimal steps = raw.divide(stepSize, 0, RoundingMode.UP);
        BigDecimal required = steps.multiply(stepSize);

        if (required.compareTo(BigDecimal.ZERO) <= 0) return null;

        int scale = Math.max(0, stepSize.stripTrailingZeros().scale());
        return required.setScale(scale, RoundingMode.UP);
    }

    private BigDecimal computeNotional(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) return null;
        return price.multiply(qty);
    }

    private boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}

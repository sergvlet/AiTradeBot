package com.chicu.aitradebot.market.guard;

import com.chicu.aitradebot.market.model.ExchangeLimitScope;
import com.chicu.aitradebot.market.model.SymbolDescriptor;
import com.chicu.aitradebot.trade.math.QtyMath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class ExchangeAIGuard {

    /**
     * Микро-допуск на minNotional, чтобы не падать из-за копеечного rounding noise.
     * 0.02% обычно достаточно и безопасно.
     */
    private static final BigDecimal MIN_NOTIONAL_EPS_PCT = new BigDecimal("0.0002"); // 0.02%

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
     * allowIncreaseQtyToMinNotional оставлен для совместимости сигнатуры,
     * но в "прод-режиме" guard НЕ поднимает qty автоматически.
     */
    public GuardResult validateAndAdjust(
            String exchange,
            SymbolDescriptor d,
            BigDecimal qty,
            BigDecimal price,
            boolean isMarketOrder,
            boolean allowIncreaseQtyToMinNotional
    ) {
        final String ex = normalizeExchange(exchange);

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

            logResult(ex, null, res);
            return res;
        }

        final String symbol = safeSymbol(d.symbol());

        // =====================================================
        // 2) SCOPES — null-safe
        // =====================================================
        ExchangeLimitScope minNotionalScope = safeScope(d.minNotionalScope());
        ExchangeLimitScope stepScope        = safeScope(d.stepSizeScope());
        ExchangeLimitScope tickScope        = safeScope(d.tickSizeScope());
        ExchangeLimitScope maxOrdersScope   = safeScope(d.maxOrdersScope());

        // =====================================================
        // 3) SANITY CHECKS
        // =====================================================
        if (!isPositive(finalQty)) {
            errors.add("Количество (qty) должно быть > 0.");
            GuardResult res = build(false, false, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
            logResult(ex, symbol, res);
            return res;
        }

        if (!isMarketOrder && !isPositive(finalPrice)) {
            errors.add("Цена (price) должна быть > 0 для LIMIT ордера.");
            GuardResult res = build(false, false, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
            logResult(ex, symbol, res);
            return res;
        }

        boolean adjusted = false;

        // =====================================================
        // 4) TICK SIZE (PRICE) — округляем ВНИЗ (только если не MARKET)
        // =====================================================
        if (!isMarketOrder) {
            if (isPositive(d.tickSize())) {
                BigDecimal snapped = QtyMath.floorToStep(finalPrice, d.tickSize());
                if (snapped != null && snapped.compareTo(finalPrice) != 0) {
                    warnings.add("Цена округлена под tickSize: " + QtyMath.strip(finalPrice) + " → " + QtyMath.strip(snapped));
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
            GuardResult res = build(false, adjusted, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
            logResult(ex, symbol, res);
            return res;
        }

        // =====================================================
        // 5) STEP SIZE (QTY) — ТОЛЬКО ОКРУГЛЯЕМ ВНИЗ, НЕ ПОДНИМАЕМ
        // =====================================================
        if (isPositive(d.stepSize())) {

            BigDecimal snappedDown = QtyMath.floorToStep(finalQty, d.stepSize());

            // если после floor стало 0 — это значит qty < stepSize
            if (!isPositive(snappedDown)) {
                errors.add("qty меньше stepSize (" + QtyMath.strip(d.stepSize()) + ") — увеличь qty.");
            } else if (snappedDown.compareTo(finalQty) != 0) {
                warnings.add("Количество округлено под stepSize: " + QtyMath.strip(finalQty) + " → " + QtyMath.strip(snappedDown));
                finalQty = snappedDown;
                adjusted = true;
            }

            if (!isPositive(finalQty)) {
                errors.add("После округления под stepSize количество стало 0 — увеличь qty.");
            }
        } else {
            warnings.add("stepSize отсутствует (" + stepScope + ") — округление qty невозможно.");
        }

        if (!errors.isEmpty()) {
            GuardResult res = build(false, adjusted, finalQty, finalPrice, d, warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
            logResult(ex, symbol, res);
            return res;
        }

        // =====================================================
        // 6) MIN NOTIONAL (с EPS, но без автоподнятия qty)
        // =====================================================
        BigDecimal notional = computeNotional(finalQty, finalPrice);

        if (isPositive(d.minNotional())) {

            if (notional == null) {
                warnings.add("minNotional задан, но цена неизвестна — точная проверка невозможна.");
            } else {
                BigDecimal minN = d.minNotional();
                BigDecimal minWithEps = applyEps(minN);

                if (notional.compareTo(minWithEps) < 0) {
                    BigDecimal requiredQty = null;

                    // подсказка: какой qty нужен для minNotional (если можем посчитать)
                    if (isPositive(d.stepSize()) && isPositive(finalPrice)) {
                        requiredQty = computeRequiredQtyHint(finalPrice, minN, d.stepSize());
                    }

                    String msg = "Сумма сделки (qty*price=" + QtyMath.strip(notional) +
                                 ") меньше minNotional=" + QtyMath.strip(minN);
                    if (requiredQty != null) {
                        msg += " (нужно qty≥" + QtyMath.strip(requiredQty) + ")";
                    }
                    errors.add(msg);
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

        logResult(ex, symbol, res);
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
     * Подсказка requiredQty (НЕ автоподнятие):
     * requiredQty = ceil_to_step(minNotional / price)
     */
    private BigDecimal computeRequiredQtyHint(BigDecimal price, BigDecimal minNotional, BigDecimal stepSize) {
        if (!isPositive(price) || !isPositive(minNotional) || !isPositive(stepSize)) return null;

        BigDecimal raw = minNotional.divide(price, 18, RoundingMode.UP);
        return QtyMath.ceilToStepAtLeastStep(raw, stepSize);
    }

    private BigDecimal computeNotional(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) return null;
        return price.multiply(qty);
    }

    private boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private String normalizeExchange(String exchange) {
        String ex = exchange != null ? exchange.trim() : "";
        return ex.isEmpty() ? "UNKNOWN" : ex.toUpperCase(Locale.ROOT);
    }

    private String safeSymbol(String s) {
        if (s == null) return "UNKNOWN";
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? "UNKNOWN" : v;
    }

    private ExchangeLimitScope safeScope(ExchangeLimitScope s) {
        return s != null ? s : ExchangeLimitScope.UNKNOWN;
    }

    private BigDecimal applyEps(BigDecimal minNotional) {
        if (minNotional == null) return null;
        BigDecimal k = BigDecimal.ONE.subtract(MIN_NOTIONAL_EPS_PCT);
        return minNotional.multiply(k);
    }

    private void logResult(String exchange, String symbol, GuardResult res) {
        if (res == null) return;

        String sym = (symbol != null ? symbol : "UNKNOWN");

        if (!res.ok()) {
            log.warn("🛡️ AI-GUARD BLOCK exchange={} symbol={} errors={}",
                    exchange, sym, res.errors());
            return;
        }

        if (res.adjusted()) {
            log.info("🛡️ AI-GUARD ADJUST exchange={} symbol={} qty={} price={} warnings={}",
                    exchange, sym, QtyMath.strip(res.finalQty()), QtyMath.strip(res.finalPrice()), res.warnings());
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("🛡️ AI-GUARD PASS exchange={} symbol={} warnings={}",
                    exchange, sym, res.warnings());
        }
    }
}



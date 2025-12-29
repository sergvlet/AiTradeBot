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
     * Валидация + авто-округление qty/price под биржевые фильтры.
     *
     * AI-aware логика:
     * - SYMBOL   → ограничения пришли на уровне инструмента
     * - ACCOUNT  → ограничения аккаунта (Bybit)
     * - UNKNOWN  → данных нет, не блокируем жёстко
     */
    public GuardResult validateAndAdjust(
            String exchange,          // "BINANCE" / "BYBIT"
            SymbolDescriptor d,        // может быть null
            BigDecimal qty,
            BigDecimal price,          // null для MARKET
            boolean isMarketOrder
    ) {

        final String ex =
                exchange != null ? exchange.trim().toUpperCase() : "UNKNOWN";

        List<String> warnings = new ArrayList<>();
        List<String> errors   = new ArrayList<>();

        BigDecimal finalQty   = qty;
        BigDecimal finalPrice = price;

        // =====================================================
        // 1️⃣ DESCRIPTOR ОТСУТСТВУЕТ
        // =====================================================
        if (d == null) {

            warnings.add(
                    "SymbolDescriptor отсутствует — проверка биржевых ограничений невозможна."
            );

            if (finalQty == null || finalQty.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("Количество (qty) должно быть > 0.");
            }

            if (!isMarketOrder &&
                (finalPrice == null || finalPrice.compareTo(BigDecimal.ZERO) <= 0)) {
                errors.add("Цена (price) должна быть > 0 для LIMIT ордера.");
            }

            GuardResult res = GuardResult.builder()
                    .ok(errors.isEmpty())
                    .adjusted(false)
                    .finalQty(finalQty)
                    .finalPrice(finalPrice)
                    .minNotional(null)
                    .computedNotional(null)
                    .minNotionalScope(ExchangeLimitScope.UNKNOWN)
                    .stepSizeScope(ExchangeLimitScope.UNKNOWN)
                    .tickSizeScope(ExchangeLimitScope.UNKNOWN)
                    .maxOrdersScope(ExchangeLimitScope.UNKNOWN)
                    .warnings(warnings)
                    .errors(errors)
                    .build();

            if (!res.ok()) {
                log.warn("🛡️ AI-GUARD BLOCK exchange={} errors={}", ex, res.errors());
            } else {
                log.info("🛡️ AI-GUARD PASS exchange={} warnings={}", ex, res.warnings());
            }

            return res;
        }

        // =====================================================
        // 2️⃣ SCOPES — ТОЛЬКО ИЗ SymbolDescriptor
        // =====================================================
        ExchangeLimitScope minNotionalScope = d.minNotionalScope();
        ExchangeLimitScope stepScope        = d.stepSizeScope();
        ExchangeLimitScope tickScope        = d.tickSizeScope();
        ExchangeLimitScope maxOrdersScope   = d.maxOrdersScope();

        // =====================================================
        // 3️⃣ SANITY CHECKS
        // =====================================================
        if (finalQty == null || finalQty.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Количество (qty) должно быть > 0.");
            return build(false, false, finalQty, finalPrice, d,
                    warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        if (!isMarketOrder &&
            (finalPrice == null || finalPrice.compareTo(BigDecimal.ZERO) <= 0)) {

            errors.add("Цена (price) должна быть > 0 для LIMIT ордера.");
            return build(false, false, finalQty, finalPrice, d,
                    warnings, errors,
                    minNotionalScope, stepScope, tickScope, maxOrdersScope);
        }

        boolean adjusted = false;

        // =====================================================
        // 4️⃣ TICK SIZE (PRICE)
        // =====================================================
        if (!isMarketOrder) {
            if (d.tickSize() != null && d.tickSize().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal snapped =
                        snapToStep(finalPrice, d.tickSize(), RoundingMode.DOWN);

                if (snapped.compareTo(finalPrice) != 0) {
                    warnings.add(
                            "Цена округлена под tickSize: "
                            + strip(finalPrice) + " → " + strip(snapped)
                    );
                    finalPrice = snapped;
                    adjusted = true;
                }
            } else {
                warnings.add(
                        "tickSize отсутствует (" + tickScope + ") — округление цены невозможно."
                );
            }
        }

        // =====================================================
        // 5️⃣ STEP SIZE (QTY)
        // =====================================================
        if (d.stepSize() != null && d.stepSize().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal snapped =
                    snapToStep(finalQty, d.stepSize(), RoundingMode.DOWN);

            if (snapped.compareTo(finalQty) != 0) {
                warnings.add(
                        "Количество округлено под stepSize: "
                        + strip(finalQty) + " → " + strip(snapped)
                );
                finalQty = snapped;
                adjusted = true;
            }

            if (finalQty.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(
                        "После округления под stepSize количество стало 0 — увеличь qty."
                );
            }
        } else {
            warnings.add(
                    "stepSize отсутствует (" + stepScope + ") — округление qty невозможно."
            );
        }

        // =====================================================
        // 6️⃣ MIN NOTIONAL
        // =====================================================
        BigDecimal notional = null;

        if (finalPrice != null) {
            notional = finalPrice.multiply(finalQty);
        }

        if (d.minNotional() != null && d.minNotional().compareTo(BigDecimal.ZERO) > 0) {

            if (notional != null &&
                notional.compareTo(d.minNotional()) < 0) {

                errors.add(
                        "Сумма сделки (qty*price=" + strip(notional) +
                        ") меньше minNotional=" + strip(d.minNotional())
                );

            } else if (notional == null) {
                warnings.add(
                        "minNotional задан, но цена неизвестна — точная проверка невозможна."
                );
            }

        } else {
            warnings.add(
                    "minNotional отсутствует (" + minNotionalScope + ") — биржа может отклонить ордер."
            );
        }

        // =====================================================
        // 7️⃣ RESULT
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
            log.warn("🛡️ AI-GUARD BLOCK exchange={} symbol={} errors={}",
                    ex, d.symbol(), res.errors());
        } else if (res.adjusted()) {
            log.info("🛡️ AI-GUARD ADJUST exchange={} symbol={} qty={} price={}",
                    ex, d.symbol(), strip(finalQty), strip(finalPrice));
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
                .computedNotional(null)
                .minNotionalScope(minNotionalScope)
                .stepSizeScope(stepScope)
                .tickSizeScope(tickScope)
                .maxOrdersScope(maxOrdersScope)
                .warnings(warnings)
                .errors(errors)
                .build();
    }

    private BigDecimal snapToStep(BigDecimal v, BigDecimal step, RoundingMode mode) {
        if (v == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return v;
        }
        return v.divide(step, 0, mode).multiply(step);
    }

    private String strip(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }
}

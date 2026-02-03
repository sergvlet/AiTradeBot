package com.chicu.aitradebot.market.guard;

import com.chicu.aitradebot.market.model.ExchangeLimitScope;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Builder
public record GuardResult(

        boolean ok,
        boolean adjusted,                 // true если guard поправил qty/price

        BigDecimal finalQty,
        BigDecimal finalPrice,

        BigDecimal minNotional,
        BigDecimal computedNotional,

        ExchangeLimitScope minNotionalScope,
        ExchangeLimitScope stepSizeScope,
        ExchangeLimitScope tickSizeScope,
        ExchangeLimitScope maxOrdersScope,

        List<String> warnings,
        List<String> errors

) {

    // =====================================================
    // SAFETY
    // =====================================================

    public GuardResult {
        // --- списки ---
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        errors   = errors   != null ? List.copyOf(errors)   : List.of();

        // --- scopes (никогда не null) ---
        minNotionalScope = minNotionalScope != null ? minNotionalScope : ExchangeLimitScope.UNKNOWN;
        stepSizeScope    = stepSizeScope    != null ? stepSizeScope    : ExchangeLimitScope.UNKNOWN;
        tickSizeScope    = tickSizeScope    != null ? tickSizeScope    : ExchangeLimitScope.UNKNOWN;
        maxOrdersScope   = maxOrdersScope   != null ? maxOrdersScope   : ExchangeLimitScope.UNKNOWN;

        // computedNotional: если не задан, но qty/price есть — считаем
        if (computedNotional == null && finalQty != null && finalPrice != null) {
            computedNotional = finalPrice.multiply(finalQty);
        }

        // ❗ защита от логической ошибки
        if (ok && !errors.isEmpty()) {
            throw new IllegalStateException(
                    "GuardResult: ok=true but errors not empty: " + errors
            );
        }
        if (!ok && errors.isEmpty()) {
            // не жёстко валим, но подсветим, чтобы не было "ok=false без причин"
            // если не хочешь — убери
            // throw new IllegalStateException("GuardResult: ok=false but errors empty");
        }
    }

    // =====================================================
    // FACTORIES (AI-friendly)
    // =====================================================

    /** Успешно, без корректировок */
    public static GuardResult pass(BigDecimal qty, BigDecimal price) {
        return GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(qty)
                .finalPrice(price)
                .computedNotional(computeNotional(qty, price))
                .minNotionalScope(ExchangeLimitScope.UNKNOWN)
                .stepSizeScope(ExchangeLimitScope.UNKNOWN)
                .tickSizeScope(ExchangeLimitScope.UNKNOWN)
                .maxOrdersScope(ExchangeLimitScope.UNKNOWN)
                .warnings(List.of())
                .errors(List.of())
                .build();
    }

    /** Успешно, но с предупреждениями */
    public static GuardResult passWithWarnings(
            BigDecimal qty,
            BigDecimal price,
            List<String> warnings
    ) {
        return GuardResult.builder()
                .ok(true)
                .adjusted(false)
                .finalQty(qty)
                .finalPrice(price)
                .computedNotional(computeNotional(qty, price))
                .minNotionalScope(ExchangeLimitScope.UNKNOWN)
                .stepSizeScope(ExchangeLimitScope.UNKNOWN)
                .tickSizeScope(ExchangeLimitScope.UNKNOWN)
                .maxOrdersScope(ExchangeLimitScope.UNKNOWN)
                .warnings(warnings != null ? warnings : List.of())
                .errors(List.of())
                .build();
    }

    /** Заблокировано */
    public static GuardResult block(
            BigDecimal qty,
            BigDecimal price,
            List<String> errors
    ) {
        return GuardResult.builder()
                .ok(false)
                .adjusted(false)
                .finalQty(qty)
                .finalPrice(price)
                .computedNotional(computeNotional(qty, price))
                .minNotionalScope(ExchangeLimitScope.UNKNOWN)
                .stepSizeScope(ExchangeLimitScope.UNKNOWN)
                .tickSizeScope(ExchangeLimitScope.UNKNOWN)
                .maxOrdersScope(ExchangeLimitScope.UNKNOWN)
                .warnings(List.of())
                .errors(errors != null ? errors : List.of("UNKNOWN_ERROR"))
                .build();
    }

    /** Быстрый fail с одной ошибкой */
    public static GuardResult fail(BigDecimal qty, BigDecimal price, String error) {
        List<String> errs = new ArrayList<>();
        errs.add(error != null ? error : "UNKNOWN_ERROR");
        return block(qty, price, errs);
    }

    // =====================================================
    // HELPERS (для OrderService / UI / Logs)
    // =====================================================

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    public String errorsAsText() {
        return errors == null ? "" : String.join("; ", errors);
    }

    public String warningsAsText() {
        return warnings == null ? "" : String.join("; ", warnings);
    }

    private static BigDecimal computeNotional(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) return null;
        return price.multiply(qty);
    }
}

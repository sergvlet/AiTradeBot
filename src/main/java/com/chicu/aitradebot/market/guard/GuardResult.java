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
        warnings = warnings != null ? warnings : new ArrayList<>();
        errors   = errors   != null ? errors   : new ArrayList<>();

        // ❗ защита от логической ошибки
        if (ok && !errors.isEmpty()) {
            throw new IllegalStateException(
                    "GuardResult: ok=true but errors not empty: " + errors
            );
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
                .warnings(new ArrayList<>())
                .errors(new ArrayList<>())
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
                .warnings(warnings)
                .errors(new ArrayList<>())
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
                .warnings(new ArrayList<>())
                .errors(errors)
                .build();
    }

    /** Быстрый fail с одной ошибкой */
    public static GuardResult fail(BigDecimal qty, BigDecimal price, String error) {
        List<String> errs = new ArrayList<>();
        errs.add(error);
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
}

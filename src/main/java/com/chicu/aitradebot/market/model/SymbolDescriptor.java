package com.chicu.aitradebot.market.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Унифицированное описание торгового инструмента.
 *
 * ❗ Только данные (immutable)
 * ❗ Scope-поля никогда не null
 */
public record SymbolDescriptor(

        // ================= ОСНОВНОЕ =================
        String symbol,
        String baseAsset,
        String quoteAsset,

        // ================= РЫНОЧНЫЕ ДАННЫЕ =================
        BigDecimal lastPrice,
        BigDecimal priceChangePct24h,
        BigDecimal volume24h,

        // ================= ОГРАНИЧЕНИЯ =================
        BigDecimal minNotional,
        BigDecimal stepSize,
        BigDecimal tickSize,
        Integer maxOrders,

        // ================= SCOPE =================
        ExchangeLimitScope minNotionalScope,
        ExchangeLimitScope stepSizeScope,
        ExchangeLimitScope tickSizeScope,
        ExchangeLimitScope maxOrdersScope,

        // ================= ФЛАГИ =================
        boolean tradable
) {

    // =====================================================
    // CANONICAL CONSTRUCTOR (SAFETY)
    // =====================================================

    public SymbolDescriptor {
        // --- symbol обязателен (иначе всё ломается в ключах/кэше/логике) ---
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("SymbolDescriptor.symbol is blank");
        }

        // --- нормализуем строковые поля (пустые -> null, верхний регистр) ---
        symbol = normalizeUpperRequired(symbol);
        baseAsset = normalizeUpperNullable(baseAsset);
        quoteAsset = normalizeUpperNullable(quoteAsset);

        // --- нормализуем числа (пустые/<=0 для ограничений -> null) ---
        lastPrice = normalizeDecimalNullable(lastPrice);
        priceChangePct24h = normalizeDecimalNullable(priceChangePct24h);
        volume24h = normalizeDecimalNullable(volume24h);

        minNotional = normalizePositiveNullable(minNotional);
        stepSize = normalizePositiveNullable(stepSize);
        tickSize = normalizePositiveNullable(tickSize);

        if (maxOrders != null && maxOrders <= 0) {
            maxOrders = null;
        }

        // --- scopes никогда не null ---
        minNotionalScope = (minNotionalScope != null) ? minNotionalScope : ExchangeLimitScope.UNKNOWN;
        stepSizeScope    = (stepSizeScope    != null) ? stepSizeScope    : ExchangeLimitScope.UNKNOWN;
        tickSizeScope    = (tickSizeScope    != null) ? tickSizeScope    : ExchangeLimitScope.UNKNOWN;
        maxOrdersScope   = (maxOrdersScope   != null) ? maxOrdersScope   : ExchangeLimitScope.UNKNOWN;

        // --- косметика: для step/tick приводим scale к "красивому" (не обязательно, но полезно) ---
        stepSize = stripSafe(stepSize);
        tickSize = stripSafe(tickSize);
        minNotional = stripSafe(minNotional);
        lastPrice = stripSafe(lastPrice);
        priceChangePct24h = stripSafe(priceChangePct24h);
        volume24h = stripSafe(volume24h);
    }

    // =====================================================
    // FACTORY (SAFE)
    // =====================================================

    public static SymbolDescriptor of(
            String symbol,
            String baseAsset,
            String quoteAsset,
            BigDecimal lastPrice,
            BigDecimal priceChangePct24h,
            BigDecimal volume24h,
            BigDecimal minNotional,
            BigDecimal stepSize,
            BigDecimal tickSize,
            Integer maxOrders,
            boolean tradable,
            String exchange
    ) {
        final String ex  = normalizeUpperNullable(exchange);
        final String sym = normalizeUpperRequired(symbol);

        BigDecimal mn = normalizePositiveNullable(minNotional);
        BigDecimal ss = normalizePositiveNullable(stepSize);
        BigDecimal ts = normalizePositiveNullable(tickSize);
        Integer mo = (maxOrders != null && maxOrders > 0) ? maxOrders : null;

        return new SymbolDescriptor(
                sym,
                normalizeUpperNullable(baseAsset),
                normalizeUpperNullable(quoteAsset),

                normalizeDecimalNullable(lastPrice),
                normalizeDecimalNullable(priceChangePct24h),
                normalizeDecimalNullable(volume24h),

                mn,
                ss,
                ts,
                mo,

                scopeOf(ex, mn),
                scopeOf(ex, ss),
                scopeOf(ex, ts),
                scopeOf(ex, mo),

                tradable
        );
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static String normalizeUpperRequired(String s) {
        if (s == null) throw new IllegalArgumentException("symbol is null");
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) throw new IllegalArgumentException("symbol is blank");
        return t;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal normalizeDecimalNullable(BigDecimal v) {
        if (v == null) return null;
        // рыночные поля могут быть 0, поэтому НЕ обнуляем в null
        return stripSafe(v);
    }

    private static BigDecimal normalizePositiveNullable(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return stripSafe(v);
    }

    private static BigDecimal stripSafe(BigDecimal v) {
        if (v == null) return null;
        // защита от экзотики: BigDecimal может быть с scale<0 после некоторых операций
        BigDecimal x = v.stripTrailingZeros();
        if (x.scale() < 0) {
            x = x.setScale(0, RoundingMode.UNNECESSARY);
        }
        return x;
    }

    /**
     * В UI "UNKNOWN" только путает.
     * Для BINANCE/OKX/etc лимиты обычно символ-специфичны (exchangeInfo filters),
     * даже если конкретное значение не пришло (null) — показываем SYMBOL.
     * Для BYBIT часто лимиты/правила зависят от аккаунта → ACCOUNT.
     *
     * ВАЖНО: scope возвращается ВСЕГДА не-null.
     */
    private static ExchangeLimitScope scopeOf(String exchange, Object value) {
        if (value != null) return ExchangeLimitScope.SYMBOL;

        if (exchange == null || exchange.isBlank()) return ExchangeLimitScope.UNKNOWN;

        if ("BYBIT".equalsIgnoreCase(exchange)) return ExchangeLimitScope.ACCOUNT;

        // BINANCE / OKX / др.: лучше показать SYMBOL (а значение может быть "—")
        return ExchangeLimitScope.SYMBOL;
    }
}

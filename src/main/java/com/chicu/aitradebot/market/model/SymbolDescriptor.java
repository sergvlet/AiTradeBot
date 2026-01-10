package com.chicu.aitradebot.market.model;

import java.math.BigDecimal;

/**
 * Унифицированное описание торгового инструмента.
 *
 * ❗ Только данные (immutable)
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

        final String ex = normalizeUpper(exchange);
        final String sym = normalizeUpper(symbol);

        return new SymbolDescriptor(
                sym,
                normalizeUpper(baseAsset),
                normalizeUpper(quoteAsset),
                lastPrice,
                priceChangePct24h,
                volume24h,

                minNotional,
                stepSize,
                tickSize,
                maxOrders,

                scopeOf(ex, minNotional),
                scopeOf(ex, stepSize),
                scopeOf(ex, tickSize),
                scopeOf(ex, maxOrders),

                tradable
        );
    }

    private static String normalizeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    /**
     * В UI "UNKNOWN" только путает.
     * Для BINANCE/OKX/etc лимиты обычно символ-специфичны (exchangeInfo filters),
     * даже если конкретное значение не пришло (null) — показываем SYMBOL, а не UNKNOWN.
     * Для BYBIT часто лимиты/правила зависят от аккаунта → ACCOUNT.
     */
    private static ExchangeLimitScope scopeOf(String exchange, Object value) {
        if (value != null) return ExchangeLimitScope.SYMBOL;

        if (exchange == null || exchange.isBlank()) return ExchangeLimitScope.UNKNOWN;

        if ("BYBIT".equalsIgnoreCase(exchange)) return ExchangeLimitScope.ACCOUNT;

        // BINANCE / OKX / др.: лучше показать SYMBOL (а значение может быть "—")
        return ExchangeLimitScope.SYMBOL;
    }
}

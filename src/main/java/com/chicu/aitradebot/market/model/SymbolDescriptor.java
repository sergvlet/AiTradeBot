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

        return new SymbolDescriptor(
                symbol,
                baseAsset,
                quoteAsset,
                lastPrice,
                priceChangePct24h,
                volume24h,

                minNotional,
                stepSize,
                tickSize,
                maxOrders,

                scopeOf(exchange, minNotional),
                scopeOf(exchange, stepSize),
                scopeOf(exchange, tickSize),
                maxOrders != null
                        ? ExchangeLimitScope.SYMBOL
                        : ExchangeLimitScope.UNKNOWN,

                tradable
        );
    }

    private static ExchangeLimitScope scopeOf(String exchange, Object value) {
        if (value != null) return ExchangeLimitScope.SYMBOL;
        if ("BYBIT".equalsIgnoreCase(exchange)) return ExchangeLimitScope.ACCOUNT;
        return ExchangeLimitScope.UNKNOWN;
    }
}

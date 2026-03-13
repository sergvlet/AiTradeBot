package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

public record TradeClosedEvent(
        Long chatId,
        StrategyType strategyType,
        String symbol,
        String timeframe,
        String exchange,
        NetworkType network,
        Instant closedAt,
        String exitReason,
        BigDecimal pnlPct,
        BigDecimal exitPrice,

        // ✅ новые поля для датасета (опционально)
        BigDecimal entryPrice,
        BigDecimal qty,
        BigDecimal tpPrice,
        BigDecimal slPrice,
        Boolean tpHit,
        Boolean slHit
) {
    // ✅ backward-compat: старый вызов new TradeClosedEvent(... 10 args ...)
    public TradeClosedEvent(Long chatId,
                            StrategyType strategyType,
                            String symbol,
                            String timeframe,
                            String exchange,
                            NetworkType network,
                            Instant closedAt,
                            String exitReason,
                            BigDecimal pnlPct,
                            BigDecimal exitPrice) {
        this(chatId, strategyType, symbol, timeframe, exchange, network, closedAt, exitReason, pnlPct, exitPrice,
                null, null, null, null, null, null);
    }

    public TradeClosedEvent {
        symbol = normUpper(symbol);
        timeframe = normTf(timeframe);
        exchange = normUpper(exchange);
        exitReason = normReason(exitReason);
        if (closedAt == null) closedAt = Instant.now();
    }

    public boolean isWin() {
        return pnlPct != null && pnlPct.signum() >= 0;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String normTf(String s) {
        if (s == null) return "1m";
        String t = s.trim();
        return t.isEmpty() ? "1m" : t;
    }

    private static String normReason(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

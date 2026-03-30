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

        // базовые поля сделки
        BigDecimal entryPrice,
        BigDecimal qty,
        BigDecimal tpPrice,
        BigDecimal slPrice,
        Boolean tpHit,
        Boolean slHit,

        // расширенная телеметрия закрытия
        BigDecimal requestedQty,
        BigDecimal executedQty,
        BigDecimal dustRemainderQty,
        BigDecimal dustRemainderNotional,
        String closureMode,
        Boolean restoredPosition,
        Boolean ignoredForTraining
) {
    public static final String CLOSURE_MODE_FULL = "FULL";
    public static final String CLOSURE_MODE_PARTIAL = "PARTIAL";
    public static final String CLOSURE_MODE_DUST_CLOSE = "DUST_CLOSE";
    public static final String CLOSURE_MODE_PAPER = "PAPER";

    // backward-compat: самый старый вызов
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
                null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    // backward-compat: старый расширенный вызов на 16 аргументов
    public TradeClosedEvent(Long chatId,
                            StrategyType strategyType,
                            String symbol,
                            String timeframe,
                            String exchange,
                            NetworkType network,
                            Instant closedAt,
                            String exitReason,
                            BigDecimal pnlPct,
                            BigDecimal exitPrice,
                            BigDecimal entryPrice,
                            BigDecimal qty,
                            BigDecimal tpPrice,
                            BigDecimal slPrice,
                            Boolean tpHit,
                            Boolean slHit) {
        this(chatId, strategyType, symbol, timeframe, exchange, network, closedAt, exitReason, pnlPct, exitPrice,
                entryPrice, qty, tpPrice, slPrice, tpHit, slHit,
                qty, qty, null, null, null, null, null);
    }

    public TradeClosedEvent {
        symbol = normUpper(symbol);
        timeframe = normTf(timeframe);
        exchange = normUpper(exchange);
        exitReason = normReason(exitReason);
        closureMode = normUpper(closureMode);

        if (closedAt == null) closedAt = Instant.now();

        entryPrice = positiveOrNull(entryPrice);
        exitPrice = positiveOrNull(exitPrice);
        qty = positiveOrNull(qty);
        tpPrice = positiveOrNull(tpPrice);
        slPrice = positiveOrNull(slPrice);
        requestedQty = positiveOrNull(requestedQty);
        executedQty = positiveOrNull(executedQty);
        dustRemainderQty = positiveOrNull(dustRemainderQty);
        dustRemainderNotional = positiveOrNull(dustRemainderNotional);

        if (closureMode == null) {
            if (Boolean.TRUE.equals(ignoredForTraining) || positiveOrNull(dustRemainderQty) != null || positiveOrNull(dustRemainderNotional) != null) {
                closureMode = CLOSURE_MODE_DUST_CLOSE;
            } else {
                closureMode = CLOSURE_MODE_FULL;
            }
        }

        if (ignoredForTraining == null) {
            boolean partial = CLOSURE_MODE_PARTIAL.equals(closureMode);
            boolean dust = CLOSURE_MODE_DUST_CLOSE.equals(closureMode);
            ignoredForTraining = partial || dust;
        }
    }

    public boolean isWin() {
        return pnlPct != null && pnlPct.signum() >= 0;
    }

    public boolean isDustClose() {
        return CLOSURE_MODE_DUST_CLOSE.equals(closureMode)
                || dustRemainderQty != null
                || dustRemainderNotional != null;
    }

    public boolean isPartialClose() {
        return CLOSURE_MODE_PARTIAL.equals(closureMode);
    }

    public boolean isTrainable() {
        return !Boolean.TRUE.equals(ignoredForTraining) && !isPartialClose() && !isDustClose();
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
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

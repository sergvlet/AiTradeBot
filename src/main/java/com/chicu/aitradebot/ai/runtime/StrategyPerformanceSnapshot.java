package com.chicu.aitradebot.ai.runtime;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record StrategyPerformanceSnapshot(
        long chatId,
        StrategyType type,
        String exchange,
        NetworkType network,
        String symbol,
        String timeframe,
        Instant startedAt,
        Instant lastEntryAt,
        Instant lastExitAt,
        long ticks,
        long candles,
        long entries,
        long exits,
        long closedTrades,
        long wins,
        long losses,
        int winStreak,
        int lossStreak,
        long candlesWithoutEntry,
        long candlesSinceLastEntry,
        long candlesSinceLastExit,
        long candlesInPosition,
        boolean inPosition,
        BigDecimal rollingPnlPct,
        BigDecimal rollingPnlUsd,
        BigDecimal avgPnlPct,
        BigDecimal rollingWinRate,
        BigDecimal avgHoldSeconds,
        BigDecimal avgAtrPct,
        BigDecimal avgSpreadPct,
        BigDecimal avgVolumeRatio,
        String lastBlockReason,
        String dominantBlocker,
        Map<String, Integer> blockHistogram
) {

    public StrategyPerformanceSnapshot {
        exchange = safe(exchange);
        symbol = safe(symbol);
        timeframe = safe(timeframe);
        rollingPnlPct = nz(rollingPnlPct);
        rollingPnlUsd = nz(rollingPnlUsd);
        avgPnlPct = nz(avgPnlPct);
        rollingWinRate = nz(rollingWinRate);
        avgHoldSeconds = nz(avgHoldSeconds);
        avgAtrPct = nz(avgAtrPct);
        avgSpreadPct = nz(avgSpreadPct);
        avgVolumeRatio = nz(avgVolumeRatio);
        lastBlockReason = safe(lastBlockReason);
        dominantBlocker = safe(dominantBlocker);
        blockHistogram = blockHistogram == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(blockHistogram));
    }

    public boolean hasEntries() {
        return entries > 0;
    }

    public boolean hasClosedTrades() {
        return closedTrades > 0;
    }

    public boolean isStarving(long candleThreshold) {
        return !inPosition && candlesWithoutEntry >= Math.max(1L, candleThreshold);
    }

    public boolean isLosing(BigDecimal thresholdPct) {
        return hasClosedTrades() && rollingPnlPct.compareTo(nz(thresholdPct)) < 0;
    }

    public boolean isProfitable(BigDecimal thresholdPct) {
        return hasClosedTrades() && rollingPnlPct.compareTo(nz(thresholdPct)) > 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : value.setScale(8, RoundingMode.HALF_UP);
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

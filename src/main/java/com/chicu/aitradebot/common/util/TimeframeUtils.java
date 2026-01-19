package com.chicu.aitradebot.common.util;

public final class TimeframeUtils {

    private TimeframeUtils() {}

    public static long toMillis(String tf) {
        if (tf == null) return 60_000;

        return switch (tf) {
            case "1s"  -> 1_000;
            case "5s"  -> 5_000;
            case "15s" -> 15_000;

            case "1m"  -> 60_000;
            case "3m"  -> 180_000;
            case "5m"  -> 300_000;
            case "15m" -> 900_000;
            case "30m" -> 1_800_000;

            case "1h"  -> 3_600_000;
            case "4h"  -> 14_400_000;

            case "1d"  -> 86_400_000;

            default -> 60_000; // fallback
        };
    }
}

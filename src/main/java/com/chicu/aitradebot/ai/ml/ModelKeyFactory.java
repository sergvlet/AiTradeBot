package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ModelKeyFactory {

    /**
     * Пример:
     * WINDOW_SCALPING|ETHUSDT|1m|BINANCE|MAINNET|schema=xxxx
     */
    public String build(
            StrategyType type,
            String symbol,
            String timeframe,
            String exchange,
            NetworkType network,
            String schemaHash
    ) {
        String t = type != null ? type.name() : "UNKNOWN";
        String sym = norm(symbol);
        String tf = norm(timeframe);
        String ex = norm(exchange);
        String net = network != null ? network.name() : "UNKNOWN";
        String sh = (schemaHash != null && !schemaHash.isBlank()) ? schemaHash : "no_schema";

        return t + "|" + sym + "|" + tf + "|" + ex + "|" + net + "|schema=" + sh;
    }

    private static String norm(String s) {
        if (s == null) return "NULL";
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? "NULL" : x;
    }
}

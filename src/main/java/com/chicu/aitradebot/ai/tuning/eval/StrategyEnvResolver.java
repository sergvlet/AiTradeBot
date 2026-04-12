package com.chicu.aitradebot.ai.tuning.eval;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

import java.util.Locale;

public interface StrategyEnvResolver {

    Env resolve(long chatId, StrategyType type);

    record Env(String exchangeName, NetworkType networkType) {

        public Env {
            String ex = (exchangeName == null) ? "" : exchangeName.trim().toUpperCase(Locale.ROOT);
            exchangeName = ex.isEmpty() ? "BINANCE" : ex;

            networkType = (networkType != null) ? networkType : NetworkType.MAINNET;
        }

        public static Env of(String exchangeName, NetworkType networkType) {
            return new Env(exchangeName, networkType);
        }
    }
}
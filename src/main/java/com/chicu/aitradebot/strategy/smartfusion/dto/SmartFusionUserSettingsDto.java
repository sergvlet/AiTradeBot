package com.chicu.aitradebot.strategy.smartfusion.dto;

import com.chicu.aitradebot.common.enums.NetworkType;
import lombok.*;

/**
 * DTO для пользовательских параметров Smart Fusion.
 * Используется в UI / Telegram / REST API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartFusionUserSettingsDto {

    // === Market Settings ===
    private String symbol;              // пример: BTCUSDT
    private String exchange;            // BINANCE, BYBIT, OKX
    private NetworkType networkType;    // MAINNET / TESTNET
    private String timeframe;           // 1m, 5m, 15m, 1h
    private int candleLimit;            // 100 – 1000

    // === Capital & Risk ===
    private double capitalUsd;          // 100 – 100000
    private double leverage;            // 1 – 10
    private double riskPerTradePct;     // 0.5 – 5.0
    private double dailyLossLimitPct;   // 1 – 10

    // === Indicators & TP/SL ===
    private double takeProfitAtrMult;   // 1.5 – 3.0
    private double stopLossAtrMult;     // 0.8 – 2.0
    private int atrPeriod;
    private int emaFastPeriod;
    private int emaSlowPeriod;
    private double rsiBuyThreshold;
    private double rsiSellThreshold;
    private int bollingerPeriod;
    private double bollingerK;

    // === ML & Behavior ===
    private double mlBuyMin;
    private double mlSellMin;
    private boolean autoRetrain;
    private boolean reinvestProfit;
}

package com.chicu.aitradebot.strategy.smartfusion.dto;

import com.chicu.aitradebot.common.enums.NetworkType;
import lombok.*;

/**
 * DTO для динамических полей Smart Fusion в unified-настройках.
 * 1:1 повторяет SmartFusionUserSettingsDto
 * НО ИСПОЛЬЗУЕТСЯ ТОЛЬКО ДЛЯ UI
 */

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartFusionFieldsDTO {

    // === Market Settings ===
    private String symbol;
    private String exchange;
    private NetworkType networkType;
    private String timeframe;
    private int candleLimit;

    // === Capital & Risk ===
    private double capitalUsd;
    private double leverage;
    private double riskPerTradePct;
    private double dailyLossLimitPct;

    // === Indicators & TP/SL ===
    private double takeProfitAtrMult;
    private double stopLossAtrMult;
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

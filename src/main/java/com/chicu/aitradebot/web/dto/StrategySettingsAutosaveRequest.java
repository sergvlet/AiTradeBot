package com.chicu.aitradebot.web.dto;

import lombok.Data;

@Data
public class StrategySettingsAutosaveRequest {

    private Long chatId;

    /** SCALPING / RSI_EMA / ... */
    private String type;

    /** BINANCE / BYBIT / OKX / ... */
    private String exchange;

    /** MAINNET / TESTNET */
    private String network;

    /** general */
    private String scope;

    // поля General
    private String advancedControlMode;
    private String accountAsset;

    /** приходят строкой, чтобы не падать на "" */
    private String maxExposureUsd;
    private String maxExposurePct;

    private String dailyLossLimitPct;
    private Boolean reinvestProfit;
}

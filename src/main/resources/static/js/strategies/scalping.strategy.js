"use strict";

import { BaseStrategy } from "./base-strategy.js";
import { FeatureTpSl } from "../chart/features/feature-tp-sl.js";
import { FeatureTrades } from "../chart/features/feature-trades.js";
import { FeatureWindowZone } from "../chart/features/feature-window-zone.js";
import { FeatureAtr } from "../chart/features/feature-atr.js";
import { FeatureScalpingHud } from "../chart/features/feature-scalping-hud.js";
import { FeatureScalpingSeries } from "../chart/features/feature-scalping-series.js";

export class ScalpingStrategy extends BaseStrategy {

    constructor({ layers, ctx } = {}) {
        super({ ctx });

        const info = ctx?.info || {};
        const windowSize = Number(info.windowSize ?? 36);
        const trendTpPct = Number(info.trendTpPct ?? info.takeProfitPct ?? 0.28);
        const trendSlPct = Number(info.trendSlPct ?? info.stopLossPct ?? 0.16);
        const maxSpreadPct = Number(info.maxSpreadPct ?? info.spreadLimitPct ?? 0.12);
        const minAtrPct = Number(info.minAtrPct ?? 0.03);
        const maxAtrPct = Number(info.maxAtrPct ?? info.atrPctRange ?? 0.80);

        const hudDefaults = {
            symbol: info.symbol ?? ctx?.symbol ?? "BTCUSDT",
            timeframe: info.timeframe ?? ctx?.timeframe ?? "1m",
            windowSize,
            microWindowSize: Number(info.microWindowSize ?? 8),
            orderVolume: Number(info.orderVolume ?? 20),
            regimeAutoEnabled: Boolean(info.regimeAutoEnabled ?? true),
            allowTrendTrades: Boolean(info.allowTrendTrades ?? true),
            allowRangeTrades: Boolean(info.allowRangeTrades ?? true),
            allowBreakoutTrades: Boolean(info.allowBreakoutTrades ?? true),
            allowCounterTrendTrades: Boolean(info.allowCounterTrendTrades ?? false),
            trendMinScore: Number(info.trendMinScore ?? 58),
            rangeMinScore: Number(info.rangeMinScore ?? 52),
            breakoutMinScore: Number(info.breakoutMinScore ?? 61),
            maxSpreadPct,
            minAtrPct,
            maxAtrPct,
            minVolumeRatio: Number(info.minVolumeRatio ?? info.volumeRatio ?? 0.75),
            minRiskReward: Number(info.minRiskReward ?? info.riskRewardMin ?? 1.05),
            trendTpPct,
            trendSlPct,
            trendBreakEvenPct: Number(info.trendBreakEvenPct ?? 0.12),
            rangeTpPct: Number(info.rangeTpPct ?? 0.16),
            rangeSlPct: Number(info.rangeSlPct ?? 0.12),
            breakoutTpPct: Number(info.breakoutTpPct ?? 0.34),
            breakoutSlPct: Number(info.breakoutSlPct ?? 0.18),
            partialExitEnabled: Boolean(info.partialExitEnabled ?? true),
            partialExitPct: Number(info.partialExitPct ?? 0.50),
            partialExitTriggerPct: Number(info.partialExitTriggerPct ?? 0.18),
            useIntrabarConfirmation: Boolean(info.useIntrabarConfirmation ?? true)
        };

        const features = [
            new FeatureWindowZone({
                layers,
                windowSize,
                priceChangeThreshold: Number(info.pullbackEntryBufferPct ?? 0.30),
                spreadThreshold: maxSpreadPct
            }),
            new FeatureTrades({ layers }),
            new FeatureTpSl({
                layers,
                takeProfitPct: trendTpPct,
                stopLossPct: trendSlPct
            }),
            new FeatureAtr({ layers }),
            new FeatureScalpingSeries({ layers, ctx }),
            new FeatureScalpingHud({ layers, defaults: hudDefaults })
        ];

        this.registerFeatures(features);
    }
}


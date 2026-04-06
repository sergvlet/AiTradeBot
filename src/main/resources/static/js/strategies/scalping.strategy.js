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
        const takeProfitPct = Number(info.takeProfitPct ?? 0.28);
        const stopLossPct = Number(info.stopLossPct ?? 0.18);
        const windowSize = Number(info.windowSize ?? 24);
        const minImpulsePct = Number(info.minImpulsePct ?? info.priceChangeThreshold ?? 0.20);
        const spreadLimitPct = Number(info.spreadLimitPct ?? info.spreadThreshold ?? 0.03);
        const emaDiffThreshold = Number(info.emaDiffThreshold ?? 0.08);
        const volumeRatio = Number(info.volumeRatio ?? 1.15);
        const atrPctRange = Number(info.atrPctRange ?? 0.60);
        const rsiFilter = Number(info.rsiFilter ?? 52.0);
        const riskRewardMin = Number(info.riskRewardMin ?? 1.40);

        const features = [
            new FeatureWindowZone({
                layers,
                windowSize,
                priceChangeThreshold: minImpulsePct,
                spreadThreshold: spreadLimitPct
            }),
            new FeatureTrades({ layers }),
            new FeatureTpSl({
                layers,
                takeProfitPct,
                stopLossPct
            }),
            new FeatureAtr({ layers }),
            new FeatureScalpingSeries({ layers, ctx }),
            new FeatureScalpingHud({
                layers,
                defaults: {
                    windowSize,
                    minImpulsePct,
                    spreadLimitPct,
                    emaDiffThreshold,
                    volumeRatio,
                    atrPctRange,
                    rsiFilter,
                    riskRewardMin
                }
            })
        ];

        this.registerFeatures(features);
    }
}

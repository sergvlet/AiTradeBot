"use strict";

import { BaseStrategy } from "./base-strategy.js";

import { FeatureLevels }     from "../chart/features/feature-levels.js";
import { FeatureZones }      from "../chart/features/feature-zones.js";
import { FeatureTpSl }       from "../chart/features/feature-tp-sl.js";
import { FeatureTrades }     from "../chart/features/feature-trades.js";
import { FeatureWindowZone } from "../chart/features/feature-window-zone.js";
import { FeatureAtr }        from "../chart/features/feature-atr.js";

/**
 * ScalpingStrategy
 * ------------------------
 * Визуальная стратегия скальпинга.
 *
 * Использует:
 * - window zone (high / low)
 * - trade markers
 * - TP / SL
 * - ATR (как данные)
 *
 * НЕ использует:
 * - levels (fib / grid)
 * - orders
 */
export class ScalpingStrategy extends BaseStrategy {

    constructor({ layers, ctx } = {}) {
        super({ ctx });

        // ✅ Получаем настройки стратегии из ctx
        const {
            takeProfitPct = 1.0,
            stopLossPct   = 1.0,
            windowSize    = 20,
            priceChangeThreshold = 0.3,
            spreadThreshold       = 0.1
        } = ctx?.info || {};

        // -------------------------
        // FEATURES
        // -------------------------
        const features = [

            new FeatureWindowZone({
                layers,
                windowSize,
                priceChangeThreshold,
                spreadThreshold
            }),

            new FeatureTrades({ layers }),

            new FeatureTpSl({
                layers,
                takeProfitPct,
                stopLossPct
            }),

            new FeatureAtr({ layers })

            // ❌ levels / zones / orders НЕ подключаем
        ];

        this.registerFeatures(features);
    }
}

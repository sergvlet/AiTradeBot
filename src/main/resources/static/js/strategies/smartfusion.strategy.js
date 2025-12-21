"use strict";

import { BaseStrategy } from "./base-strategy.js";

import { FeatureLevels }     from "../chart/features/feature-levels.js";
import { FeatureZones }      from "../chart/features/feature-zones.js";
import { FeatureTpSl }       from "../chart/features/feature-tp-sl.js";
import { FeatureOrders }     from "../chart/features/feature-orders.js";
import { FeatureTrades }     from "../chart/features/feature-trades.js";
import { FeatureWindowZone } from "../chart/features/feature-window-zone.js";
import { FeatureAtr }        from "../chart/features/feature-atr.js";

/**
 * SmartFusionStrategy (ШАГ 10)
 * ---------------------------
 * Универсальная стратегия с максимальным набором визуальных фич.
 *
 * Использует:
 * - levels (fib / grid / adaptive)
 * - generic zones + trade zones
 * - window zone (scalping / range)
 * - TP / SL
 * - limit orders
 * - trade markers
 * - ATR / volatility (данные)
 *
 * НЕ:
 * - не рисует
 * - не знает про chart-controller
 * - не знает про WS
 */
export class SmartFusionStrategy extends BaseStrategy {

    constructor({ layers, ctx } = {}) {
        super({ ctx });

        const features = [

            // уровни (fib / grid / dynamic)
            new FeatureLevels({ layers }),

            // зоны (generic + BUY/SELL)
            new FeatureZones({ layers }),

            // окно high / low
            new FeatureWindowZone({ layers }),

            // TP / SL
            new FeatureTpSl({ layers }),

            // лимитные ордера
            new FeatureOrders({ layers }),

            // маркеры сделок
            new FeatureTrades({ layers }),

            // ATR / volatility (данные)
            new FeatureAtr({ layers })
        ];

        this.registerFeatures(features);
    }
}

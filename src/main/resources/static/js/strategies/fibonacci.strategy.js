"use strict";

import { BaseStrategy } from "./base-strategy.js";

import { FeatureLevels }     from "../chart/features/feature-levels.js";
import { FeatureZones }      from "../chart/features/feature-zones.js";
import { FeatureTpSl }       from "../chart/features/feature-tp-sl.js";
import { FeatureOrders }     from "../chart/features/feature-orders.js";
import { FeatureTrades }     from "../chart/features/feature-trades.js";
import { FeatureAtr }        from "../chart/features/feature-atr.js";

/**
 * FibonacciStrategy (ШАГ 10)
 * -------------------------
 * Визуальная стратегия Fibonacci / Grid.
 *
 * Использует:
 * - levels (fib / grid)
 * - generic zones (диапазоны)
 * - TP / SL
 * - limit orders
 * - trade markers
 * - ATR (как данные)
 *
 * НЕ использует:
 * - window zone (скальпинг)
 */
export class FibonacciStrategy extends BaseStrategy {

    constructor({ layers, ctx } = {}) {
        super({ ctx });

        const features = [

            // уровни Fibonacci / Grid
            new FeatureLevels({ layers }),

            // зоны диапазонов (generic + trade)
            new FeatureZones({ layers }),

            // TP / SL линии
            new FeatureTpSl({ layers }),

            // лимитные ордера
            new FeatureOrders({ layers }),

            // маркеры сделок
            new FeatureTrades({ layers }),

            // ATR / volatility (данные)
            new FeatureAtr({ layers })

            // ⚠ window-zone намеренно НЕ подключён
        ];

        this.registerFeatures(features);
    }
}

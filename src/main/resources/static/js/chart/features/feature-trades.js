"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureTrades (ШАГ 6)
 * --------------------
 * Отвечает ТОЛЬКО за отображение трейдов:
 *  - BUY / SELL стрелки (markers)
 *  - ограничение истории
 *
 * Источник события:
 *   ev.type === "trade"
 *
 * Контракт события:
 *   {
 *     type: "trade",
 *     trade: {
 *       side: "BUY" | "SELL",
 *       price?: number | string,
 *       qty?: number | string
 *     },
 *     time?: number   // ms или sec
 *   }
 *
 * Принципы:
 * - НЕ зависит от orders
 * - НЕ знает про стратегию
 * - использует ТОЛЬКО:
 *     layers.renderTrade(trade, timeSec)
 */
export class FeatureTrades extends FeatureBase {

    constructor({ layers, limit = 300 } = {}) {
        super({ layers });

        this.limit = limit;
        this.count = 0;
    }

    /**
     * Обработка событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "trade") return;

        const trade = ev.trade;
        if (!trade || (trade.side !== "BUY" && trade.side !== "SELL")) return;

        const timeSec = this._toTimeSec(ev.time);
        if (!Number.isFinite(timeSec)) return;

        this.callLayer("renderTrade", trade, timeSec);
        this.count++;

        // ограничение истории — marker-лимит живёт в layer-renderer
        if (this.count > this.limit) {
            this.count = this.limit;
        }

        this.log("render trade", trade, timeSec);
    }

    /**
     * Очистить ВСЕ трейды этой фичи
     */
    clear() {
        // markers живут внутри layer-renderer,
        // отдельного clearMarkers сейчас нет — и это ОК
        this.count = 0;
        this.log("clear trades");
    }

    // =====================================================
    // HELPERS
    // =====================================================

    _toTimeSec(v) {
        const n = Number(v);
        if (!Number.isFinite(n)) return NaN;
        return n > 10_000_000_000 ? Math.floor(n / 1000) : Math.floor(n);
    }
}

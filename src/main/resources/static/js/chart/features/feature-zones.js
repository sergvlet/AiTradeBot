"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureZones (ШАГ 3)
 * -------------------
 * Отвечает ТОЛЬКО за зоны:
 *  - generic zone
 *  - trade zone (BUY / SELL)
 *
 * Источники событий:
 *  - ev.type === "zone"        -> generic zone
 *  - ev.type === "trade_zone"  -> trade zone
 *
 * Контракты событий:
 *  {
 *    type: "zone",
 *    zone: { top, bottom, color? }
 *  }
 *
 *  {
 *    type: "trade_zone",
 *    tradeZone: { top, bottom, side: "BUY" | "SELL" }
 *  }
 *
 * Принципы:
 * - не знает про стратегию
 * - не знает про уровни / TP / ордера
 * - вызывает ТОЛЬКО:
 *     layers.renderZone / layers.clearZone
 *     layers.renderTradeZone / layers.clearTradeZone
 */
export class FeatureZones extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        this.hasGenericZone = false;
        this.hasTradeZone   = false;
    }

    /**
     * Главный обработчик событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || !ev.type) return;

        switch (ev.type) {

            // -------------------------
            // 🟠 GENERIC ZONE
            // -------------------------
            case "zone": {
                const zone = ev.zone;

                if (!zone) {
                    this.clearGeneric();
                    return;
                }

                this.callLayer("renderZone", zone);
                this.hasGenericZone = true;

                this.log("render generic zone", zone);
                break;
            }

            // -------------------------
            // 🔴 BUY / SELL ZONE
            // -------------------------
            case "trade_zone": {
                const tradeZone = ev.tradeZone;

                if (!tradeZone) {
                    this.clearTrade();
                    return;
                }

                this.callLayer("renderTradeZone", tradeZone);
                this.hasTradeZone = true;

                this.log("render trade zone", tradeZone);
                break;
            }

            default:
                break;
        }
    }

    /**
     * Очистить ТОЛЬКО generic zone
     */
    clearGeneric() {
        if (!this.hasGenericZone) return;

        this.callLayer("clearZone");
        this.hasGenericZone = false;

        this.log("clear generic zone");
    }

    /**
     * Очистить ТОЛЬКО trade zone
     */
    clearTrade() {
        if (!this.hasTradeZone) return;

        this.callLayer("clearTradeZone");
        this.hasTradeZone = false;

        this.log("clear trade zone");
    }

    /**
     * Очистка ВСЕХ зон этой фичи
     */
    clear() {
        this.clearGeneric();
        this.clearTrade();
    }
}

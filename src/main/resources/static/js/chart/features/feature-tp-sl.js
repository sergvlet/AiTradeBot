"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureTpSl (ШАГ 4)
 * ------------------
 * Отвечает ТОЛЬКО за TP / SL.
 *
 * Источник события:
 *   ev.type === "tp_sl"
 *
 * Контракт события:
 *   {
 *     type: "tp_sl",
 *     tpSl: {
 *       tp?: number | string,
 *       sl?: number | string
 *     }
 *   }
 *
 * Принципы:
 * - не знает про стратегию
 * - не знает про зоны / уровни / ордера
 * - использует ТОЛЬКО:
 *     layers.renderTpSl
 *     layers.clearTpSl
 */
export class FeatureTpSl extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        this.hasTpSl = false;
    }

    /**
     * Обработка событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "tp_sl") return;

        const tpSl = ev.tpSl;

        if (!tpSl) {
            this.clear();
            return;
        }

        this.callLayer("renderTpSl", tpSl);
        this.hasTpSl = true;

        this.log("render tp/sl", tpSl);
    }

    /**
     * Очистка TP / SL
     */
    clear() {
        if (!this.hasTpSl) return;

        this.callLayer("clearTpSl");
        this.hasTpSl = false;

        this.log("clear tp/sl");
    }
}

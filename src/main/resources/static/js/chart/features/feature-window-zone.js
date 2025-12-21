"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureWindowZone (ШАГ 7)
 * ------------------------
 * Отвечает ТОЛЬКО за окно high / low (window zone).
 *
 * Источник события:
 *   ev.type === "window_zone"
 *
 * Контракт события:
 *   {
 *     type: "window_zone",
 *     windowZone: {
 *       high: number | string,
 *       low:  number | string
 *     }
 *   }
 *
 * Принципы:
 * - одна зона
 * - легко включать / выключать
 * - не знает про стратегию, уровни, TP, ордера
 * - использует ТОЛЬКО:
 *     layers.renderWindowZone
 *     layers.clearWindowZone
 */
export class FeatureWindowZone extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        this.active = false;
    }

    /**
     * Обработка событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "window_zone") return;

        const zone = ev.windowZone;

        if (!zone) {
            this.clear();
            return;
        }

        this.callLayer("renderWindowZone", zone);
        this.active = true;

        this.log("render window zone", zone);
    }

    /**
     * Очистка window zone
     */
    clear() {
        if (!this.active) return;

        this.callLayer("clearWindowZone");
        this.active = false;

        this.log("clear window zone");
    }
}

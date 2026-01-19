"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureLevels (ШАГ 2)
 * --------------------
 * Отвечает ТОЛЬКО за уровни (levels).
 *
 * Источник данных:
 *   ev.type === "levels"
 *
 * Контракт события:
 *   {
 *     type: "levels",
 *     levels: Array<number | { price }>
 *   }
 *
 * Принципы:
 * - не знает про стратегию
 * - не знает про ордера
 * - не знает про зоны
 * - дергает ТОЛЬКО layers.renderLevels / layers.clearLevels
 */
export class FeatureLevels extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        // текущее состояние (нужно для корректного clear)
        this.hasLevels = false;
    }

    /**
     * Обработка входящих событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "levels") return;

        const levels = Array.isArray(ev.levels) ? ev.levels : [];

        if (levels.length === 0) {
            this.clear();
            return;
        }

        this.callLayer("renderLevels", levels);
        this.hasLevels = true;

        this.log("render", levels);
    }

    /**
     * Очистка уровней
     */
    clear() {
        if (!this.hasLevels) return;

        this.callLayer("clearLevels");
        this.hasLevels = false;

        this.log("clear");
    }
}

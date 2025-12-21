"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureAtr (ШАГ 8)
 * -----------------
 * Отвечает ТОЛЬКО за ATR / volatility.
 *
 * Источник события:
 *   ev.type === "atr"
 *
 * Контракт события:
 *   {
 *     type: "atr",
 *     atr: {
 *       atr?: number,
 *       volatilityPct?: number
 *     }
 *   }
 *
 * Принципы:
 * - фича НИЧЕГО не решает
 * - не инициирует сделки
 * - может использоваться стратегией
 * - хранит последнее значение
 * - использует ТОЛЬКО:
 *     layers.renderAtr
 *     layers.clearAtr
 */
export class FeatureAtr extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        this.lastAtr = null;
        this.lastVolatilityPct = null;
        this.active = false;
    }

    /**
     * Обработка событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "atr") return;

        const data = ev.atr;

        if (!data) {
            this.clear();
            return;
        }

        const atr = Number(data.atr);
        const vol = Number(data.volatilityPct);

        if (Number.isFinite(atr)) {
            this.lastAtr = atr;
        }

        if (Number.isFinite(vol)) {
            this.lastVolatilityPct = vol;
        }

        // визуализация (если layer-renderer поддерживает)
        this.callLayer("renderAtr", {
            atr: this.lastAtr,
            volatilityPct: this.lastVolatilityPct
        });

        this.active = true;

        this.log("update atr", this.lastAtr, this.lastVolatilityPct);
    }

    /**
     * Очистка ATR / volatility
     */
    clear() {
        if (!this.active) return;

        this.lastAtr = null;
        this.lastVolatilityPct = null;
        this.active = false;

        this.callLayer("clearAtr");

        this.log("clear atr");
    }

    // =====================================================
    // PUBLIC API (для стратегии)
    // =====================================================

    /**
     * Получить последнее значение ATR
     */
    getAtr() {
        return this.lastAtr;
    }

    /**
     * Получить последнюю волатильность (%)
     */
    getVolatilityPct() {
        return this.lastVolatilityPct;
    }
}

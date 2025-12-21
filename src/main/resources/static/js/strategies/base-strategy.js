"use strict";

/**
 * BaseStrategy (ШАГ 9)
 * -------------------
 * Адаптер между:
 *   - источником событий (WS / REST / replay)
 *   - набором feature
 *
 * НЕ:
 * - рисует
 * - знает про chart-controller
 * - знает про layer-renderer детали
 *
 * ДЕЛАЕТ:
 * - регистрирует features
 * - прокидывает события
 * - управляет lifecycle (clear)
 */
export class BaseStrategy {

    constructor({ ctx } = {}) {
        /**
         * Контекст стратегии (пассивные данные)
         * Пример:
         * {
         *   chatId,
         *   strategyType,
         *   symbol,
         *   timeframe
         * }
         */
        this.ctx = ctx || {};

        /** @type {Array<Object>} */
        this.features = [];

        this.debug = false;
        this.name = this.constructor?.name || "Strategy";
    }

    // =====================================================
    // FEATURE REGISTRATION
    // =====================================================

    /**
     * Зарегистрировать features стратегии.
     * Вызывается ОДИН РАЗ при инициализации.
     *
     * @param {Array<Object>} features
     */
    registerFeatures(features = []) {
        if (!Array.isArray(features)) return;

        for (const f of features) {
            if (!f || typeof f.onEvent !== "function") continue;
            this.features.push(f);
        }

        if (this.debug) {
            console.log(`🧠 ${this.name}: registered features`, this.features);
        }
    }

    // =====================================================
    // EVENT PIPELINE
    // =====================================================

    /**
     * Главная точка входа событий.
     * Сюда попадает ВСЁ:
     *  - candle
     *  - price
     *  - levels
     *  - tp_sl
     *  - trade
     *  - order
     *  - atr
     *  - window_zone
     *
     * Strategy НИЧЕГО не фильтрует —
     * каждая feature сама решает, что ей нужно.
     *
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev) return;

        for (const f of this.features) {
            try {
                f.onEvent(ev);
            } catch (e) {
                // одна фича не должна ломать остальные
                console.warn(`⚠ ${this.name}: feature error`, e);
            }
        }
    }

    // =====================================================
    // LIFECYCLE
    // =====================================================

    /**
     * Очистить состояние стратегии (через features)
     */
    clear() {
        for (const f of this.features) {
            try {
                f.clear?.();
            } catch (e) {
                console.warn(`⚠ ${this.name}: feature clear error`, e);
            }
        }

        if (this.debug) {
            console.log(`🧹 ${this.name}: cleared`);
        }
    }

    // =====================================================
    // OPTIONAL HOOKS (на будущее)
    // =====================================================

    /**
     * Хук при старте стратегии (опционально)
     */
    onStart() {}

    /**
     * Хук при остановке стратегии (опционально)
     */
    onStop() {}
}

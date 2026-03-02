"use strict";

/**
 * BaseStrategy (ШАГ 11)
 * -------------------
 * Адаптер между:
 *   - источником событий (WS / REST / replay)
 *   - набором feature
 *
 * ДОПОЛНИТЕЛЬНО:
 * ✔ хранит read-only runtime-состояния (cooldown и т.п.)
 */
export class BaseStrategy {

    constructor({ ctx } = {}) {

        this.ctx = ctx || {};

        /** @type {Array<Object>} */
        this.features = [];

        this.debug = false;
        this.name = this.constructor?.name || "Strategy";

        // =============================
        // RUNTIME STATE (READ-ONLY)
        // =============================
        this.cooldownSeconds = null;
        this.cooldownUpdatedAt = null;
    }

    // =====================================================
    // FEATURE REGISTRATION
    // =====================================================

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
     */
    onEvent(ev) {
        if (!ev) return;

        // -----------------------------
        // SNAPSHOT / REPLAY LAYERS (INIT)
        // -----------------------------
        // StrategyDashboard.js отправляет это после REST snapshot:
        //   strategy.onEvent({ type: "layers", layers: {...} })
        // Раньше это игнорировалось feature-слоями, поэтому при refresh
        // линии/зоны могли пропадать до следующего live-события.
        if (ev.type === "layers" && ev.layers && typeof ev.layers === "object") {
            const L = ev.layers || {};

            // levels: [{price:...}, ...]
            if (Array.isArray(L.levels)) {
                this.onEvent({ type: "levels", levels: L.levels });
            }

            // zone: {top,bottom,color?}
            if (L.zone && typeof L.zone === "object") {
                this.onEvent({ type: "zone", zone: L.zone });
            }

            // tpSl: {tp?, sl?}
            const tpSl = (L.tpSl && typeof L.tpSl === "object") ? L.tpSl
                : (L.tp_sl && typeof L.tp_sl === "object") ? L.tp_sl
                    : null;
            if (tpSl) {
                this.onEvent({ type: "tp_sl", tpSl });
            }

            return; // исходный "layers" дальше не прокидываем
        }

        // -----------------------------
        // SIGNAL PARSING (SYSTEM)
        // -----------------------------
        if (ev.type === "signal" && ev.action === "hold") {
            this._handleHoldSignal(ev);
        }

        // -----------------------------
        // FORWARD TO FEATURES
        // -----------------------------
        for (const f of this.features) {
            try {
                f.onEvent(ev);
            } catch (e) {
                console.warn(`⚠ ${this.name}: feature error`, e);
            }
        }
    }

    // =====================================================
    // SIGNAL HANDLERS
    // =====================================================

    _handleHoldSignal(ev) {
        if (typeof ev.reason !== "string") return;

        // ожидаемый формат: "cooldown 12s"
        const m = ev.reason.match(/^cooldown\s+(\d+)s$/i);
        if (!m) return;

        this.cooldownSeconds = Number(m[1]);
        this.cooldownUpdatedAt = Date.now();

        if (this.debug) {
            console.log(`⏳ ${this.name}: cooldown ${this.cooldownSeconds}s`);
        }
    }

    // =====================================================
    // READ-ONLY API (для UI)
    // =====================================================

    /**
     * @returns {number|null}
     */
    getCooldownSeconds() {
        return this.cooldownSeconds;
    }

    // =====================================================
    // LIFECYCLE
    // =====================================================

    clear() {
        for (const f of this.features) {
            try {
                f.clear?.();
            } catch (e) {
                console.warn(`⚠ ${this.name}: feature clear error`, e);
            }
        }

        // сбрасываем runtime-индикаторы
        this.cooldownSeconds = null;
        this.cooldownUpdatedAt = null;

        if (this.debug) {
            console.log(`🧹 ${this.name}: cleared`);
        }
    }

    // OPTIONAL HOOKS
    onStart() {}
    onStop() {}
}

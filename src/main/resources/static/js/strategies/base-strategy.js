"use strict";

export class BaseStrategy {

    constructor({ ctx } = {}) {
        this.ctx = ctx || {};
        this.features = [];
        this.debug = false;
        this.name = this.constructor?.name || "Strategy";

        this.cooldownSeconds = null;
        this.cooldownUpdatedAt = null;
    }

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

    setInfo(info = {}) {
        this.ctx = this.ctx || {};
        this.ctx.info = (info && typeof info === "object") ? info : {};
    }

    onEvent(ev) {
        if (!ev) return;

        if (ev.type === "layers" && ev.layers && typeof ev.layers === "object") {
            const L = ev.layers || {};

            if (Array.isArray(L.levels)) {
                this.onEvent({ type: "levels", levels: L.levels });
            }

            if (L.zone && typeof L.zone === "object") {
                this.onEvent({ type: "zone", zone: L.zone });
            }

            const tpSl = (L.tpSl && typeof L.tpSl === "object") ? L.tpSl
                : (L.tp_sl && typeof L.tp_sl === "object") ? L.tp_sl
                    : null;
            if (tpSl) {
                this.onEvent({ type: "tp_sl", tpSl });
            }

            const windowZone = (L.windowZone && typeof L.windowZone === "object") ? L.windowZone
                : (L.window_zone && typeof L.window_zone === "object") ? L.window_zone
                    : null;
            if (windowZone) {
                this.onEvent({ type: "window_zone", windowZone });
            }

            if (Array.isArray(L.priceLines)) {
                for (const pl of L.priceLines) {
                    this.onEvent({ type: "price_line", priceLine: pl });
                }
            }

            if (Array.isArray(L.trades)) {
                for (const tr of L.trades) {
                    this.onEvent({ type: "trade", trade: tr, time: tr?.time });
                }
            }

            return;
        }

        if (ev.type === "signal" && ev.action === "hold") {
            this._handleHoldSignal(ev);
        }

        for (const f of this.features) {
            try {
                f.onEvent(ev);
            } catch (e) {
                console.warn(`⚠ ${this.name}: feature error`, e);
            }
        }
    }

    _handleHoldSignal(ev) {
        if (typeof ev.reason !== "string") return;
        const m = ev.reason.match(/^cooldown\s+(\d+)s$/i);
        if (!m) return;

        this.cooldownSeconds = Number(m[1]);
        this.cooldownUpdatedAt = Date.now();

        if (this.debug) {
            console.log(`⏳ ${this.name}: cooldown ${this.cooldownSeconds}s`);
        }
    }

    getCooldownSeconds() {
        return this.cooldownSeconds;
    }

    clear() {
        for (const f of this.features) {
            try {
                f.clear?.();
            } catch (e) {
                console.warn(`⚠ ${this.name}: feature clear error`, e);
            }
        }

        this.cooldownSeconds = null;
        this.cooldownUpdatedAt = null;

        if (this.debug) {
            console.log(`🧹 ${this.name}: cleared`);
        }
    }

    onStart() {}
    onStop() {}
}

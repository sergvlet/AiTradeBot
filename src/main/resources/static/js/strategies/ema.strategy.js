"use strict";

import { BaseStrategy } from "./base-strategy.js";

export class EmaStrategy extends BaseStrategy {

    constructor({ layers, ctx } = {}) {
        super({ ctx });
        this.layers = layers;
        this.history = [];
        this.emaFast = 9;
        this.emaSlow = 21;
        this.setInfo(ctx?.info || {});
    }

    setInfo(info = {}) {
        super.setInfo(info);

        const fast = Number(info?.emaFast);
        const slow = Number(info?.emaSlow);

        this.emaFast = Number.isFinite(fast) && fast > 0 ? Math.max(1, Math.floor(fast)) : 9;
        this.emaSlow = Number.isFinite(slow) && slow > this.emaFast ? Math.floor(slow) : Math.max(this.emaFast + 1, 21);

        if (this.history.length) {
            this.renderEmaHistory();
        }
    }

    onCandleHistory(candlesData) {
        if (!Array.isArray(candlesData)) return;
        this.history = candlesData.map(c => ({
            time: this._normalizeTime(c?.time),
            close: Number(c?.close)
        })).filter(c => Number.isFinite(c.time) && Number.isFinite(c.close) && c.close > 0);

        this.renderEmaHistory();
    }

    onEvent(ev) {
        if (!ev) return;

        if (ev.type === "layers") {
            super.onEvent(ev);
            return;
        }

        switch (ev.type) {
            case "tp_sl":
                if (ev.tpSl) this.layers?.renderTpSl?.(ev.tpSl);
                else this.layers?.clearTpSl?.();
                break;

            case "price_line": {
                const pl = ev.priceLine || ev;
                if (pl?.name && pl?.price != null) this.layers?.renderPriceLine?.(pl);
                else this.layers?.clearPriceLines?.();
                break;
            }

            case "trade": {
                const trade = ev.trade || ev;
                const timeSec = this._normalizeTime(ev.time ?? trade?.time);
                if (Number.isFinite(timeSec)) this.layers?.renderTrade?.(trade, timeSec);
                break;
            }

            default:
                break;
        }

        super.onEvent(ev);
    }

    clear() {
        this.layers?.clearTpSl?.();
        this.layers?.clearPriceLines?.();
        this.layers?.clearEmaSeries?.();
        super.clear();
    }

    renderEmaHistory() {
        if (!this.layers?.renderEmaSeries || !Array.isArray(this.history) || !this.history.length) return;

        const fastData = this._buildEma(this.history, this.emaFast);
        const slowData = this._buildEma(this.history, this.emaSlow);
        this.layers.renderEmaSeries({ fastData, slowData });
    }

    _buildEma(history, period) {
        if (!Array.isArray(history) || !history.length || !Number.isFinite(period) || period <= 0) return [];

        const alpha = 2 / (period + 1);
        let ema = null;
        const out = [];

        for (const candle of history) {
            const close = Number(candle?.close);
            const time = this._normalizeTime(candle?.time);
            if (!Number.isFinite(close) || close <= 0 || !Number.isFinite(time)) continue;

            ema = (ema == null) ? close : (close * alpha) + (ema * (1 - alpha));
            out.push({ time, value: Number(ema.toFixed(8)) });
        }

        return out;
    }

    _normalizeTime(t) {
        if (t == null) return NaN;
        const n = Number(t);
        if (!Number.isFinite(n)) return NaN;
        return n > 1e11 ? Math.floor(n / 1000) : Math.floor(n);
    }
}

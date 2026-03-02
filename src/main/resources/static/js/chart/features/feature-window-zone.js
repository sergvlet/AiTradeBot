"use strict";

import { FeatureBase } from "./feature-base.js";

export class FeatureWindowZone extends FeatureBase {

    constructor({ layers, windowSize = 20, priceChangeThreshold = 0.3, spreadThreshold = 0.1 } = {}) {
        super({ layers });

        this.windowSize = windowSize;
        this.priceChangeThreshold = priceChangeThreshold;
        this.spreadThreshold = spreadThreshold;

        this.active = false;
        this.chart = null;

        this.candlesData = [];
        this.lastZone = null;       // ✅ запоминаем последнюю зону
    }

    bindChart(chart) {
        this.chart = chart;
    }

    // =====================================================
    // HISTORY
    // =====================================================
    onCandleHistory(candles) {
        if (!Array.isArray(candles) || candles.length < this.windowSize) return;

        this.candlesData = candles;

        const slice = candles.slice(-this.windowSize);

        const highs = slice.map(c => Number(c.high)).filter(Number.isFinite);
        const lows  = slice.map(c => Number(c.low)).filter(Number.isFinite);

        if (!highs.length || !lows.length) return;

        const high = Math.max(...highs);
        const low  = Math.min(...lows);

        if (!Number.isFinite(high) || !Number.isFinite(low) || low >= high) return;

        // ✅ рисуем по истории (мгновенно после загрузки страницы)
        const zone = { high, low, candlesData: candles };
        this.lastZone = { high, low };

        this.callLayer("renderWindowZone", zone);
        this.active = true;

        this.log("draw window zone (history)", zone);
    }

    // =====================================================
    // LIVE EVENTS
    // =====================================================
    onEvent(ev) {
        if (!ev || ev.type !== "window_zone") return;

        const zone = ev.windowZone;

        // ✅ допускаем BigDecimal как string
        const hi = zone ? Number(zone.high) : NaN;
        const lo = zone ? Number(zone.low) : NaN;

        if (!zone || !Number.isFinite(hi) || !Number.isFinite(lo) || lo >= hi) {
            this.lastZone = null;
            this.clear();
            return;
        }

        this.lastZone = { high: hi, low: lo };

        this.callLayer("renderWindowZone", {
            ...zone,
            high: hi,
            low: lo,
            candlesData: (Array.isArray(this.candlesData) && this.candlesData.length) ? this.candlesData : null
        });

        this.active = true;
        this.log("render window zone (event)", { high: hi, low: lo });
    }

    // =====================================================
    // CLEAR
    // =====================================================
    clear() {
        if (!this.active) return;

        this.callLayer("clearWindowZone");
        this.active = false;

        this.log("clear window zone");
    }
}
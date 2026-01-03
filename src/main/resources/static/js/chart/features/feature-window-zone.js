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
    }

    bindChart(chart) {
        this.chart = chart;
    }

    // =====================================================
    // HISTORY
    // =====================================================
    onCandleHistory(candles) {
        if (!Array.isArray(candles) || candles.length < this.windowSize) return;

        this.candlesData = candles; // сохранить для передачи в render

        const slice = candles.slice(-this.windowSize);

        const highs = slice.map(c => Number(c.high)).filter(Number.isFinite);
        const lows  = slice.map(c => Number(c.low)).filter(Number.isFinite);

        if (!highs.length || !lows.length) return;

        const high = Math.max(...highs);
        const low  = Math.min(...lows);

        const spread = high - low;
        if (!Number.isFinite(high) || !Number.isFinite(low) || spread <= 0) return;

        const zone = {
            high,
            low,
            candlesData: candles // 👈 ОБЯЗАТЕЛЬНО передать сюда
        };

        this.callLayer("renderWindowZone", zone);
        this.active = true;

        this.log("draw window zone", zone);
    }


    // =====================================================
    // LIVE EVENTS
    // =====================================================
    onEvent(ev) {
        if (!ev || ev.type !== "window_zone") return;

        const zone = ev.windowZone;

        if (
            !zone ||
            !Number.isFinite(zone.high) ||
            !Number.isFinite(zone.low) ||
            zone.low >= zone.high
        ) {
            this.clear();
            return;
        }

        // 🔴 candlesData ОБЯЗАТЕЛЬНО
        this.callLayer("renderWindowZone", {
            high: zone.high,
            low: zone.low,
            candlesData: this.candlesData
        });

        this.active = true;
        this.log("render window zone (event)", zone);
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

"use strict";

export class LayerRenderer {

    constructor(chart, candleSeries) {
        this.chart = chart;
        this.candles = candleSeries;

        this.levelLines = [];
        this.activeLevelPrice = null;
        this.zoneLines = [];
        this.zoneBackground = null;
        this._lastZone = null;
        this.tradeZoneLines = [];
        this.tpLine = null;
        this.slLine = null;
        this.priceLines = new Map();

        this.windowHighLine = null;
        this.windowLowLine = null;
        this.windowZoneBackground = null;
        this._lastWindowZone = null;

        this.emaFastSeries = null;
        this.emaSlowSeries = null;
        this._lastEmaSeries = null;

        this.dynamicSeries = new Map();
        this._lastSeriesBundle = null;

        this.lastAtr = null;
        this.lastVolatilityPct = null;

        this.orderLines = new Map();
        this.markers = [];
        this._lastMarkers = [];

        this.magnetTarget = null;
        this.magnetStrength = 0;
        this.currentPrice = null;
        this.candlesData = [];
    }

    bind(chart, candleSeries) {
        this.chart = chart;
        this.candles = candleSeries;
        this.restoreZone();
        this.restoreWindowZone();
        this.restoreEmaSeries();
        this.restoreSeriesBundle();
        this.restoreMarkers();
    }

    restoreZone() {
        if (!this._lastZone) return;
        this.renderZone(this._lastZone);
    }

    restoreWindowZone() {
        if (!this._lastWindowZone) return;
        this.renderWindowZone(this._lastWindowZone);
    }

    restoreEmaSeries() {
        if (!this._lastEmaSeries) return;
        this.renderEmaSeries(this._lastEmaSeries);
    }

    restoreSeriesBundle() {
        if (!this._lastSeriesBundle) return;
        this.renderSeriesBundle(this._lastSeriesBundle);
    }

    restoreMarkers() {
        if (!Array.isArray(this._lastMarkers) || !this._lastMarkers.length) return;
        this.markers = [...this._lastMarkers];
        try {
            this.candles?.setMarkers?.(this.markers);
        } catch {}
    }

    _parsePrice(v) {
        if (v == null) return NaN;
        if (typeof v === "number") return v;
        if (typeof v === "string") return Number(v.replace(",", "."));
        if (typeof v === "object") {
            const p = v.price ?? v.value;
            return Number(p);
        }
        return NaN;
    }

    _safeRemovePriceLine(line) {
        if (!line) return;
        try { this.candles?.removePriceLine?.(line); } catch {}
    }

    _safeRemoveSeries(series) {
        if (!series) return;
        try { this.chart?.removeSeries?.(series); } catch {}
    }

    _normalizeTime(t) {
        if (t == null) return NaN;
        if (typeof t === "object") return NaN;
        const n = Number(t);
        if (!Number.isFinite(n)) return NaN;
        if (n > 1e11) return Math.floor(n / 1000);
        return Math.floor(n);
    }

    _createLineSeries(color) {
        if (typeof this.chart?.addLineSeries !== "function") return null;
        try {
            return this.chart.addLineSeries({
                color,
                lineWidth: 2,
                priceLineVisible: false,
                lastValueVisible: false,
                crosshairMarkerVisible: false
            });
        } catch {
            return null;
        }
    }

    _resolveSeriesColor(name, fallback) {
        const key = String(name || "").toUpperCase();
        if (key === "WINDOW_MID") return "#94a3b8";
        if (key === "RSI_VIEW") return "#facc15";
        if (key === "VOL_VIEW") return "#38bdf8";
        if (key === "SPREAD_VIEW") return "#f87171";
        if (key === "SCORE_VIEW") return "#e879f9";
        if (key === "EMA_FAST") return "#f59e0b";
        if (key === "EMA_SLOW") return "#60a5fa";
        return fallback || "#94a3b8";
    }

    _isDebugMetricName(name) {
        const key = String(name || "").toUpperCase();
        return key === "RSI_VIEW" || key === "VOL_VIEW" || key === "SPREAD_VIEW" || key === "SCORE_VIEW";
    }

    _isMainPaneSeriesName(name) {
        const key = String(name || "").toUpperCase();
        return key === "EMA_FAST" || key === "EMA_SLOW" || key === "WINDOW_MID";
    }

    _shouldSkipPriceLine(name) {
        const key = String(name || "").toUpperCase();
        return this._isDebugMetricName(key)
            || key === "EMA_FAST"
            || key === "EMA_SLOW"
            || key === "WINDOW_MID";
    }

    _removePriceLineByName(name) {
        const key = String(name || "").toUpperCase();
        if (!this.priceLines.has(key)) return;
        this._safeRemovePriceLine(this.priceLines.get(key));
        this.priceLines.delete(key);
    }

    _getTimeBounds() {
        let fromTime = NaN;
        let toTime = NaN;

        if (Array.isArray(this.candlesData) && this.candlesData.length) {
            fromTime = this._normalizeTime(this.candlesData[0]?.time);
            toTime = this._normalizeTime(this.candlesData.at(-1)?.time);
        }

        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) {
            const vr = this.chart?.timeScale?.().getVisibleRange?.();
            if (vr && vr.from != null && vr.to != null) {
                fromTime = this._normalizeTime(vr.from);
                toTime = this._normalizeTime(vr.to);
            }
        }

        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) return null;
        if (toTime < fromTime) {
            const tmp = fromTime;
            fromTime = toTime;
            toTime = tmp;
        }

        return { fromTime, toTime };
    }

    _inferStepSec() {
        if (Array.isArray(this.candlesData) && this.candlesData.length >= 2) {
            const t1 = this._normalizeTime(this.candlesData.at(-1)?.time);
            const t0 = this._normalizeTime(this.candlesData.at(-2)?.time);
            const dt = Math.abs(t1 - t0);
            if (Number.isFinite(dt) && dt > 0) {
                return dt;
            }
        }
        return 60;
    }

    _makeBandFillColor(color, fallback) {
        const c = String(color || "").trim();
        if (!c) return fallback;
        if (c.startsWith("rgba(")) return c;
        return fallback;
    }

    _renderBand(top, bottom, fillColor, targetField) {
        if (!Number.isFinite(top) || !Number.isFinite(bottom)) return;
        if (typeof this.chart?.addBaselineSeries !== "function") return;

        const bounds = this._getTimeBounds();
        if (!bounds) return;

        let { fromTime, toTime } = bounds;
        let step = this._inferStepSec();
        const range = Math.max(0, toTime - fromTime);
        const maxPoints = 800;
        if (step <= 0) step = 60;
        if (range > 0 && Math.floor(range / step) > maxPoints) {
            step = Math.max(1, Math.ceil(range / maxPoints));
        }

        const bg = this.chart.addBaselineSeries({
            baseValue: { type: "price", price: bottom },
            topFillColor1: fillColor,
            topFillColor2: fillColor,
            bottomFillColor1: fillColor,
            bottomFillColor2: fillColor,
            lineVisible: false,
            priceLineVisible: false,
            lastValueVisible: false,
            crosshairMarkerVisible: false,
            autoscaleInfoProvider: () => null
        });

        const data = [];
        for (let t = fromTime; t <= toTime; t += step) {
            data.push({ time: t, value: top });
        }
        if (!data.length) {
            this._safeRemoveSeries(bg);
            return;
        }

        try {
            bg.setData(data);
            this[targetField] = bg;
        } catch {
            this._safeRemoveSeries(bg);
        }
    }

    renderLevels(levels) {
        if (!Array.isArray(levels)) return;
        this.clearLevels();

        levels.forEach(lvl => {
            const price = this._parsePrice(lvl);
            if (!Number.isFinite(price)) return;

            const line = this.candles.createPriceLine({
                price,
                color: "#3b82f6",
                lineWidth: 1,
                axisLabelVisible: true,
                title: "LEVEL"
            });

            this.levelLines.push({ price, line });
        });

        this._applyActiveLevelStyle();
    }

    clearLevels() {
        this.levelLines.forEach(l => this._safeRemovePriceLine(l.line));
        this.levelLines = [];
    }

    onActiveLevel(payload) {
        if (!payload) return;
        this.activeLevelPrice = Number(payload.price);
        this._applyActiveLevelStyle();
    }

    _applyActiveLevelStyle() {
        this.levelLines.forEach(lvl => {
            lvl.line.applyOptions(
                lvl.price === this.activeLevelPrice
                    ? { color: "#22c55e", lineWidth: 3, title: "ACTIVE" }
                    : { color: "#3b82f6", lineWidth: 1, title: "LEVEL" }
            );
        });
    }

    onPriceUpdate(price) {
        if (!Number.isFinite(price)) return;
        this.currentPrice = price;
    }

    renderZone(zone) {
        if (!zone) return;
        this.clearZone();

        const top = Number(zone.top);
        const bottom = Number(zone.bottom);
        if (!Number.isFinite(top) || !Number.isFinite(bottom)) return;

        const hi = Math.max(top, bottom);
        const lo = Math.min(top, bottom);
        const color = zone.color || "rgba(59,130,246,0.15)";

        this._lastZone = { top: hi, bottom: lo, color };

        this.zoneLines = [
            this.candles.createPriceLine({
                price: hi,
                color: "#3b82f6",
                lineWidth: 2,
                axisLabelVisible: true,
                title: "ZONE TOP"
            }),
            this.candles.createPriceLine({
                price: lo,
                color: "#3b82f6",
                lineWidth: 2,
                axisLabelVisible: true,
                title: "ZONE BOTTOM"
            })
        ];

        this._renderBand(hi, lo, this._makeBandFillColor(color, "rgba(59,130,246,0.12)"), "zoneBackground");
    }

    clearZone() {
        this.zoneLines.forEach(l => this._safeRemovePriceLine(l));
        this.zoneLines = [];
        this._safeRemoveSeries(this.zoneBackground);
        this.zoneBackground = null;
        this._lastZone = null;
    }

    renderTradeZone(zone) {
        if (!zone) return;
        this.clearTradeZone();

        const top = Number(zone.top);
        const bottom = Number(zone.bottom);
        if (!Number.isFinite(top) || !Number.isFinite(bottom)) return;

        const hi = Math.max(top, bottom);
        const lo = Math.min(top, bottom);

        const color = zone.side === "BUY"
            ? "rgba(34,197,94,0.25)"
            : "rgba(239,68,68,0.25)";

        this.tradeZoneLines = [
            this.candles.createPriceLine({
                price: hi,
                color,
                lineWidth: 2,
                axisLabelVisible: true,
                title: `${zone.side} TOP`
            }),
            this.candles.createPriceLine({
                price: lo,
                color,
                lineWidth: 2,
                axisLabelVisible: true,
                title: `${zone.side} BOTTOM`
            })
        ];
    }

    clearTradeZone() {
        this.tradeZoneLines.forEach(l => this._safeRemovePriceLine(l));
        this.tradeZoneLines = [];
    }

    renderTpSl(tpSl) {
        if (!tpSl) return;
        this.clearTpSl();

        if (tpSl.tp != null) {
            const tp = Number(tpSl.tp);
            if (Number.isFinite(tp)) {
                this.tpLine = this.candles.createPriceLine({
                    price: tp,
                    color: "#22c55e",
                    lineWidth: 2,
                    axisLabelVisible: true,
                    title: "TP"
                });
            }
        }

        if (tpSl.sl != null) {
            const sl = Number(tpSl.sl);
            if (Number.isFinite(sl)) {
                this.slLine = this.candles.createPriceLine({
                    price: sl,
                    color: "#ef4444",
                    lineWidth: 2,
                    axisLabelVisible: true,
                    title: "SL"
                });
            }
        }
    }

    clearTpSl() {
        this._safeRemovePriceLine(this.tpLine);
        this._safeRemovePriceLine(this.slLine);
        this.tpLine = null;
        this.slLine = null;
    }

    renderPriceLine(pl) {
        if (!pl || !pl.name || pl.price == null) return;

        const name = String(pl.name).toUpperCase();
        const price = Number(pl.price);
        if (!Number.isFinite(price)) return;

        if (this._shouldSkipPriceLine(name)) {
            this._removePriceLineByName(name);
            return;
        }

        if (this.priceLines.has(name)) {
            this._safeRemovePriceLine(this.priceLines.get(name));
        }

        const color = pl.color ||
            (name === "ENTRY" ? "#eab308" :
                name === "TP" ? "#22c55e" :
                    name === "SL" ? "#ef4444" :
                        "#94a3b8");

        const line = this.candles.createPriceLine({
            price,
            color,
            lineWidth: 2,
            axisLabelVisible: true,
            title: name
        });

        this.priceLines.set(name, line);
    }

    clearPriceLines() {
        this.priceLines.forEach(line => this._safeRemovePriceLine(line));
        this.priceLines.clear();
    }

    renderWindowZone(zone) {
        if (!zone) return;

        const high = Number(zone.high);
        const low = Number(zone.low);
        if (!Number.isFinite(high) || !Number.isFinite(low)) return;

        const hi = Math.max(high, low);
        const lo = Math.min(high, low);

        this._lastWindowZone = {
            high: hi,
            low: lo,
            candlesData: Array.isArray(zone.candlesData) ? zone.candlesData : null
        };

        this.clearWindowZone();

        const color = "#64748b";

        this.windowHighLine = this.candles.createPriceLine({
            price: hi,
            color,
            lineWidth: 1,
            axisLabelVisible: true,
            title: "WINDOW HIGH"
        });

        this.windowLowLine = this.candles.createPriceLine({
            price: lo,
            color,
            lineWidth: 1,
            axisLabelVisible: true,
            title: "WINDOW LOW"
        });

        this._renderBand(hi, lo, "rgba(100,116,139,0.12)", "windowZoneBackground");
    }

    clearWindowZone() {
        this._safeRemovePriceLine(this.windowHighLine);
        this._safeRemovePriceLine(this.windowLowLine);
        this.windowHighLine = null;
        this.windowLowLine = null;
        this._safeRemoveSeries(this.windowZoneBackground);
        this.windowZoneBackground = null;
    }

    renderEmaSeries(payload) {
        if (!payload) return;
        const fastData = Array.isArray(payload.fastData) ? payload.fastData : [];
        const slowData = Array.isArray(payload.slowData) ? payload.slowData : [];

        this._lastEmaSeries = {
            fastData: fastData.map(p => ({ ...p })),
            slowData: slowData.map(p => ({ ...p }))
        };

        this.clearEmaSeries();
        this._removePriceLineByName("EMA_FAST");
        this._removePriceLineByName("EMA_SLOW");

        if (!fastData.length && !slowData.length) return;

        this.emaFastSeries = this._createLineSeries("#f59e0b");
        this.emaSlowSeries = this._createLineSeries("#60a5fa");

        try { this.emaFastSeries?.setData?.(fastData); } catch {}
        try { this.emaSlowSeries?.setData?.(slowData); } catch {}
    }

    clearEmaSeries() {
        this._safeRemoveSeries(this.emaFastSeries);
        this._safeRemoveSeries(this.emaSlowSeries);
        this.emaFastSeries = null;
        this.emaSlowSeries = null;
    }

    renderAtr(atr) {
        if (!atr) return;
        this.lastAtr = atr.atr;
        this.lastVolatilityPct = atr.volatilityPct;
    }

    clearAtr() {
        this.lastAtr = null;
        this.lastVolatilityPct = null;
    }

    renderOrder(order) {
        if (!order || !order.orderId) return;

        const orderId = String(order.orderId);
        if (this.orderLines.has(orderId)) {
            this._safeRemovePriceLine(this.orderLines.get(orderId));
        }

        const color = order.side === "BUY" ? "#22c55e" : "#ef4444";

        const line = this.candles.createPriceLine({
            price: Number(order.price),
            color,
            lineWidth: 1,
            lineStyle: 2,
            axisLabelVisible: true,
            title: `ORDER ${order.side}`
        });

        this.orderLines.set(orderId, line);
    }

    onMagnet(magnet) {
        if (!magnet) return;
        this.magnetTarget = Number(magnet.target);
        this.magnetStrength = magnet.strength;
    }

    renderTrade(trade, timeSec) {
        if (!trade || !Number.isFinite(timeSec)) return;

        const side = trade.side;
        if (side !== "BUY" && side !== "SELL") return;

        this.markers.push({
            time: timeSec,
            position: side === "BUY" ? "belowBar" : "aboveBar",
            color: side === "BUY" ? "#22c55e" : "#ef4444",
            shape: side === "BUY" ? "arrowUp" : "arrowDown",
            text: side
        });

        if (this.markers.length > 300) {
            this.markers = this.markers.slice(-300);
        }

        this._lastMarkers = [...this.markers];
        try {
            this.candles.setMarkers(this.markers);
        } catch {}
    }

    clearTrades() {
        this.markers = [];
        this._lastMarkers = [];
        try {
            this.candles?.setMarkers?.([]);
        } catch {}
    }

    renderSeriesBundle(payload) {
        if (!payload) return;

        const series = Array.isArray(payload.series) ? payload.series : [];
        this._lastSeriesBundle = {
            series: series.map(s => ({
                name: s?.name,
                color: s?.color,
                data: Array.isArray(s?.data) ? s.data.map(p => ({ ...p })) : []
            }))
        };

        this.clearSeriesBundle();
        if (!series.length) return;

        for (const item of series) {
            const name = String(item?.name || "").toUpperCase();
            if (!this._isMainPaneSeriesName(name)) {
                this._removePriceLineByName(name);
                continue;
            }

            const rawData = Array.isArray(item?.data) ? item.data : [];
            const data = rawData
                .map(p => ({
                    time: this._normalizeTime(p?.time),
                    value: Number(p?.value)
                }))
                .filter(p => Number.isFinite(p.time) && Number.isFinite(p.value));

            if (!data.length) continue;

            const line = this._createLineSeries(this._resolveSeriesColor(name, item?.color));
            if (!line) continue;

            try {
                line.setData(data);
                this.dynamicSeries.set(name, line);
                this._removePriceLineByName(name);
            } catch {
                this._safeRemoveSeries(line);
            }
        }
    }

    clearSeriesBundle() {
        this.dynamicSeries.forEach(series => this._safeRemoveSeries(series));
        this.dynamicSeries.clear();
    }
}

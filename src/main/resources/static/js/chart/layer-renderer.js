"use strict";

export class LayerRenderer {

    constructor(chart, candleSeries) {
        this.chart = chart;
        this.candles = candleSeries;

        this.levelLines = [];
        this.activeLevelPrice = null;
        this.zoneLines = [];
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

        this.lastAtr = null;
        this.lastVolatilityPct = null;

        this.orderLines = new Map();
        this.markers = [];
        this._lastMarkers = [];

        this.magnetTarget = null;
        this.magnetStrength = 0;
        this.currentPrice = null;
    }

    bind(chart, candleSeries) {
        this.chart = chart;
        this.candles = candleSeries;
        this.restoreWindowZone();
        this.restoreEmaSeries();
        this.restoreMarkers();
    }

    restoreWindowZone() {
        if (!this._lastWindowZone) return;
        this.renderWindowZone(this._lastWindowZone);
    }

    restoreEmaSeries() {
        if (!this._lastEmaSeries) return;
        this.renderEmaSeries(this._lastEmaSeries);
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

        this.zoneLines = [
            this.candles.createPriceLine({
                price: hi,
                color,
                lineWidth: 2,
                axisLabelVisible: true,
                title: "ZONE TOP"
            }),
            this.candles.createPriceLine({
                price: lo,
                color,
                lineWidth: 2,
                axisLabelVisible: true,
                title: "ZONE BOTTOM"
            })
        ];
    }

    clearZone() {
        this.zoneLines.forEach(l => this._safeRemovePriceLine(l));
        this.zoneLines = [];
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

        if (typeof this.chart?.addBaselineSeries !== "function") return;

        let fromTime = NaN;
        let toTime = NaN;
        const candles = Array.isArray(zone.candlesData) ? zone.candlesData : null;

        if (candles && candles.length) {
            fromTime = this._normalizeTime(candles[0]?.time);
            toTime = this._normalizeTime(candles.at(-1)?.time);
        }

        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) {
            const vr = this.chart?.timeScale?.().getVisibleRange?.();
            if (vr && vr.from != null && vr.to != null) {
                fromTime = this._normalizeTime(vr.from);
                toTime = this._normalizeTime(vr.to);
            }
        }

        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) return;
        if (toTime < fromTime) {
            const tmp = fromTime;
            fromTime = toTime;
            toTime = tmp;
        }

        let step = 60;
        if (candles && candles.length >= 2) {
            const t1 = this._normalizeTime(candles.at(-1)?.time);
            const t0 = this._normalizeTime(candles.at(-2)?.time);
            const dt = Math.abs(t1 - t0);
            if (Number.isFinite(dt) && dt > 0) step = dt;
        }

        const maxPoints = 600;
        const range = toTime - fromTime;
        const approx = Math.floor(range / step);
        if (approx > maxPoints) step = Math.max(1, Math.ceil(range / maxPoints));

        const bg = this.chart.addBaselineSeries({
            baseValue: { type: "price", price: lo },
            topFillColor1: "rgba(100, 116, 139, 0.12)",
            topFillColor2: "rgba(100, 116, 139, 0.12)",
            bottomFillColor1: "rgba(100, 116, 139, 0.12)",
            bottomFillColor2: "rgba(100, 116, 139, 0.12)",
            lineVisible: false,
            priceLineVisible: false,
            lastValueVisible: false,
            autoscaleInfoProvider: () => null
        });

        const data = [];
        for (let t = fromTime; t <= toTime; t += step) {
            data.push({ time: t, value: hi });
        }

        if (!data.length) {
            this._safeRemoveSeries(bg);
            return;
        }

        bg.setData(data);
        this.windowZoneBackground = bg;
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
        this.candles.setMarkers(this.markers);
    }

    clearTrades() {
        this.markers = [];
        this._lastMarkers = [];
        try {
            this.candles?.setMarkers?.([]);
        } catch {}
    }
}

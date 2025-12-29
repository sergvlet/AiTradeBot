"use strict";

/**
 * LayerRenderer
 * =============
 *
 * PUBLIC API (used by strategies):
 * ✔ renderLevels / clearLevels
 * ✔ renderZone / clearZone
 * ✔ renderTradeZone / clearTradeZone
 * ✔ renderTpSl / clearTpSl
 * ✔ renderPriceLine / clearPriceLines
 * ✔ renderOrder
 * ✔ renderTrade
 * ✔ renderWindowZone / clearWindowZone
 * ✔ renderAtr / clearAtr
 *
 * INTERNAL / LEGACY (not for new features):
 * ⚠ onActiveLevel
 * ⚠ onMagnet
 *
 * ❗ LayerRenderer:
 * - НЕ знает стратегию
 * - НЕ знает таймфрейм
 * - НЕ знает рынок
 */

export class LayerRenderer {

    constructor(chart, candleSeries) {
        this.chart   = chart;
        this.candles = candleSeries;

        // === LEVELS ===
        this.levelLines = [];
        this.activeLevelPrice = null;

        // === GENERIC ZONE ===
        this.zoneLines = [];

        // === BUY / SELL ZONE ===
        this.tradeZoneLines = [];

        // === TP / SL (legacy) ===
        this.tpLine = null;
        this.slLine = null;

        // === NAMED PRICE LINES (ENTRY / TP / SL) ===
        this.priceLines = new Map();

        // === WINDOW ZONE (SCALPING) ===
        this.windowHighLine = null;
        this.windowLowLine  = null;

        // === ATR / VOLATILITY (INFO ONLY) ===
        this.lastAtr = null;
        this.lastVolatilityPct = null;

        // === ORDERS ===
        this.orderLines = new Map();

        // === TRADES (MARKERS) ===
        this.markers = [];

        // === LEGACY / INTERNAL ===
        this.magnetTarget = null;
        this.magnetStrength = 0;

        this.currentPrice = null;
    }

    // =====================================================
    // HELPERS
    // =====================================================
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
        try {
            this.candles.removePriceLine(line);
        } catch {}
    }

    // =====================================================
    // LEVELS
    // =====================================================
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

    // =====================================================
    // ACTIVE LEVEL (LEGACY)
    // =====================================================
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

    // =====================================================
    // PRICE
    // =====================================================
    onPriceUpdate(price) {
        if (!Number.isFinite(price)) return;
        this.currentPrice = price;
    }

    // =====================================================
    // GENERIC ZONE
    // =====================================================
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

    // =====================================================
    // BUY / SELL ZONE
    // =====================================================
    renderTradeZone(zone) {
        if (!zone) return;
        this.clearTradeZone();

        const top = Number(zone.top);
        const bottom = Number(zone.bottom);
        if (!Number.isFinite(top) || !Number.isFinite(bottom)) return;

        const hi = Math.max(top, bottom);
        const lo = Math.min(top, bottom);

        const color =
            zone.side === "BUY"
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

    // =====================================================
    // TP / SL (LEGACY)
    // =====================================================
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

    // =====================================================
    // NAMED PRICE LINES
    // =====================================================
    renderPriceLine(pl) {
        if (!pl || !pl.name || pl.price == null) return;

        const name = String(pl.name).toUpperCase();
        const price = Number(pl.price);
        if (!Number.isFinite(price)) return;

        if (this.priceLines.has(name)) {
            this._safeRemovePriceLine(this.priceLines.get(name));
        }

        const color =
            pl.color ||
            (name === "ENTRY" ? "#eab308" :
                name === "TP"    ? "#22c55e" :
                    name === "SL"    ? "#ef4444" :
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

    // =====================================================
    // WINDOW ZONE
    // =====================================================
    renderWindowZone(zone) {
        if (!zone) return;
        this.clearWindowZone();

        const high = Number(zone.high);
        const low  = Number(zone.low);
        if (!Number.isFinite(high) || !Number.isFinite(low)) return;

        const hi = Math.max(high, low);
        const lo = Math.min(high, low);

        const color = "#64748b";

        this.windowHighLine = this.candles.createPriceLine({
            price: hi,
            color,
            lineWidth: 2,
            axisLabelVisible: true,
            title: "WINDOW HIGH"
        });

        this.windowLowLine = this.candles.createPriceLine({
            price: lo,
            color,
            lineWidth: 2,
            axisLabelVisible: true,
            title: "WINDOW LOW"
        });
    }

    clearWindowZone() {
        this._safeRemovePriceLine(this.windowHighLine);
        this._safeRemovePriceLine(this.windowLowLine);
        this.windowHighLine = null;
        this.windowLowLine  = null;
    }

    // =====================================================
    // ATR / VOLATILITY (INFO)
    // =====================================================
    renderAtr(atr) {
        if (!atr) return;
        this.lastAtr = atr.atr;
        this.lastVolatilityPct = atr.volatilityPct;
    }

    clearAtr() {
        this.lastAtr = null;
        this.lastVolatilityPct = null;
    }

    // =====================================================
    // ORDERS
    // =====================================================
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

    // =====================================================
    // MAGNET (LEGACY)
    // =====================================================
    onMagnet(magnet) {
        if (!magnet) return;
        this.magnetTarget = Number(magnet.target);
        this.magnetStrength = magnet.strength;
    }

    // =====================================================
    // TRADES (MARKERS)
    // =====================================================
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

        this.candles.setMarkers(this.markers);
    }
}

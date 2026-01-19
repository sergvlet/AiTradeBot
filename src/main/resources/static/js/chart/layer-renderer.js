"use strict";

/**
 * LayerRenderer
 * =============
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
        this.windowZoneBackground = null;

        // ✅ sticky state (чтобы после refresh восстановить)
        this._lastWindowZone = null;

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
    // BIND / REBIND (ВАЖНО для refresh)
    // =====================================================
    bind(chart, candleSeries) {
        this.chart   = chart;
        this.candles = candleSeries;

        // после пересоздания series/graph — восстанавливаем зону
        this.restoreWindowZone();
    }

    restoreWindowZone() {
        if (!this._lastWindowZone) return;
        // перерисует на текущих this.chart/this.candles
        this.renderWindowZone(this._lastWindowZone);
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
            this.candles?.removePriceLine?.(line);
        } catch {}
    }

    _safeRemoveSeries(series) {
        if (!series) return;
        try {
            this.chart?.removeSeries?.(series);
        } catch {}
    }

    // ✅ приводим время к UTCTimestamp (секунды)
    _normalizeTime(t) {
        if (t == null) return NaN;

        // LightweightCharts иногда может вернуть объект businessDay
        if (typeof t === "object") return NaN;

        const n = Number(t);
        if (!Number.isFinite(n)) return NaN;

        // если миллисекунды (типично 13 цифр)
        if (n > 1e11) return Math.floor(n / 1000);
        return Math.floor(n);
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
    // WINDOW ZONE  ✅ FIXED
    // =====================================================
    renderWindowZone(zone) {
        if (!zone) return;

        const high = Number(zone.high);
        const low  = Number(zone.low);
        if (!Number.isFinite(high) || !Number.isFinite(low)) return;

        const hi = Math.max(high, low);
        const lo = Math.min(high, low);

        // ✅ сохраняем последнюю валидную зону (для restore после refresh)
        this._lastWindowZone = {
            high: hi,
            low: lo,
            candlesData: Array.isArray(zone.candlesData) ? zone.candlesData : null
        };

        // перерисовка
        this.clearWindowZone();

        const color = "#64748b";

        // ✅ Линии рисуем ВСЕГДА
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

        // ---- ФОН (опционально) ----
        if (typeof this.chart?.addBaselineSeries !== "function") return;

        let fromTime = NaN;
        let toTime   = NaN;

        const candles = Array.isArray(zone.candlesData) ? zone.candlesData : null;

        if (candles && candles.length) {
            fromTime = this._normalizeTime(candles[0]?.time);
            toTime   = this._normalizeTime(candles.at(-1)?.time);
        }

        // fallback: видимый диапазон (если candlesData нет/битый)
        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) {
            const vr = this.chart?.timeScale?.().getVisibleRange?.();
            if (vr && vr.from != null && vr.to != null) {
                fromTime = this._normalizeTime(vr.from);
                toTime   = this._normalizeTime(vr.to);
            }
        }

        if (!Number.isFinite(fromTime) || !Number.isFinite(toTime)) return;

        if (toTime < fromTime) {
            const tmp = fromTime;
            fromTime = toTime;
            toTime = tmp;
        }

        // шаг по времени
        let step = 60;
        if (candles && candles.length >= 2) {
            const t1 = this._normalizeTime(candles.at(-1)?.time);
            const t0 = this._normalizeTime(candles.at(-2)?.time);
            const dt = Math.abs(t1 - t0);
            if (Number.isFinite(dt) && dt > 0) step = dt;
        }

        // ограничим размер массива
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

            // ✅ чтобы фон НЕ влиял на autoscale свечей
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

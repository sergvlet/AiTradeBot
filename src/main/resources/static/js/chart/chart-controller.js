"use strict";

/**
 * ChartController (LIVE FIX v3)
 * =============================
 * Что исправлено:
 * 1) candlesData / _candlesByTime всегда синхронизированы
 * 2) время нормализуется в bucket таймфрейма
 * 3) candle event остаётся источником истины по OHLC
 * 4) price event теперь тоже двигает текущую свечу в реальном времени
 * 5) старые price events не переписывают уже закрытую историю
 */

export class ChartController {
    constructor(container) {
        this.container = container;
        if (!container) {
            console.error("❌ ChartController: container is null");
            return;
        }

        const { clientWidth, clientHeight } = container;
        const LightweightCharts = window.LightweightCharts;

        this.chart = LightweightCharts.createChart(container, {
            width: clientWidth || 800,
            height: clientHeight || 420,
            layout: {
                background: { color: "#0e0f11" },
                textColor: "#e0e0e0"
            },
            grid: {
                vertLines: { visible: false },
                horzLines: { visible: false }
            },
            rightPriceScale: { borderColor: "#444" },
            timeScale: {
                borderColor: "#444",
                timeVisible: true,
                secondsVisible: false
            }
        });

        this.candles = this.chart.addCandlestickSeries({
            upColor: "#26a69a",
            downColor: "#ef5350",
            wickUpColor: "#26a69a",
            wickDownColor: "#ef5350",
            borderVisible: false
        });

        this.symbol = null;
        this.timeframe = "1m";

        this.candlesData = [];
        this._candlesByTime = new Map();
        this._lastCandle = null;
        this._lastWarnAt = 0;
        this._lastPrice = null;
    }

    // =====================================================
    // Time helpers
    // =====================================================

    timeframeToSeconds(tf) {
        const s = String(tf || "1m").trim().toLowerCase();
        const m = s.match(/^(\d+)\s*([smhd])$/);
        if (!m) return 60;

        const n = parseInt(m[1], 10);
        const u = m[2];

        if (u === "s") return n;
        if (u === "m") return n * 60;
        if (u === "h") return n * 60 * 60;
        if (u === "d") return n * 60 * 60 * 24;

        return 60;
    }

    getTimeSeconds(rawTime) {
        if (rawTime === null || rawTime === undefined) return null;

        if (typeof rawTime === "number") {
            if (rawTime > 4_000_000_000) return Math.floor(rawTime / 1000);
            return Math.floor(rawTime);
        }

        if (typeof rawTime === "string") {
            const t = rawTime.trim();
            if (!t) return null;

            if (/^\d+$/.test(t)) {
                const n = Number(t);
                if (!Number.isFinite(n)) return null;
                return this.getTimeSeconds(n);
            }

            const ms = Date.parse(t);
            if (Number.isFinite(ms)) return Math.floor(ms / 1000);
        }

        return null;
    }

    normalizeTimeToBucket(rawTime) {
        const sec = this.getTimeSeconds(rawTime);
        if (sec === null) return null;

        const bucket = this.timeframeToSeconds(this.timeframe);
        return Math.floor(sec / bucket) * bucket;
    }

    // =====================================================
    // Parse helpers
    // =====================================================

    parseNum(v) {
        if (v === null || v === undefined) return null;
        if (typeof v === "number") return Number.isFinite(v) ? v : null;

        const s = String(v).trim();
        if (!s) return null;

        const n = Number(s);
        return Number.isFinite(n) ? n : null;
    }

    getNestedKline(ev) {
        return ev?.kline || ev?.k || ev?.data?.k || null;
    }

    extractCandle(ev) {
        if (!ev) return null;

        if (ev.time !== undefined && (ev.open !== undefined || ev.close !== undefined)) {
            return {
                time: ev.time,
                open: ev.open,
                high: ev.high,
                low: ev.low,
                close: ev.close
            };
        }

        const k = this.getNestedKline(ev);
        if (!k) return null;

        const rawTime =
            k.openTime ?? k.open_time ?? k.t ??
            k.startTime ?? k.start_time ??
            ev.time ?? ev.t ?? ev.T ??
            null;

        const o = (k.open ?? k.o);
        const h = (k.high ?? k.h);
        const l = (k.low  ?? k.l);
        const c = (k.close ?? k.c);

        return { time: rawTime, open: o, high: h, low: l, close: c };
    }

    extractPriceTick(ev) {
        if (!ev || ev.type !== "price") return null;

        const price = this.parseNum(ev.price);
        const time = ev.time;

        if (price === null || time === null || time === undefined) return null;

        return { time, price };
    }

    // =====================================================
    // History
    // =====================================================

    setHistory(candles) {
        if (!Array.isArray(candles)) return;

        const map = new Map();

        for (const c of candles) {
            const t = this.normalizeTimeToBucket(c?.time ?? c?.t ?? c?.T);
            if (t === null) continue;

            const o  = this.parseNum(c.open  ?? c.o);
            const h  = this.parseNum(c.high  ?? c.h);
            const l  = this.parseNum(c.low   ?? c.l);
            const cl = this.parseNum(c.close ?? c.c);

            if (o === null || h === null || l === null || cl === null) continue;

            const hi = Math.max(h, o, cl);
            const lo = Math.min(l, o, cl);

            map.set(t, { time: t, open: o, high: hi, low: lo, close: cl });
        }

        const times = Array.from(map.keys()).sort((a, b) => a - b);
        const out = times.map(t => map.get(t));

        this.candlesData = out;
        this._candlesByTime = map;
        this._lastCandle = out.length ? out[out.length - 1] : null;
        this._lastPrice = this._lastCandle ? this._lastCandle.close : null;

        this.candles.setData(out);

        console.log("📦 History loaded", out.length);
    }

    // =====================================================
    // Core upsert
    // =====================================================

    upsertCandle(partial) {
        const t = this.normalizeTimeToBucket(partial?.time);
        if (t === null) return;

        const prev = this._candlesByTime.get(t) || null;

        let o = this.parseNum(partial.open);
        let h = this.parseNum(partial.high);
        let l = this.parseNum(partial.low);
        let c = this.parseNum(partial.close);

        if (prev) {
            if (o === null) o = prev.open;
            if (c === null) c = prev.close;
            if (h === null) h = prev.high;
            if (l === null) l = prev.low;
        }

        if (!prev) {
            if (o === null && c !== null) o = c;
            if (c === null && o !== null) c = o;
        }

        if (o === null || c === null) return;

        if (h === null) h = Math.max(o, c);
        if (l === null) l = Math.min(o, c);

        h = Math.max(h, o, c);
        l = Math.min(l, o, c);

        const candle = { time: t, open: o, high: h, low: l, close: c };

        const existed = this._candlesByTime.has(t);
        this._candlesByTime.set(t, candle);

        if (!this._lastCandle || t >= this._lastCandle.time) {
            this._lastCandle = candle;
            this._lastPrice = candle.close;
        }

        if (!this.candlesData || this.candlesData.length === 0) {
            this.candlesData = [candle];
        } else {
            const last = this.candlesData[this.candlesData.length - 1];

            if (last.time === t) {
                this.candlesData[this.candlesData.length - 1] = candle;
            } else if (last.time < t) {
                this.candlesData.push(candle);
            } else {
                let loIdx = 0;
                let hiIdx = this.candlesData.length - 1;
                let idx = -1;

                while (loIdx <= hiIdx) {
                    const mid = (loIdx + hiIdx) >> 1;
                    const mt = this.candlesData[mid].time;

                    if (mt === t) {
                        idx = mid;
                        break;
                    }
                    if (mt < t) loIdx = mid + 1;
                    else hiIdx = mid - 1;
                }

                if (idx >= 0) this.candlesData[idx] = candle;
                else this.candlesData.splice(loIdx, 0, candle);
            }
        }

        this.candles.update(candle);

        if (!existed) this.adjustBarSpacing();
    }

    // =====================================================
    // Live price -> current candle
    // =====================================================

    applyPriceTick(tick) {
        if (!tick) return;

        const t = this.normalizeTimeToBucket(tick.time);
        const price = this.parseNum(tick.price);

        if (t === null || price === null) return;

        this._lastPrice = price;

        if (this._lastCandle && t < this._lastCandle.time) {
            return;
        }

        const sameBucket = this._candlesByTime.get(t);
        if (sameBucket) {
            this.upsertCandle({
                time: t,
                open: sameBucket.open,
                high: Math.max(sameBucket.high, price),
                low: Math.min(sameBucket.low, price),
                close: price
            });
            return;
        }

        const prevClose =
            this._lastCandle && Number.isFinite(this._lastCandle.close)
                ? this._lastCandle.close
                : price;

        const open = prevClose;
        const high = Math.max(open, price);
        const low = Math.min(open, price);

        this.upsertCandle({
            time: t,
            open,
            high,
            low,
            close: price
        });
    }

    // =====================================================
    // WS entry
    // =====================================================

    onWsMessage(ev) {
        if (!ev) return;

        const priceTick = this.extractPriceTick(ev);
        if (priceTick) {
            this.applyPriceTick(priceTick);
            return;
        }

        const candle = this.extractCandle(ev);
        if (candle) {
            this.upsertCandle(candle);
        }
    }

    // =====================================================
    // UX
    // =====================================================

    adjustBarSpacing() {
        try {
            const len = this.candlesData?.length || 0;
            if (len < 50) return;

            const bs = Math.max(2, Math.min(10, Math.floor(1200 / Math.sqrt(len))));
            this.chart.timeScale().applyOptions({ barSpacing: bs });
        } catch (e) {
            const now = Date.now();
            if (now - this._lastWarnAt > 3000) {
                this._lastWarnAt = now;
                console.warn("⚠ adjustBarSpacing failed", e);
            }
        }
    }
}
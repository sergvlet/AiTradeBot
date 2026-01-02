"use strict";

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

        // ✅ Добавлен отступ справа (важно для читаемости)
        this.chart.timeScale().applyOptions({ rightOffset: 20 });

        this.candles = this.chart.addCandlestickSeries({
            upColor: "#26a69a",
            downColor: "#ef5350",
            wickUpColor: "#26a69a",
            wickDownColor: "#ef5350",
            borderVisible: false,

            // ❗ ВАЖНО: отключаем встроенную цену
            priceLineVisible: false,
            lastValueVisible: false
        });

        // timeframe + data
        this.timeframeSec = 60;
        this.lastBar = null;
        this.candlesData = [];

        // meta
        this.symbol = null;
        this.timeframe = null;

        // === PRICE LINE (создаём 1 раз) ==================================
        this.lastPrice = null;
        this.priceLine = this.candles.createPriceLine({
            price: 0,
            color: "#888",
            lineWidth: 1,
            lineStyle: 2,
            axisLabelVisible: true
        });

        this.applyTheme("dark");
        this.adjustBarSpacing();
        window.addEventListener("resize", () => this.adjustBarSpacing());

        console.log("✅ ChartController created", {
            w: clientWidth,
            h: clientHeight,
            timeframeSec: this.timeframeSec
        });
    }

    /* ====================================================================== */
    /* 🧠 TIME HELPERS                                                         */
    /* ====================================================================== */

    setTimeframe(tf) {
        this.timeframe = tf;
        this.timeframeSec = ChartController.parseTimeframeSec(tf);
        console.log("⏱️ setTimeframe:", tf, "=>", this.timeframeSec, "sec");
    }

    static parseTimeframeSec(tf) {
        const s = String(tf || "1m").trim().toLowerCase();
        const m = s.match(/^(\d+)(s|m|h|d)$/);
        if (!m) return 60;
        const n = Number(m[1]);
        const mult = m[2] === "s" ? 1 : m[2] === "m" ? 60 : m[2] === "h" ? 3600 : 86400;
        return Math.max(1, n * mult);
    }

    normalizeLiveTime(raw) {
        let t = Number(raw);
        if (!Number.isFinite(t)) return null;
        if (t > 1e12) t = Math.floor(t / 1000);
        const step = this.timeframeSec || 60;
        return Math.floor(t / step) * step;
    }

    normalizeTimeToBucket(rawTime, index = null, total = null) {
        let t = Number(rawTime);

        if (Number.isFinite(t) && t > 1e12) {
            t = Math.floor(t / 1000);
        }

        if (Number.isFinite(t) && t >= 1e9) {
            const step = this.timeframeSec || 60;
            return Math.floor(t / step) * step;
        }

        if (typeof index === "number" && typeof total === "number") {
            const now = Math.floor(Date.now() / 1000);
            const step = this.timeframeSec || 60;
            const distance = total - index - 1;
            const restored = now - distance * step;
            return Math.floor(restored / step) * step;
        }

        return null;
    }

    /* ====================================================================== */
    /* 📦 HISTORY                                                              */
    /* ====================================================================== */

    setHistory(candles) {
        if (!Array.isArray(candles) || candles.length === 0) {
            console.warn("⚠️ Empty candles history");
            return;
        }

        const safe = [];
        const total = candles.length;

        for (let i = 0; i < total; i++) {
            const c = candles[i];
            const time = this.normalizeTimeToBucket(c?.time, i, total);

            const open = Number(c?.open);
            const high = Number(c?.high);
            const low  = Number(c?.low);
            const close = Number(c?.close);
            const volume = Number(c?.volume);

            if (!Number.isFinite(time)) continue;
            if (![open, high, low, close].every(Number.isFinite)) continue;

            safe.push({ time, open, high, low, close, volume });
        }

        if (!safe.length) {
            console.error("❌ All candles invalid (history)");
            return;
        }

        safe.sort((a, b) => a.time - b.time);

        const map = new Map();
        for (const c of safe) map.set(c.time, c);
        const unique = [...map.values()].sort((a, b) => a.time - b.time);

        this.candles.setData(unique);
        this.candlesData = unique;

        this.lastBar = unique.at(-1) || null;

        if (this.lastBar) this.updatePriceLine(this.lastBar.close);

        console.log("📦 History loaded", unique.length);
        this.detectTimeframe(unique);
    }

    detectTimeframe(unique) {
        if (unique.length > 1) {
            const dt = unique[1].time - unique[0].time;
            if (dt > 0 && dt !== this.timeframeSec) {
                this.timeframeSec = dt;
                console.info("🧠 Detected timeframe", dt + "s");
            }
        }
    }

    /* ====================================================================== */
    /* 🔥 WS MESSAGE (важно!)                                                  */
    /* ====================================================================== */

    onWsMessage(msg) {
        if (!msg || typeof msg !== "object") return;

        // ✅ ИСПРАВЛЕНО: НЕ ПРЕОБРАЗУЕМ null → 0
        if (msg.price !== null && msg.price !== undefined) {
            const p = Number(msg.price);
            if (Number.isFinite(p) && p > 0) {
                this.updatePriceLine(p);
            }
        }

        if (msg.type === "candle") {
            this.onCandle(msg);
        }
    }

    /* ====================================================================== */
    /* 🔴 LIVE CANDLE                                                          */
    /* ====================================================================== */

    onCandle(ev) {
        const k = (ev && ev.kline && typeof ev.kline === "object") ? ev.kline : ev;

        const rawTime =
            ev?.time ?? ev?.openTime ?? ev?.timestamp ?? ev?.t ??
            k?.time ?? k?.openTime ?? k?.timestamp ?? k?.t;

        const time = this.normalizeLiveTime(rawTime);
        if (!Number.isFinite(time)) return;

        const open   = Number(k?.open);
        const high   = Number(k?.high);
        const low    = Number(k?.low);
        const close  = Number(k?.close);
        const volume = Number(k?.volume);

        if (![open, high, low, close].every(Number.isFinite)) return;

        const bar = {
            time,
            open,
            high: Math.max(high, open, close),
            low:  Math.min(low, open, close),
            close,
            volume
        };

        const last = this.candlesData.at(-1);

        if (last && last.time === bar.time) {
            this.candles.update(bar);
            this.candlesData[this.candlesData.length - 1] = bar;
        } else if (!last || bar.time > last.time) {
            this.candles.update(bar);
            this.candlesData.push(bar);
            this.chart.timeScale().scrollToRealTime();
        } else {
            return;
        }

        this.lastBar = bar;

        // ✅ ЕДИНСТВЕННОЕ МЕСТО ОБНОВЛЕНИЯ ЦЕНЫ
        this.updatePriceLine(bar.close);
    }

    /* ====================================================================== */
    /* 💲 PRICE LINE                                                           */
    /* ====================================================================== */

    updatePriceLine(price) {
        const p = Number(price);
        if (!Number.isFinite(p) || p <= 0) return;

        const prev = this.lastPrice;
        if (prev === p) return;

        this.lastPrice = p;

        let color = "#888";
        if (prev != null) {
            color = p > prev ? "#26a69a" : p < prev ? "#ef5350" : "#888";
        }

        this.priceLine.applyOptions({ price: p, color });

        console.log("💲 PriceLine", prev, "→", p, color);
    }

    /* ====================================================================== */
    /* 🧩 UI                                                                    */
    /* ====================================================================== */

    adjustBarSpacing() {
        const w = this.container.clientWidth;
        this.chart.applyOptions({
            timeScale: { barSpacing: w < 400 ? 4 : w < 800 ? 6 : 8 }
        });
    }

    applyTheme(kind = "dark") {
        const dark = kind === "dark";
        this.chart.applyOptions({
            layout: {
                background: { color: dark ? "#0e0f11" : "#fff" },
                textColor:   dark ? "#e0e0e0" : "#000"
            },
            grid: {
                vertLines: { color: dark ? "#444" : "#ccc" },
                horzLines: { color: dark ? "#444" : "#ccc" }
            }
        });
    }
}

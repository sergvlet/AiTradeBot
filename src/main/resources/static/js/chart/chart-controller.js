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

        this.candles = this.chart.addCandlestickSeries({
            upColor: "#26a69a",
            downColor: "#ef5350",
            wickUpColor: "#26a69a",
            wickDownColor: "#ef5350",
            borderVisible: false,
            priceLineVisible: false,
            lastValueVisible: false
        });

        this.timeframeSec = 60;
        this.lastBar = null;
        this.lastPrice = null;
        this.priceLine = null;

        this._plRaf = 0;
        this._plQueuedPrice = null;

        this.candlesData = [];

        // indicator series
        this.lastSMA = null;
        this.lastEMA = null;
        this.volSeries = null;

        this.applyTheme("dark");
        this.adjustBarSpacing();

        window.addEventListener("resize", () => this.adjustBarSpacing());
    }

    setTimeframe(tf) {
        this.timeframeSec = ChartController.parseTimeframeSec(tf);
    }

    static parseTimeframeSec(tf) {
        const s = String(tf || "1m").trim().toLowerCase();
        const m = s.match(/^(\d+)(s|m|h|d)$/);
        if (!m) return 60;
        const n = Number(m[1]);
        const unit = m[2];
        const mult = unit === "s" ? 1 : unit === "m" ? 60 : unit === "h" ? 3600 : 86400;
        return Math.max(1, n * mult);
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

        if (Number.isFinite(t) && typeof index === "number" && typeof total === "number") {
            const now = Math.floor(Date.now() / 1000);
            const step = this.timeframeSec || 60;
            const distance = total - index - 1;
            const restored = now - distance * step;
            return Math.floor(restored / step) * step;
        }

        return null;
    }

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
            const open  = Number(c?.open);
            const high  = Number(c?.high);
            const low   = Number(c?.low);
            const close = Number(c?.close);
            const volume = Number(c?.volume);

            if (!Number.isFinite(time)) continue;
            if (![open, high, low, close].every(Number.isFinite)) continue;

            safe.push({ time, open, high, low, close, volume });
        }

        if (!safe.length) {
            console.error("❌ All candles invalid");
            return;
        }

        safe.sort((a, b) => a.time - b.time);
        const map = new Map();
        for (const c of safe) map.set(c.time, c);
        const unique = [...map.values()].sort((a, b) => a.time - b.time);

        this.candles.setData(unique);
        this.candlesData = unique;

        this.lastBar = unique.at(-1) || null;
        this.updatePriceLine(this.lastBar?.close);

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

    onCandle(ev) {
        const time = this.normalizeTimeToBucket(ev.time);
        if (time == null) return;

        const open  = Number(ev.open);
        const high  = Number(ev.high);
        const low   = Number(ev.low);
        const close = Number(ev.close);
        const volume = Number(ev.volume);

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
            this.candlesData[this.candlesData.length-1] = bar;
        } else if (!last || bar.time > last.time) {
            this.candles.update(bar);
            this.chart.timeScale().scrollToRealTime();
            this.candlesData.push(bar);
        }

        this.lastBar = bar;
        this.updatePriceLine(bar.close);
    }

    updatePriceLine(price) {
        if (!Number.isFinite(price)) return;
        if (price === this.lastPrice) return;

        this._plQueuedPrice = price;
        if (this._plRaf) return;

        this._plRaf = requestAnimationFrame(() => {
            this._plRaf = 0;
            const p = this._plQueuedPrice;
            this._plQueuedPrice = null;

            if (!Number.isFinite(p) || p === this.lastPrice) return;

            if (this.priceLine) {
                try { this.candles.removePriceLine(this.priceLine); }
                catch {}
                this.priceLine = null;
            }

            this.priceLine = this.candles.createPriceLine({
                price: p,
                color: p >= this.lastPrice ? "#26a69a" : "#ef5350",
                lineWidth: 1,
                lineStyle: 2,
                axisLabelVisible: true
            });
            this.lastPrice = p;
        });
    }

    adjustBarSpacing() {
        const w = this.container.clientWidth;
        this.chart.applyOptions({
            timeScale: { barSpacing: w < 400 ? 4 : w < 800 ? 6 : 8 }
        });
    }

    applyTheme(kind="dark") {
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

    addSMA(period=14, color="#ffeb3b") {
        if (!this.candlesData.length) return;
        const data = [];
        const vals = this.candlesData.map(c => c.close);
        for (let i = 0; i < vals.length; i++) {
            if (i < period - 1) {
                data.push(null);
            } else {
                const sum = vals.slice(i - period + 1, i + 1).reduce((a,b) => a+b, 0);
                data.push(sum / period);
            }
        }
        const seriesData = this.candlesData.map((c,i) => ({
            time: c.time,
            value: data[i]
        })).filter(d => d.value != null);

        if (!this.lastSMA) {
            this.lastSMA = this.chart.addLineSeries({ color, lineWidth: 2 });
        }
        this.lastSMA.setData(seriesData);
    }

    addEMA(period=14, color="#03a9f4") {
        if (!this.candlesData.length) return;
        let k = 2/(period+1), prev;
        const out = [];
        this.candlesData.forEach((c,i) => {
            prev = i===0 ? c.close : c.close*k + prev*(1-k);
            out.push({ time: c.time, value: prev });
        });

        if (!this.lastEMA) {
            this.lastEMA = this.chart.addLineSeries({ color, lineWidth: 2 });
        }
        this.lastEMA.setData(out);
    }

    addVolumeSeries() {
        if (!this.candlesData.length) return;
        if (!this.volSeries) {
            this.volSeries = this.chart.addHistogramSeries({
                priceFormat: { type: "volume" },
                priceScaleId: "",
                scaleMargins: { top: 0.75, bottom: 0 }
            });
        }
        this.volSeries.setData(this.candlesData.map(c => ({
            time: c.time,
            value: c.volume,
            color: c.close >= c.open ? "#26a69a" : "#ef5350"
        })));
    }
}

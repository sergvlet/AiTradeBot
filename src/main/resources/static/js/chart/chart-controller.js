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
            width:  clientWidth  || 800,
            height: clientHeight || 420,
            layout: {
                background: { color: "#0e0f11" },
                textColor: "#e0e0e0"
            },
            grid: {
                vertLines: { visible: false },
                horzLines: { visible: false }
            },
            rightPriceScale: {
                borderColor: "#444",
                autoScale: true
            },
            timeScale: {
                borderColor: "#444",
                timeVisible: true,
                secondsVisible: false,
                rightOffset: 8,
                barSpacing: 8
            }
        });

        this.candles = this.chart.addCandlestickSeries({
            upColor: "#26a69a",
            downColor: "#ef5350",
            wickUpColor: "#26a69a",
            wickDownColor: "#ef5350",
            borderVisible: false,
            lastValueVisible: false,
            priceLineVisible: false
        });

        this.lastBar   = null;
        this.lastPrice = null;
        this.priceLine = null;
    }

    // =====================================================
    // HISTORY (REST)
    // =====================================================
    setHistory(candles) {
        if (!Array.isArray(candles) || candles.length === 0) return;

        const data = candles
            .map(c => ({
                time:  Number(c.time),   // ❗ как приходит — не трогаем
                open:  +c.open,
                high:  +c.high,
                low:   +c.low,
                close: +c.close
            }))
            .filter(c => Number.isFinite(c.time));

        if (data.length === 0) return;

        this.candles.setData(data);
        this.lastBar = { ...data[data.length - 1] };
        this.updatePriceLine(this.lastBar.close);
        this.chart.timeScale().scrollToRealTime();
    }

    // =====================================================
    // LIVE CANDLE (WS)
    // =====================================================
    onCandle(ev) {
        if (!ev || !Number.isFinite(ev.time)) return;

        const open  = ev.kline?.open  ?? ev.open;
        const high  = ev.kline?.high  ?? ev.high;
        const low   = ev.kline?.low   ?? ev.low;
        const close = ev.kline?.close ?? ev.close;

        if (
            !Number.isFinite(open) ||
            !Number.isFinite(high) ||
            !Number.isFinite(low)  ||
            !Number.isFinite(close)
        ) return;

        // =================================================
        // 🔑 КЛЮЧЕВОЙ ФИКС: привязка к таймфрейму 1m
        // =================================================
        const TF_MS = 60_000; // 1m
        const candleTime = Math.floor(ev.time / TF_MS) * TF_MS;

        const bar = {
            time:  candleTime,
            open:  +open,
            high:  +high,
            low:   +low,
            close: +close
        };

        // 🔁 update текущей свечи
        if (this.lastBar && this.lastBar.time === bar.time) {
            this.candles.update(bar);
            this.lastBar = { ...bar };
            this.updatePriceLine(bar.close);
            return;
        }

        // ➕ новая свеча
        if (!this.lastBar || bar.time > this.lastBar.time) {
            this.candles.update(bar);
            this.lastBar = { ...bar };
            this.chart.timeScale().scrollToRealTime();
            this.updatePriceLine(bar.close);
        }
    }


    // =====================================================
    // PRICE TICK
    // =====================================================
    onPrice(ev) {
        const price = Number(ev.price);
        if (!Number.isFinite(price) || !this.lastBar) return;

        this.lastBar = {
            ...this.lastBar,
            high: Math.max(this.lastBar.high, price),
            low:  Math.min(this.lastBar.low, price),
            close: price
        };

        this.candles.update(this.lastBar);
        this.updatePriceLine(price);
    }

    // =====================================================
    // PRICE LINE
    // =====================================================
    updatePriceLine(price) {
        if (!Number.isFinite(price)) return;

        const up = this.lastPrice == null || price >= this.lastPrice;
        const color = up ? "#26a69a" : "#ef5350";

        if (this.priceLine) {
            this.candles.removePriceLine(this.priceLine);
        }

        this.priceLine = this.candles.createPriceLine({
            price,
            color,
            lineWidth: 1,
            lineStyle: 2,
            axisLabelVisible: true
        });

        this.lastPrice = price;
    }
}

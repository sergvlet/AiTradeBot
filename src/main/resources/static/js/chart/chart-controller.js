"use strict";

/**
 * ChartController
 * ----------------
 * Отвечает ТОЛЬКО за рынок:
 * - свечи (candle)
 * - price tick → обновление последней свечи
 * - одна price-line (Binance style)
 *
 * НЕ знает ничего про стратегии, уровни, зоны, трейды
 */
export class ChartController {

    constructor(container) {
        this.container = container;

        const LightweightCharts = window.LightweightCharts;

        this.chart = LightweightCharts.createChart(container, {
            height: 420,
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
    // UTILS
    // =====================================================
    toTimeSec(v) {
        const n = Number(v);
        if (!Number.isFinite(n)) return null;
        return n > 10_000_000_000 ? Math.floor(n / 1000) : Math.floor(n);
    }

    // =====================================================
    // HISTORY
    // =====================================================
    setHistory(candles) {
        if (!Array.isArray(candles) || candles.length === 0) return;

        this.candles.setData(candles);
        this.lastBar = { ...candles[candles.length - 1] };

        this.updatePriceLine(this.lastBar.close);
        this.chart.timeScale().scrollToRealTime();
    }

    // =====================================================
    // CANDLE (OHLC)
    // =====================================================
    onCandle(ev) {
        if (!ev || !ev.kline) return;

        const t = this.toTimeSec(ev.time);
        if (!t) return;

        const bar = {
            time: t,
            open: +ev.kline.open,
            high: +ev.kline.high,
            low:  +ev.kline.low,
            close:+ev.kline.close
        };

        this.candles.update(bar);
        this.lastBar = { ...bar };

        this.updatePriceLine(bar.close);
        this.chart.timeScale().scrollToRealTime();
    }

    // =====================================================
    // PRICE TICK
    // =====================================================
    onPrice(ev) {
        const price = Number(ev.price);
        if (!Number.isFinite(price)) return;

        if (!this.lastBar) return;

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
    // PRICE LINE (ONE)
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
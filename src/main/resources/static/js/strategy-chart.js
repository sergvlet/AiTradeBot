console.log("📈 strategy-chart.js loaded (ULTRA FIX v7, REAL-TIME + MARKERS + TOOLTIPS)");

let chart, candleSeries, ema20Series, ema50Series;
let ws = null;
let lastPriceLine = null;
let tradeMarkers = [];

let tradesGlobal = [];
let candlesGlobal = [];
let ema20Global = [];
let ema50Global = [];

// =======================================================================
// INIT
// =======================================================================
document.addEventListener("DOMContentLoaded", () => {
    const root = document.getElementById("strategy-dashboard");
    if (!root) return;

    const symbol = root.dataset.symbol;
    const exchange = root.dataset.exchange;
    const network = root.dataset.network;
    const timeframe = root.dataset.timeframe || "15m";

    initChart();
    loadTimeframes(exchange, network, timeframe);
    loadChartData(symbol, timeframe);
    subscribeLive(symbol);

    setInterval(() => {
        loadChartData(symbol, timeframe);
    }, 5000);
});

// =======================================================================
// CHART INIT
// =======================================================================
function initChart() {
    const chartEl = document.getElementById("candles-chart");

    chart = LightweightCharts.createChart(chartEl, {
        width: chartEl.clientWidth,
        height: 500,
        layout: {
            background: { color: "#0e0f11" },
            textColor: "#d0d0d0"
        },
        grid: {
            vertLines: { color: "rgba(200,200,200,0.05)" },
            horzLines: { color: "rgba(200,200,200,0.05)" }
        },
        crosshair: {
            mode: 1
        },
        timeScale: {
            borderColor: "rgba(255,255,255,0.1)",
        }
    });

    candleSeries = chart.addCandlestickSeries({
        upColor: "#2ecc71",
        downColor: "#e74c3c",
        borderUpColor: "#2ecc71",
        borderDownColor: "#e74c3c",
        wickUpColor: "#2ecc71",
        wickDownColor: "#e74c3c"
    });

    ema20Series = chart.addLineSeries({
        color: "#42a5f5",
        lineWidth: 2
    });

    ema50Series = chart.addLineSeries({
        color: "#ab47bc",
        lineWidth: 2
    });

    addTooltip();
}

// =======================================================================
// TOOLTIP
// =======================================================================
function addTooltip() {
    const container = document.getElementById("candles-chart");

    const tooltip = document.createElement("div");
    tooltip.style = `
        position: absolute;
        display: none;
        pointer-events: none;
        background: rgba(0,0,0,0.8);
        border: 1px solid rgba(255,255,255,0.1);
        padding: 8px 10px;
        border-radius: 6px;
        font-size: 12px;
        color: #eee;
        z-index: 999;
    `;
    container.appendChild(tooltip);

    chart.subscribeCrosshairMove(param => {
        if (!param.point || !param.time) {
            tooltip.style.display = "none";
            return;
        }

        const candle = candlesGlobal.find(c => c.time / 1000 === param.time);
        if (!candle) {
            tooltip.style.display = "none";
            return;
        }

        const ema20 = ema20Global.find(e => e.time / 1000 === param.time);
        const ema50 = ema50Global.find(e => e.time / 1000 === param.time);

        const trade = tradesGlobal.find(t => Math.abs(t.time / 1000 - param.time) < 2);

        tooltip.innerHTML = `
          <b>${new Date(candle.time).toLocaleTimeString()}</b><br>
          O: ${candle.open}<br>
          H: ${candle.high}<br>
          L: ${candle.low}<br>
          C: ${candle.close}<br><br>
          <span style="color:#42a5f5">EMA20:</span> ${(ema20?.value || "-")}<br>
          <span style="color:#ab47bc">EMA50:</span> ${(ema50?.value || "-")}<br>
          ${trade ? `
            <hr style="opacity:0.3">
            <b>${trade.side}</b> @ ${trade.price}<br>
            TP: ${trade.tpPrice}<br>
            SL: ${trade.slPrice}<br>
            PnL: ${trade.pnlUsd} USD<br>
            <span style="color:#7bdcb5">ML conf:</span> ${trade.mlConfidence}<br>
            <i>${trade.entryReason}</i>
          ` : ""}
        `;

        const x = param.point.x + 20;
        const y = param.point.y + 20;

        tooltip.style.left = x + "px";
        tooltip.style.top = y + "px";
        tooltip.style.display = "block";
    });
}

// =======================================================================
// LOAD TIMEFRAMES
// =======================================================================
async function loadTimeframes(exchange, network, currentTf) {
    try {
        const url = `/api/exchange/timeframes?exchange=${exchange}&networkType=${network}`;
        const r = await fetch(url);
        const arr = await r.json();

        if (!Array.isArray(arr)) return;

        const select = document.getElementById("timeframe-select");
        select.innerHTML = "";

        arr.forEach(tf => {
            const opt = document.createElement("option");
            opt.value = tf;
            opt.textContent = tf;
            if (tf === currentTf) opt.selected = true;
            select.appendChild(opt);
        });

        select.addEventListener("change", () => {
            loadChartData(
                document.getElementById("strategy-dashboard").dataset.symbol,
                select.value
            );
        });

    } catch (e) {
        console.error("Ошибка загрузки таймфреймов:", e);
    }
}

// =======================================================================
// LOAD HISTORY
// =======================================================================
async function loadChartData(symbol, timeframe) {
    try {
        const url = `/api/chart/candles?symbol=${symbol}&timeframe=${timeframe}&limit=300`;
        const r = await fetch(url);
        const data = await r.json();

        if (!data || !data.candles) return;

        candlesGlobal = data.candles;
        ema20Global = data.emaFast;
        ema50Global = data.emaSlow;
        tradesGlobal = data.trades;

        candleSeries.setData(
            data.candles.map(c => ({
                time: c.time / 1000,
                open: c.open,
                high: c.high,
                low: c.low,
                close: c.close
            }))
        );

        ema20Series.setData(
            data.emaFast.map(e => ({ time: e.time / 1000, value: e.value }))
        );

        ema50Series.setData(
            data.emaSlow.map(e => ({ time: e.time / 1000, value: e.value }))
        );

        updateTradeMarkers();
        updatePriceLine();

    } catch (e) {
        console.error("Ошибка load", e);
    }
}

// =======================================================================
// TRADE MARKERS
// =======================================================================
function updateTradeMarkers() {
    tradeMarkers = tradesGlobal.map(t => ({
        time: t.time / 1000,
        position: t.side === "BUY" ? "belowBar" : "aboveBar",
        color: t.side === "BUY" ? "#2ecc71" : "#e74c3c",
        shape: t.side === "BUY" ? "arrowUp" : "arrowDown",
        text: `${t.side} ${t.price}`
    }));

    candleSeries.setMarkers(tradeMarkers);
}

// =======================================================================
// PRICE LINE
// =======================================================================
function updatePriceLine() {
    const last = candlesGlobal[candlesGlobal.length - 1];
    if (!last) return;

    if (lastPriceLine) {
        candleSeries.removePriceLine(lastPriceLine);
    }

    lastPriceLine = candleSeries.createPriceLine({
        price: last.close,
        color: "#00ff90",
        lineWidth: 2,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title: "Last"
    });
}

// =======================================================================
// LIVE WEBSOCKET
// =======================================================================
function subscribeLive(symbol) {
    const url = `ws://${location.host}/ws/candles?symbol=${symbol}&timeframe=1s`;
    ws = new WebSocket(url);

    ws.onopen = () => console.log("WS OPEN");
    ws.onclose = () => setTimeout(() => subscribeLive(symbol), 2000);

    ws.onmessage = ev => {
        try {
            const c = JSON.parse(ev.data);

            if (c.type === "candle") {
                applyLiveCandle(c);
            }

        } catch (e) {
            console.error("WS error", e);
        }
    };
}

function applyLiveCandle(c) {
    const item = {
        time: c.time / 1000,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close
    };

    candleSeries.update(item);
    updatePriceLine();
}

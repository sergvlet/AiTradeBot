"use strict";

console.log("📈 strategy-live-chart.js loaded (WS v4.1 FINAL)");

// -------------------------------------------------------------
// ГЛОБАЛЬНОЕ СОСТОЯНИЕ
// -------------------------------------------------------------
window.stompClient = window.stompClient || null;

let levelLines = [];
let tradeMarkers = [];
let lastCandles = [];
const MAX_STATS_CANDLES = 500;


// -------------------------------------------------------------
// ЖДЁМ chart, candleSeries (создаются в strategy-chart.js)
// -------------------------------------------------------------
function waitForChart(callback) {
    if (window.chart && window.candleSeries) {
        console.log("✅ chart ready — starting live WS…");
        callback();
    } else {
        setTimeout(() => waitForChart(callback), 150);
    }
}

waitForChart(initLiveSystems);


// -------------------------------------------------------------
// ИНИЦИАЛИЗАЦИЯ ПОСЛЕ ГОТОВОГО ГРАФИКА
// -------------------------------------------------------------
function initLiveSystems() {

    // --- индикаторы ---
    window.emaFastSeries = window.chart.addLineSeries({ lineWidth: 2 });
    window.emaSlowSeries = window.chart.addLineSeries({ lineWidth: 2 });
    window.bbUpperSeries = window.chart.addLineSeries({ lineWidth: 1 });
    window.bbMiddleSeries = window.chart.addLineSeries({ lineWidth: 1 });
    window.bbLowerSeries = window.chart.addLineSeries({ lineWidth: 1 });

    levelLines = [];
    tradeMarkers = [];
    lastCandles = [];

    console.log("📊 Indicator series created");

    connectStompLive();
}


// -------------------------------------------------------------
// ПОДКЛЮЧЕНИЕ STOMP
// -------------------------------------------------------------
function connectStompLive() {

    const dash = document.getElementById("strategy-dashboard");
    if (!dash) return console.error("❌ #strategy-dashboard not found");


    const symbol = dash.dataset.symbol;

    const sock = new SockJS("/ws/strategy");
    window.stompClient = Stomp.over(sock);
    window.stompClient.debug = () => {};

    window.stompClient.connect({}, () => {

        console.log("🟢 STOMP connected");
        setLiveStatus(true);

        const topic = `/topic/live/${symbol}`;
        console.log("📡 Subscribing:", topic);

        window.stompClient.subscribe(topic, raw => {
            try {
                const ev = JSON.parse(raw.body);
                handleLiveEvent(ev);
            } catch (e) {
                console.error("❌ WS JSON error:", e);
            }
        });

    }, () => {
        console.warn("🔴 WS lost, reconnecting in 3s…");
        setLiveStatus(false);
        setTimeout(connectStompLive, 3000);
    });
}


// -------------------------------------------------------------
// ГЛАВНЫЙ РОУТЕР WS-СОБЫТИЙ
// -------------------------------------------------------------
function handleLiveEvent(e) {

    switch (e.type) {

        case "CANDLE": handleCandle(e); break;
        case "TRADE": handleTrade(e); break;
        case "LEVELS": handleLevels(e); break;

        case "SIGNAL": handleSignal(e); break;
        case "METRIC": handleMetric(e); break;

        case "TP":
        case "SL": handleOrderEvent(e); break;

        case "STATE": handleStateEvent(e); break;

        default:
            console.log("⚠ Unknown event:", e);
    }
}


// -------------------------------------------------------------
// CANDLE
// -------------------------------------------------------------
function handleCandle(e) {

    let candle;

    const ts = Math.floor((e.time ?? Date.now()) / 1000);

    if (e.kline) {
        candle = {
            time: ts,
            open: Number(e.kline.open),
            high: Number(e.kline.high),
            low: Number(e.kline.low),
            close: Number(e.kline.close),
        };
    } else if (e.price) {
        candle = {
            time: ts,
            open: +e.price,
            high: +e.price,
            low: +e.price,
            close: +e.price,
        };
    } else return;

    window.candleSeries.update(candle);

    lastCandles.push(candle);
    if (lastCandles.length > MAX_STATS_CANDLES) lastCandles.shift();

    updateMarketStats();
}


// -------------------------------------------------------------
// TRADE
// -------------------------------------------------------------
function handleTrade(e) {

    const ts = Math.floor((e.time ?? Date.now()) / 1000);
    const t = e.trade ?? e;
    const side = (t.side || "").toUpperCase();

    tradeMarkers.push({
        time: ts,
        position: side === "BUY" ? "belowBar" : "aboveBar",
        color: side === "BUY" ? "#2ecc71" : "#e74c3c",
        shape: side === "BUY" ? "arrowUp" : "arrowDown",
        text: side,
    });

    if (tradeMarkers.length > 200) tradeMarkers.shift();

    window.candleSeries.setMarkers(tradeMarkers);
}


// -------------------------------------------------------------
// LEVELS
// -------------------------------------------------------------
function handleLevels(e) {

    const arr = e.levels ?? null;
    if (!arr || !Array.isArray(arr)) return;

    levelLines.forEach(line => window.candleSeries.removePriceLine(line));
    levelLines = [];

    arr.forEach(v => {
        const price = Number(v);
        if (isNaN(price)) return;

        levelLines.push(
            window.candleSeries.createPriceLine({
                price,
                lineStyle: LightweightCharts.LineStyle.Dashed,
                lineWidth: 1,
                color: "#f1c40f",
                axisLabelVisible: true
            })
        );
    });
}


// -------------------------------------------------------------
// SIGNALS
// -------------------------------------------------------------
function handleSignal(e) {
    const sig = e.signal;
    if (!sig || typeof sig.value !== "number") return;

    const ts = Math.floor((e.time ?? Date.now()) / 1000);
    const name = (sig.name || "").toUpperCase();

    const val = sig.value;

    switch (name) {
        case "EMA_FAST": window.emaFastSeries.update({ time: ts, value: val }); break;
        case "EMA_SLOW": window.emaSlowSeries.update({ time: ts, value: val }); break;
        case "BB_UPPER": window.bbUpperSeries.update({ time: ts, value: val }); break;
        case "BB_MIDDLE":
        case "BB_MID": window.bbMiddleSeries.update({ time: ts, value: val }); break;
        case "BB_LOWER": window.bbLowerSeries.update({ time: ts, value: val }); break;
    }
}


// -------------------------------------------------------------
// METRIC
// -------------------------------------------------------------
function handleMetric(e) {
    const el = document.getElementById("ind-last-signal");
    if (el && typeof e.metric === "number") {
        el.textContent = e.metric.toFixed(2) + " %";
    }
}


// -------------------------------------------------------------
// TP / SL
// -------------------------------------------------------------
function handleOrderEvent(e) {
    console.log("ℹ ORDER:", e.type, e.order);
}


// -------------------------------------------------------------
// STATE
// -------------------------------------------------------------
function handleStateEvent(e) {

    const pill = document.getElementById("strategy-status-pill");
    if (!pill) return;

    const running = ["RUNNING", "ACTIVE", "ON"].includes(
        (e.state || "").toUpperCase()
    );

    const dot = pill.querySelector(".status-dot");
    const textSpan = pill.querySelector("span:last-child");

    pill.classList.remove("bg-success-subtle", "bg-danger-subtle");

    if (running) {
        pill.classList.add("bg-success-subtle");
        if (dot) dot.classList.add("status-dot-running");
        if (textSpan) textSpan.textContent = "Работает";
    } else {
        pill.classList.add("bg-danger-subtle");
        if (dot) dot.classList.add("status-dot-stopped");
        if (textSpan) textSpan.textContent = "Остановлена";
    }
}


// -------------------------------------------------------------
// MARKET STATS
// -------------------------------------------------------------
function updateMarketStats() {

    if (lastCandles.length < 2) return;

    const first = lastCandles[0];
    const last = lastCandles[lastCandles.length - 1];

    const lastPrice = last.close;
    const firstPrice = first.close;

    // последняя цена
    const elLast = document.getElementById("stat-last-price");
    if (elLast) elLast.textContent = lastPrice.toFixed(4);

    // изменение %
    const elChange = document.getElementById("stat-change-pct");
    if (elChange) {
        const pct = ((lastPrice - firstPrice) / firstPrice) * 100;
        elChange.textContent = (pct >= 0 ? "+" : "") + pct.toFixed(2) + " %";
        elChange.classList.toggle("text-success", pct >= 0);
        elChange.classList.toggle("text-danger", pct < 0);
    }

    // диапазон min-max
    const elRange = document.getElementById("stat-range");
    if (elRange) {
        const min = Math.min(...lastCandles.map(c => c.low));
        const max = Math.max(...lastCandles.map(c => c.high));
        elRange.textContent = `${min.toFixed(4)} – ${max.toFixed(4)}`;
    }

    // тренд
    const elTrend = document.getElementById("stat-trend");
    if (elTrend) {
        const pct = ((lastPrice - firstPrice) / firstPrice) * 100;
        elTrend.textContent =
            pct > 0.5 ? "Восходящий" : pct < -0.5 ? "Нисходящий" : "Боковик";
    }
}


// -------------------------------------------------------------
// STATUS TEXT
// -------------------------------------------------------------
function setLiveStatus(ok) {
    const el = document.getElementById("live-status");
    if (el) {
        el.textContent = ok ? "LIVE" : "OFFLINE";
        el.style.color = ok ? "#2ecc71" : "#e74c3c";
    }
}

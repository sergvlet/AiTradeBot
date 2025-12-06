"use strict";

console.log("📈 strategy-chart.js loaded (dashboard modular, FIXED v4.4)");

// =============================================================
// ГЛОБАЛЬНОЕ СОСТОЯНИЕ
// =============================================================
let currentWs = null;

let chart,
    candleSeries,
    ema20Series,
    ema50Series,
    bbUpperSeries,
    bbLowerSeries,
    bbMiddleSeries;

let candlesGlobal  = [];
let ema20Global    = [];
let ema50Global    = [];
let bbUpperGlobal  = [];
let bbLowerGlobal  = [];
let bbMiddleGlobal = [];
let tradesGlobal   = [];

let autoScrollToRealTime = true;
let initialDataLoaded    = false;

let lastAnimatedCandle = null;
let animFrame = null;

let lastPriceLine = null;
let strategyRunning = false;

const MAX_CANDLES_HISTORY = 600;
const MAX_TRADES_HISTORY  = 400;

const RIGHT_OFFSET_PX   = 110;
const BASE_BAR_SPACING  = 8;
let   lastBarSpacing    = BASE_BAR_SPACING;

window.setStrategyRunning = f => (strategyRunning = !!f);

// =============================================================
// ХЕЛПЕРЫ
// =============================================================
function normalizeTimeMs(t) {
    if (t == null) return null;
    return t < 1e12 ? t * 1000 : t;
}

function f2(v) {
    return typeof v === "number" ? v.toFixed(2) : "-";
}

function customPriceFormatter(price) {
    const last = candlesGlobal.at(-1);
    const base = last?.close ?? price ?? 0;

    return (
        `PRICE ${f2(base)}\n` +
        `EMA20 ${f2(ema20Global.at(-1)?.value)}\n` +
        `EMA50 ${f2(ema50Global.at(-1)?.value)}\n` +
        `BB↑ ${f2(bbUpperGlobal.at(-1)?.value)}\n` +
        `BB↓ ${f2(bbLowerGlobal.at(-1)?.value)}\n` +
        `BB mid ${f2(bbMiddleGlobal.at(-1)?.value)}`
    );
}

// =============================================================
// ОТСТУП СПРАВА
// =============================================================
function applyRightOffset() {
    if (!chart) return;
    const offsetBars = RIGHT_OFFSET_PX / (lastBarSpacing || BASE_BAR_SPACING);
    chart.timeScale().applyOptions({ rightOffset: offsetBars });
}

// =============================================================
// ИНИЦИАЛИЗАЦИЯ ГРАФИКА
// =============================================================
function initChart() {
    const el = document.getElementById("candles-chart");
    if (!el) return;

    chart = LightweightCharts.createChart(el, {
        width:  el.clientWidth,
        height: 520,
        layout: {
            background: { color: "#0b0c0e" },
            textColor:  "#c7c7c7"
        },
        grid: {
            vertLines: { color: "rgba(255,255,255,0.05)" },
            horzLines: { color: "rgba(255,255,255,0.05)" }
        },
        crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
        timeScale: {
            timeVisible: true,
            secondsVisible: true,
            borderColor: "rgba(255,255,255,0.2)",
            barSpacing: BASE_BAR_SPACING,
            minBarSpacing: 0.5
        },
        rightPriceScale: {
            borderColor: "rgba(255,255,255,0.2)",
            priceFormatter: customPriceFormatter
        }
    });

    lastBarSpacing = BASE_BAR_SPACING;

    candleSeries = chart.addCandlestickSeries({
        upColor: "#2ecc71",
        downColor: "#e74c3c",
        borderUpColor: "#2ecc71",
        borderDownColor: "#e74c3c",
        wickUpColor: "#2ecc71",
        wickDownColor: "#e74c3c",
        priceLineVisible: false
    });

    ema20Series = chart.addLineSeries({ color: "#42a5f5", lineWidth: 2 });
    ema50Series = chart.addLineSeries({ color: "#ab47bc", lineWidth: 2 });

    bbUpperSeries  = chart.addLineSeries({ color: "rgba(255,215,0,0.8)", lineWidth: 1 });
    bbLowerSeries  = chart.addLineSeries({ color: "rgba(255,215,0,0.8)", lineWidth: 1 });
    bbMiddleSeries = chart.addLineSeries({
        color: "rgba(255,255,255,0.4)",
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dotted
    });

    chart.timeScale().subscribeVisibleTimeRangeChange(() => {
        autoScrollToRealTime = chart.timeScale().scrollPosition() < 0.5;
    });

    setupSmoothZoomAndScroll(el);
    initTooltip();
    applyRightOffset();

    window.addEventListener("resize", () => {
        chart.applyOptions({ width: el.clientWidth });
        applyRightOffset();
    });
}

// =============================================================
// ZOOM / SCROLL
// =============================================================
function setupSmoothZoomAndScroll(container) {
    const ts = chart.timeScale();
    ts.applyOptions({ barSpacing: lastBarSpacing });

    container.addEventListener("wheel", e => {
        e.preventDefault();
        const zoomFactor = Math.exp(-e.deltaY * 0.0015);
        lastBarSpacing = Math.min(40, Math.max(0.5, lastBarSpacing * zoomFactor));
        ts.applyOptions({ barSpacing: lastBarSpacing });
        applyRightOffset();
    }, { passive: false });

    let dragging = false, lastX = 0;

    container.addEventListener("mousedown", e => {
        dragging = true; lastX = e.clientX;
    });

    container.addEventListener("mousemove", e => {
        if (!dragging) return;
        ts.scrollToPosition(ts.scrollPosition() - (e.clientX - lastX) / 5, false);
        lastX = e.clientX;
    });

    document.addEventListener("mouseup", () => dragging = false);
    container.addEventListener("mouseleave", () => dragging = false);
}

// =============================================================
// TOOLTIP
// =============================================================
function initTooltip() {
    const container = document.getElementById("candles-chart");
    if (!container) return;

    const tt = document.createElement("div");
    tt.id = "chart-tooltip";
    tt.style.cssText = `
        position:absolute;pointer-events:none;display:none;
        background:rgba(0,0,0,0.9);border:1px solid rgba(255,255,255,.15);
        border-radius:6px;padding:8px 12px;color:#fff;font-size:12px;z-index:99`;
    container.appendChild(tt);

    chart.subscribeCrosshairMove(param => {
        if (!param.point || param.time == null) { tt.style.display = "none"; return; }

        const sec = typeof param.time === "object" ? param.time.timestamp : param.time;
        const tMs = sec * 1000;
        const c = candlesGlobal.find(x => x.time === tMs);
        if (!c) { tt.style.display = "none"; return; }

        tt.innerHTML =
            `<b>${new Date(tMs).toLocaleString()}</b><br>
             O:${c.open} H:${c.high} L:${c.low} C:${c.close}`;

        tt.style.left = param.point.x + 20 + "px";
        tt.style.top  = param.point.y + 20 + "px";
        tt.style.display = "block";
    });
}

// =============================================================
// LOAD TIMEFRAMES
// =============================================================
async function loadTimeframes(exchange, network, currentTf, chatId, symbol) {
    try {
        const r = await fetch(`/api/exchange/timeframes?exchange=${exchange}&networkType=${network}`);
        if (!r.ok) return;

        const arr = await r.json();
        const sel = document.getElementById("timeframe-select");
        if (!sel) return;

        sel.innerHTML = "";
        arr.forEach(tf => {
            const o = document.createElement("option");
            o.value = tf;
            o.textContent = tf;
            if (tf === currentTf) o.selected = true;
            sel.appendChild(o);
        });

        sel.onchange = () => {
            initialDataLoaded = false;
            autoScrollToRealTime = true;
            lastBarSpacing = BASE_BAR_SPACING;

            const tf = sel.value;
            window.AiStrategyChart.loadFullChart(chatId, symbol, tf);
            window.AiStrategyChart.subscribeLive(symbol, tf);
        };

    } catch (e) {
        console.error("loadTimeframes error:", e);
    }
}

// =============================================================
// LOAD FULL CHART
// =============================================================
async function loadFullChart(chatId, symbol, timeframe) {
    if (!chart) initChart();

    const type = document.getElementById("strategy-dashboard")?.dataset?.type;

    try {
        const r = await fetch(
            `/api/chart/strategy?chatId=${chatId}&type=${type}&symbol=${symbol}&timeframe=${timeframe}&limit=300`
        );
        if (!r.ok) return;

        const d = await r.json();

        // ------------------------------------
        // Candles
        // ------------------------------------
        candlesGlobal = (d.candles || []).map(c => ({
            time:  normalizeTimeMs(c.time),
            open:  c.open,
            high:  c.high,
            low:   c.low,
            close: c.close
        }));

        if (candlesGlobal.length > MAX_CANDLES_HISTORY)
            candlesGlobal = candlesGlobal.slice(-MAX_CANDLES_HISTORY);

        candleSeries.setData(
            candlesGlobal.map(c => ({
                time: c.time / 1000,
                open: c.open,
                high: c.high,
                low:  c.low,
                close: c.close
            }))
        );

        // ------------------------------------
        // EMA
        // ------------------------------------
        ema20Global = (d.ema20 || []).map(p => ({
            time: normalizeTimeMs(p.time), value: p.value
        }));
        ema50Global = (d.ema50 || []).map(p => ({
            time: normalizeTimeMs(p.time), value: p.value
        }));

        ema20Series.setData(ema20Global.map(p => ({ time:p.time/1000, value:p.value })));
        ema50Series.setData(ema50Global.map(p => ({ time:p.time/1000, value:p.value })));

        // ------------------------------------
        // Bollinger
        // ------------------------------------
        const bb = d.bollinger || {};
        bbUpperGlobal  = (bb.upper  || []).map(p => ({ time: normalizeTimeMs(p.time), value:p.value }));
        bbLowerGlobal  = (bb.lower  || []).map(p => ({ time: normalizeTimeMs(p.time), value:p.value }));
        bbMiddleGlobal = (bb.middle || []).map(p => ({ time: normalizeTimeMs(p.time), value:p.value }));

        bbUpperSeries .setData(bbUpperGlobal .map(p => ({ time:p.time/1000, value:p.value })));
        bbLowerSeries .setData(bbLowerGlobal .map(p => ({ time:p.time/1000, value:p.value })));
        bbMiddleSeries.setData(bbMiddleGlobal.map(p => ({ time:p.time/1000, value:p.value })));

        // ------------------------------------
        // TRADES
        // ------------------------------------
        tradesGlobal = (d.trades || []).map(t => ({
            time: normalizeTimeMs(t.time),
            price: t.price,
            side: t.side
        }));

        if (tradesGlobal.length > MAX_TRADES_HISTORY)
            tradesGlobal = tradesGlobal.slice(-MAX_TRADES_HISTORY);

        updateTradeMarkers();
        updatePriceLine();
        updateFrontStats();

        applyRightOffset();

        // авто-скролл
        if (!initialDataLoaded) {
            chart.timeScale().scrollToRealTime();
            initialDataLoaded = true;
        } else if (autoScrollToRealTime) {
            chart.timeScale().scrollToRealTime();
        }

    } catch (e) {
        console.error("loadFullChart error:", e);
    }
}

// =============================================================
// ФРОНТ СТАТИСТИКА
// =============================================================
function updateFrontStats() {
    if (!candlesGlobal.length) return;

    const first = candlesGlobal[0];
    const last  = candlesGlobal.at(-1);

    const elLast = document.getElementById("stat-last-price");
    if (elLast) elLast.textContent = f2(last.close);

    const deltaPct = ((last.close - first.close) / first.close) * 100;
    const elChange = document.getElementById("stat-change-pct");
    const elTrend  = document.getElementById("stat-trend");

    if (elChange) {
        elChange.textContent = deltaPct.toFixed(2) + "%";
        elChange.style.color = deltaPct >= 0 ? "#2ecc71" : "#e74c3c";
    }

    if (elTrend) {
        if (deltaPct > 0.4) {
            elTrend.textContent = "Восходящий";
            elTrend.style.color = "#2ecc71";
        } else if (deltaPct < -0.4) {
            elTrend.textContent = "Нисходящий";
            elTrend.style.color = "#e74c3c";
        } else {
            elTrend.textContent = "Боковой";
            elTrend.style.color = "#f1c40f";
        }
    }

    const lows  = candlesGlobal.map(c => c.low);
    const highs = candlesGlobal.map(c => c.high);
    const elRange = document.getElementById("stat-range");
    if (elRange) elRange.textContent = `${Math.min(...lows).toFixed(2)} – ${Math.max(...highs).toFixed(2)}`;
}

// =============================================================
// TRADE MARKERS
// =============================================================
function updateTradeMarkers() {
    candleSeries.setMarkers(
        tradesGlobal.map(t => ({
            time: t.time / 1000,
            position: t.side === "BUY" ? "belowBar" : "aboveBar",
            color: t.side === "BUY" ? "#26a69a" : "#ef5350",
            shape: t.side === "BUY" ? "arrowUp" : "arrowDown",
            text: `${t.side} ${t.price}`
        }))
    );
}

// =============================================================
// ПЛАВНОЕ ОБНОВЛЕНИЕ СВЕЧИ
// =============================================================
function smoothCandleUpdate(newCandle) {
    if (!lastAnimatedCandle) {
        lastAnimatedCandle = { ...newCandle };
        candleSeries.update(newCandle);
        return;
    }

    const start = { ...lastAnimatedCandle };
    const end   = { ...newCandle };

    const duration = 120;
    const startTime = performance.now();

    if (animFrame) cancelAnimationFrame(animFrame);

    function animate(now) {
        const progress = Math.min((now - startTime) / duration, 1);
        const lerp = (a, b) => a + (b - a) * progress;

        const c = {
            time: end.time,
            open:  lerp(start.open,  end.open),
            high:  lerp(start.high,  end.high),
            low:   lerp(start.low,   end.low),
            close: lerp(start.close, end.close)
        };

        candleSeries.update(c);

        if (progress < 1) {
            animFrame = requestAnimationFrame(animate);
        } else {
            lastAnimatedCandle = { ...end };
        }
    }

    animFrame = requestAnimationFrame(animate);
}

// =============================================================
// PRICE LINE
// =============================================================
function updatePriceLine() {
    const last = candlesGlobal.at(-1);
    if (!last) return;

    if (lastPriceLine) candleSeries.removePriceLine(lastPriceLine);

    lastPriceLine = candleSeries.createPriceLine({
        price: last.close,
        color: "rgba(0,255,150,.8)",
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed
    });
}

// =============================================================
// LIVE WEBSOCKET
// =============================================================
function subscribeLive(symbol, timeframe) {
    if (currentWs) {
        try { currentWs.close(1000); } catch {}
        currentWs = null;
    }

    const loc = window.location;
    const proto = loc.protocol === "https:" ? "wss" : "ws";
    const wsUrl = `${proto}://${loc.host}/ws/candles?symbol=${symbol}&timeframe=${timeframe}`;

    const ws = new WebSocket(wsUrl);
    currentWs = ws;

    ws.onopen = () => setLiveStatus(true);
    ws.onerror = () => setLiveStatusError("Ошибка WebSocket");
    ws.onclose = () => setLiveStatus(false);

    ws.onmessage = e => {
        let payload;
        try { payload = JSON.parse(e.data); }
        catch { return; }

        let k = payload?.k || payload?.data?.k || payload?.candle;
        if (!k) return;

        const time = normalizeTimeMs(k.t);
        const open = +k.o, high = +k.h, low = +k.l, close = +k.c;

        const stateC = { time, open, high, low, close };
        const chartC = { time: time / 1000, open, high, low, close };

        smoothCandleUpdate(chartC);

        const last = candlesGlobal.at(-1);
        if (!last || last.time < time) candlesGlobal.push(stateC);
        else candlesGlobal[candlesGlobal.length - 1] = stateC;

        if (candlesGlobal.length > MAX_CANDLES_HISTORY)
            candlesGlobal = candlesGlobal.slice(-MAX_CANDLES_HISTORY);

        updatePriceLine();
        updateFrontStats();

        if (autoScrollToRealTime) chart.timeScale().scrollToRealTime();
    };
}

function setLiveStatus(ok) {
    const el = document.getElementById("live-status");
    if (!el) return;
    el.textContent = ok ? "LIVE" : "OFFLINE";
    el.style.color = ok ? "#2ecc71" : "#e74c3c";
}

function setLiveStatusError(msg) {
    const el = document.getElementById("live-status");
    if (!el) return;
    el.textContent = msg;
    el.style.color = "#f1c40f";
}

// =============================================================
// EXPORT PNG
// =============================================================
function initExportPng() {
    const btn = document.getElementById("btn-export-png");
    if (!btn) return;

    btn.onclick = async () => {
        if (!window.html2canvas) return;
        const el = document.querySelector(".chart-wrapper-main");
        const canvas = await window.html2canvas(el);
        const a = document.createElement("a");
        a.href = canvas.toDataURL("image/png");
        a.download = "chart.png";
        a.click();
    };
}

// =============================================================
// START/STOP BUTTONS
// =============================================================
function initStartStopButtons() {
    const root = document.getElementById("strategy-dashboard");
    if (!root) return;

    const chatId = root.dataset.chatId;
    const type = root.dataset.type;

    const start = document.getElementById("btn-start");
    const stop  = document.getElementById("btn-stop");

    if (start) start.onclick = () =>
        fetch(`/api/strategy/start?chatId=${chatId}&type=${type}`, { method:"POST" });

    if (stop) stop.onclick = () =>
        fetch(`/api/strategy/stop?chatId=${chatId}&type=${type}`, { method:"POST" });
}

// =============================================================
// ПУБЛИЧНЫЙ API
// =============================================================
window.AiStrategyChart = {
    initChart,
    loadFullChart,
    subscribeLive,
    loadTimeframes,
    initExportPng,
    initStartStopButtons
};

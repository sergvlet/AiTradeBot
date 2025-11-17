console.log("📈 strategy-chart.js loaded (SUPER FIX v6 — FULL LOGGING MODE)");

//
// === GLOBAL STATE ===
//
let chart,
    candleSeries,
    ema20Series,
    ema50Series,
    bbUpperSeries,
    bbLowerSeries,
    bbMiddleSeries;

let ws = null;

// raw data
let candlesGlobal = [];       // [{time(ms),open,high,low,close}]
let ema20Global = [];
let ema50Global = [];
let bbUpperGlobal = [];
let bbLowerGlobal = [];
let bbMiddleGlobal = [];
let tradesGlobal = [];

// chart behavior
let autoScrollToRealTime = true;
let initialDataLoaded = false;

// UI
let lastPriceLine = null;
let strategyRunning = false;
window.setStrategyRunning = f => strategyRunning = !!f;

// =====================================================================
// PAGE INIT
// =====================================================================
document.addEventListener("DOMContentLoaded", () => {
    const root = document.getElementById("strategy-dashboard");
    if (!root) {
        console.warn("❗ strategy-dashboard root not found");
        return;
    }

    const chatId    = Number(root.dataset.chatId || "0");
    const symbol    = root.dataset.symbol;
    const exchange  = root.dataset.exchange;
    const network   = root.dataset.network;
    const timeframe = root.dataset.timeframe || "1m";

    console.log("▶️ INIT page:", {chatId, symbol, exchange, network, timeframe});

    initChart();
    loadTimeframes(exchange, network, timeframe);

    console.log("▶️ Loading initial chart...");
    loadFullChart(chatId, symbol, timeframe, { initial: true });

    subscribeLive(symbol);

    setInterval(() => {
        console.log("⏱ 7-second refresh triggered");
        loadFullChart(chatId, symbol, getCurrentTf(), { initial: false });
    }, 7000);
});

function getCurrentTf() {
    const s = document.getElementById("timeframe-select");
    return s ? (s.value || "1m") : "1m";
}

// =====================================================================
// INIT CHART
// =====================================================================
function initChart() {
    console.log("🖼 initChart() start");

    const el = document.getElementById("candles-chart");
    if (!el) {
        console.error("❗ candles-chart element missing");
        return;
    }

    chart = LightweightCharts.createChart(el, {
        width: el.clientWidth,
        height: 520,
        layout: {
            background: { color: "#0b0c0e" },
            textColor: "#c7c7c7"
        },
        grid: {
            vertLines: { color: "rgba(255,255,255,0.05)" },
            horzLines: { color: "rgba(255,255,255,0.05)" }
        },
        crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
        timeScale: {
            timeVisible: true,
            secondsVisible: true,
            borderColor: "rgba(255,255,255,0.2)"
        },
        rightPriceScale: {
            borderColor: "rgba(255,255,255,0.2)"
        }
    });

    console.log("🖌 Chart created");

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

    console.log("📊 Series created");

    chart.timeScale().subscribeVisibleTimeRangeChange(() => {
        const sc = chart.timeScale().scrollPosition();
        autoScrollToRealTime = sc < 0.5;
        console.debug("📏 scrollPosition:", sc, "autoScroll:", autoScrollToRealTime);
    });

    initTooltip();
    console.log("🖼 initChart() done");
}

// =====================================================================
// TOOLTIP
// =====================================================================
function initTooltip() {
    console.log("🧰 initTooltip");

    const container = document.getElementById("candles-chart");
    if (!container) {
        console.warn("❗ Cannot init tooltip: container missing");
        return;
    }

    container.style.position = "relative";

    const tt = document.createElement("div");
    tt.id = "chart-tooltip";
    tt.style.position = "absolute";
    tt.style.pointerEvents = "none";
    tt.style.display = "none";
    tt.style.background = "rgba(0,0,0,0.90)";
    tt.style.border = "1px solid rgba(255,255,255,0.15)";
    tt.style.borderRadius = "6px";
    tt.style.padding = "8px 12px";
    tt.style.color = "#fff";
    tt.style.fontSize = "12px";
    tt.style.zIndex = "99999";

    container.appendChild(tt);

    chart.subscribeCrosshairMove(param => {
        if (!param.time || !param.point) {
            tt.style.display = "none";
            return;
        }

        const tMs = param.time * 1000;
        const c = candlesGlobal.find(x => x.time === tMs);
        if (!c) {
            tt.style.display = "none";
            return;
        }

        const e20 = ema20Global.find(e => e.time === tMs);
        const e50 = ema50Global.find(e => e.time === tMs);
        const tr  = tradesGlobal.find(t => Math.abs(t.time - tMs) < 2000);

        tt.innerHTML = `
            <b>${new Date(tMs).toLocaleString()}</b><br>
            O: ${c.open}<br>
            H: ${c.high}<br>
            L: ${c.low}<br>
            C: ${c.close}<br>
            <span style="color:#42a5f5">EMA20:</span> ${e20?.value ?? "-"}<br>
            <span style="color:#ab47bc">EMA50:</span> ${e50?.value ?? "-"}<br>
            ${tr ? `<hr style="margin:4px 0;opacity:0.6;">${tr.side} @ ${tr.price}` : ""}
        `;

        const offsetX = 35;
        const offsetY = 20;
        const rect = container.getBoundingClientRect();

        let left = param.point.x + offsetX;
        let top  = param.point.y + offsetY;

        tt.style.display = "block";
        tt.style.left = left + "px";
        tt.style.top  = top + "px";

        const w = tt.offsetWidth;
        const h = tt.offsetHeight;

        if (left + w > rect.width - 10) left = param.point.x - w - offsetX;
        if (top + h > rect.height - 10) top  = param.point.y - h - offsetY;

        tt.style.left = left + "px";
        tt.style.top  = top + "px";
    });

    console.log("🧰 Tooltip ready");
}

// =====================================================================
// LOAD TIMEFRAMES
// =====================================================================
async function loadTimeframes(exchange, network, currentTf) {
    console.log("⏳ loadTimeframes...", {exchange, network, currentTf});

    try {
        const r = await fetch(`/api/exchange/timeframes?exchange=${exchange}&networkType=${network}`);
        console.log("⏳ loadTimeframes HTTP", r.status);
        const arr = await r.json();
        console.log("💾 timeframes:", arr);

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

        sel.addEventListener("change", () => {
            const root = document.getElementById("strategy-dashboard");
            const chatId = Number(root.dataset.chatId || "0");
            const symbol = root.dataset.symbol;

            console.log("🎛 Timeframe changed to", sel.value);

            autoScrollToRealTime = true;
            initialDataLoaded = false;
            loadFullChart(chatId, symbol, sel.value, { initial: true });
        });
    } catch (e) {
        console.error("❌ loadTimeframes error:", e);
    }
}

// =====================================================================
// FULL CHART LOADING
// =====================================================================
async function loadFullChart(chatId, symbol, timeframe, opts = {}) {
    const initial = !!opts.initial;

    console.log("⏬ loadFullChart()", {chatId, symbol, timeframe, initial});

    try {
        const url = `/api/chart/full?chatId=${chatId}&symbol=${symbol}&timeframe=${timeframe}&limit=300`;
        console.log("📡 GET", url);

        const r = await fetch(url);
        if (!r.ok) {
            console.error("❌ loadFullChart HTTP", r.status);
            return;
        }

        const d = await r.json();
        console.log("📦 full chart payload:", d);

        // ----- CANDLES ----------------------------------------------------
        if (initial && Array.isArray(d.candles)) {
            console.log("📦 updating candles initial:", d.candles.length);

            candlesGlobal = d.candles.map(c => ({
                time: c.time,
                open: c.open,
                high: c.high,
                low:  c.low,
                close:c.close
            }));

            candleSeries.setData(
                candlesGlobal.map(c => ({
                    time: c.time / 1000,
                    open: c.open,
                    high: c.high,
                    low:  c.low,
                    close:c.close
                }))
            );
        }

        // EMA FAST/SLOW
        ema20Global = (d.ema20 || d.emaFast || []).map(p => ({time:p.time, value:p.value}));
        ema50Global = (d.ema50 || d.emaSlow || []).map(p => ({time:p.time, value:p.value}));

        console.log("📘 EMA20:", ema20Global.length, "📙 EMA50:", ema50Global.length);

        ema20Series.setData(ema20Global.map(p => ({ time:p.time/1000, value:p.value })));
        ema50Series.setData(ema50Global.map(p => ({ time:p.time/1000, value:p.value })));

        // BOLLINGER
        const bb = d.bollinger || {};
        bbUpperGlobal  = (bb.upper  || []).map(p => ({time:p.time, value:p.value}));
        bbLowerGlobal  = (bb.lower  || []).map(p => ({time:p.time, value:p.value}));
        bbMiddleGlobal = (bb.middle || []).map(p => ({time:p.time, value:p.value}));

        console.log("📙 Bollinger:", {
            upper: bbUpperGlobal.length,
            lower: bbLowerGlobal.length,
            middle:bbMiddleGlobal.length
        });

        bbUpperSeries.setData (bbUpperGlobal .map(p => ({ time:p.time/1000, value:p.value })));
        bbLowerSeries.setData (bbLowerGlobal .map(p => ({ time:p.time/1000, value:p.value })));
        bbMiddleSeries.setData(bbMiddleGlobal.map(p => ({ time:p.time/1000, value:p.value })));

        // TRADES
        tradesGlobal = (d.trades || []).map(t => ({
            time: t.time,
            price: t.price,
            qty: t.qty,
            side: t.side
        }));
        console.log("📍 Trades:", tradesGlobal.length);

        updateTradeMarkers();
        updatePriceLine();
        updateFrontStats();

        const stats = d.stats || d.kpis || {};
        if (document.getElementById("stat-winrate"))
            document.getElementById("stat-winrate").textContent =
                (typeof stats.winRate === "number") ? stats.winRate.toFixed(2)+"%" : "—";
        if (document.getElementById("stat-roi"))
            document.getElementById("stat-roi").textContent =
                (typeof stats.roi === "number") ? stats.roi.toFixed(2)+"%" : "—";

        // SCALE
        if (!initialDataLoaded) {
            console.log("⏩ fitContent()");
            chart.timeScale().fitContent();

            console.log("⏩ scrollToRealTime()");
            chart.timeScale().scrollToRealTime();

            initialDataLoaded = true;
        } else if (autoScrollToRealTime) {
            console.log("⏩ Auto scrollToRealTime()");
            chart.timeScale().scrollToRealTime();
        }
    } catch (e) {
        console.error("❌ loadFullChart error:", e);
    }
}

// =====================================================================
// UPDATE CARDS
// =====================================================================
function updateFrontStats() {
    if (!candlesGlobal.length) return;

    const first = candlesGlobal[0];
    const last  = candlesGlobal[candlesGlobal.length - 1];

    console.log("📊 updateFrontStats(): last close =", last.close);

    const elLast = document.getElementById("stat-last-price");
    if (elLast) {
        const old = parseFloat(elLast.dataset.old || last.close);
        elLast.dataset.old = String(last.close);
        elLast.textContent = last.close.toFixed(2);

        if (last.close > old) elLast.style.color = "#2ecc71";
        else if (last.close < old) elLast.style.color = "#e74c3c";
        else elLast.style.color = "";
    }

    const elChange = document.getElementById("stat-change-pct");
    if (elChange) {
        const pct = ((last.close - first.close) / first.close) * 100;
        elChange.textContent = pct.toFixed(2) + "%";
        elChange.style.color = pct >= 0 ? "#2ecc71" : "#e74c3c";
    }

    const elRange = document.getElementById("stat-range");
    if (elRange) {
        const low  = Math.min(...candlesGlobal.map(c => c.low));
        const high = Math.max(...candlesGlobal.map(c => c.high));
        elRange.textContent = `${low.toFixed(2)} – ${high.toFixed(2)}`;
    }

    const elTrend = document.getElementById("stat-trend");
    if (elTrend) {
        const arr = candlesGlobal.slice(-20).map(c => c.close);
        let momentum = 0;
        for (let i = 1; i < arr.length; i++)
            momentum += arr[i] - arr[i - 1];

        if (momentum > 0) {
            elTrend.textContent = "Рост";
            elTrend.style.color = "#2ecc71";
        } else if (momentum < 0) {
            elTrend.textContent = "Падение";
            elTrend.style.color = "#e74c3c";
        } else {
            elTrend.textContent = "Флэт";
            elTrend.style.color = "#95a5a6";
        }
    }
}

// =====================================================================
// TRADE MARKERS
// =====================================================================
function updateTradeMarkers() {
    console.log("📍 updateTradeMarkers, count =", tradesGlobal.length);

    candleSeries.setMarkers(
        tradesGlobal.map(t => ({
            time: t.time / 1000,
            position: t.side === "BUY" ? "belowBar" : "aboveBar",
            color: t.side === "BUY" ? "#26a69a" : "#ef5350",
            shape: t.side === "BUY" ? "arrowUp" : "arrowDown",
            text: `${t.side} @ ${t.price}`
        }))
    );
}

// =====================================================================
// PRICE LINE
// =====================================================================
function updatePriceLine() {
    const last = candlesGlobal[candlesGlobal.length - 1];
    if (!last) return;

    console.log("📏 updatePriceLine:", last.close);

    if (lastPriceLine) candleSeries.removePriceLine(lastPriceLine);

    lastPriceLine = candleSeries.createPriceLine({
        price: last.close,
        lineWidth: 2,
        color: "rgba(0,255,150,0.9)",
        title: last.close.toFixed(2)
    });
}

// =====================================================================
// LIVE WEBSOCKET
// =====================================================================
function subscribeLive(symbol) {
    const url = `ws://${location.host}/ws/candles?symbol=${symbol}&timeframe=1s`;

    console.log("🔌 Opening WS:", url);

    if (ws) {
        try {
            ws.close();
            console.log("🔌 Old WS closed");
        } catch (_) {}
    }

    ws = new WebSocket(url);

    ws.onopen = () => console.log("🔌 WS OPEN");
    ws.onerror = e  => console.error("❌ WS ERROR", e);
    ws.onclose = () => {
        console.warn("⚠️ WS CLOSED, retry in 1.5s...");
        setTimeout(() => subscribeLive(symbol), 1500);
    };

    ws.onmessage = ev => {
        console.log("📨 WS RAW:", ev.data);

        try {
            const x = JSON.parse(ev.data);
            console.log("📩 WS JSON parsed:", x);

            let tsMs, o, h, l, c;

            // full candle: { t,o,h,l,c }
            if (typeof x.t === "number" && x.o !== undefined) {
                console.log("⚡ WS FORMAT: FULL CANDLE t/o/h/l/c");
                tsMs = x.t;
                o = x.o; h = x.h; l = x.l; c = x.c;

                // alt candle { time,open,... }
            } else if (typeof x.time === "number" && x.open !== undefined) {
                console.log("⚡ WS FORMAT: ALT CANDLE");
                tsMs = x.time;
                o = x.open; h = x.high; l = x.low; c = x.close;

                // tick { time, price }
            } else if (typeof x.time === "number" && x.price !== undefined) {
                console.log("⚡ WS FORMAT: TICK");
                tsMs = x.time;
                o = c = x.price;
                h = l = x.price;

            } else {
                console.warn("❓ Unknown WS format", x);
                return;
            }

            const item = {
                time: tsMs / 1000,
                open: o,
                high: h,
                low:  l,
                close:c
            };

            console.log("📍 WS candle update:", item);

            candleSeries.update(item);

            const last = candlesGlobal[candlesGlobal.length - 1];
            if (!last || last.time !== tsMs) {
                console.log("🍏 New candle appended");
                candlesGlobal.push({
                    time: tsMs,
                    open: o,
                    high: h,
                    low:  l,
                    close:c
                });
            } else {
                console.log("🍎 Candle updated:", last);
                last.open = o;
                last.high = h;
                last.low  = l;
                last.close= c;
            }

            updatePriceLine();
            updateFrontStats();

            if (autoScrollToRealTime) chart.timeScale().scrollToRealTime();

        } catch (e) {
            console.error("❌ WS PARSE ERR", e);
        }
    };
}

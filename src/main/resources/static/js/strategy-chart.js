console.log("📈 strategy-chart.js loaded (FIXED EDITION v12)");

//
// === GLOBAL STATE ===
//
let currentWs = null;

let chart,
    candleSeries,
    ema20Series,
    ema50Series,
    bbUpperSeries,
    bbLowerSeries,
    bbMiddleSeries;

let candlesGlobal = [];
let ema20Global = [];
let ema50Global = [];
let bbUpperGlobal = [];
let bbLowerGlobal = [];
let bbMiddleGlobal = [];
let tradesGlobal = [];

let autoScrollToRealTime = true;
let initialDataLoaded = false;

let lastPriceLine = null;
let strategyRunning = false;

window.setStrategyRunning = f => (strategyRunning = !!f);

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

    console.log("▶️ INIT:", { chatId, symbol, exchange, network, timeframe });

    initChart();

    loadTimeframes(exchange, network, timeframe);
    loadFullChart(chatId, symbol, timeframe, { initial: true });
    subscribeLive(symbol, timeframe);

    // периодический фулл-рефреш
    setInterval(() => {
        const tf = getCurrentTf();
        loadFullChart(chatId, symbol, tf, { initial: false });
    }, 7000);
});

function getCurrentTf() {
    const s = document.getElementById("timeframe-select");
    return s ? s.value || "1m" : "1m";
}

// =====================================================================
// INIT CHART
// =====================================================================
function initChart() {
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

    candleSeries = chart.addCandlestickSeries({
        upColor: "#2ecc71",
        downColor: "#e74c3c",
        borderUpColor: "#2ecc71",
        borderDownColor: "#e74c3c",
        wickUpColor: "#2ecc71",
        wickDownColor: "#e74c3c"
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
        const sc = chart.timeScale().scrollPosition();
        autoScrollToRealTime = sc < 0.5;
    });

    initTooltip();
}

// =====================================================================
// TOOLTIP
// =====================================================================
function initTooltip() {
    const container = document.getElementById("candles-chart");
    if (!container) return;

    container.style.position = "relative";

    const tt = document.createElement("div");
    tt.id = "chart-tooltip";
    tt.style = `
        position:absolute;pointer-events:none;display:none;
        background:rgba(0,0,0,0.90);
        border:1px solid rgba(255,255,255,0.15);
        border-radius:6px;padding:8px 12px;
        color:#fff;font-size:12px;z-index:99999;
    `;
    container.appendChild(tt);

    chart.subscribeCrosshairMove(param => {
        if (!param.point || !param.time) {
            tt.style.display = "none";
            return;
        }

        // param.time — секунды, переводим в ms
        const tMs = param.time * 1000;
        const c = candlesGlobal.find(x => x.time === tMs);
        if (!c) {
            tt.style.display = "none";
            return;
        }

        const e20 = ema20Global.find(e => e.time === tMs);
        const e50 = ema50Global.find(e => e.time === tMs);
        const tr  = tradesGlobal.find(t => Math.abs(t.time - tMs) < 1500);

        tt.innerHTML = `
            <b>${new Date(tMs).toLocaleString()}</b><br>
            O: ${c.open}<br>
            H: ${c.high}<br>
            L: ${c.low}<br>
            C: ${c.close}<br>
            <span style="color:#42a5f5">EMA20:</span> ${e20?.value ?? "-"}<br>
            <span style="color:#ab47bc">EMA50:</span> ${e50?.value ?? "-"}<br>
            ${tr ? `<hr>${tr.side} @ ${tr.price}` : ""}
        `;

        let left = param.point.x + 30;
        let top  = param.point.y + 20;

        const rect = container.getBoundingClientRect();
        const w = tt.offsetWidth;
        const h = tt.offsetHeight;

        if (left + w > rect.width) left = param.point.x - w - 30;
        if (top + h > rect.height) top = param.point.y - h - 20;

        tt.style.left = left + "px";
        tt.style.top  = top + "px";
        tt.style.display = "block";
    });
}

// =====================================================================
// LOAD TIMEFRAMES
// =====================================================================
async function loadTimeframes(exchange, network, currentTf) {
    try {
        const r = await fetch(`/api/exchange/timeframes?exchange=${exchange}&networkType=${network}`);
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

        sel.addEventListener("change", () => {
            const root = document.getElementById("strategy-dashboard");
            const chatId = Number(root.dataset.chatId || "0");
            const symbol = root.dataset.symbol;
            const tf = sel.value;

            initialDataLoaded = false;
            autoScrollToRealTime = true;

            loadFullChart(chatId, symbol, tf, { initial: true });
            subscribeLive(symbol, tf);
        });
    } catch (e) {
        console.error("❌ loadTimeframes error:", e);
    }
}

// =====================================================================
// LOAD FULL CHART
// =====================================================================
async function loadFullChart(chatId, symbol, timeframe, opts = {}) {
    const initial = !!opts.initial;
    try {
        const r = await fetch(`/api/chart/full?chatId=${chatId}&symbol=${symbol}&timeframe=${timeframe}&limit=300`);
        if (!r.ok) return;

        const d = await r.json();

        // Candles: храним time в ms, в график даём секунды
        candlesGlobal = (d.candles || []).map(c => ({
            time: c.time,          // ms
            open: c.open,
            high: c.high,
            low:  c.low,
            close:c.close
        }));

        candleSeries.setData(
            candlesGlobal.map(c => ({
                time: c.time / 1000, // s
                open: c.open,
                high: c.high,
                low:  c.low,
                close:c.close
            }))
        );

        // EMA
        ema20Global = (d.ema20 || []).map(p => ({ time:p.time, value:p.value })); // ms
        ema50Global = (d.ema50 || []).map(p => ({ time:p.time, value:p.value })); // ms

        ema20Series.setData(ema20Global.map(p => ({ time:p.time / 1000, value:p.value })));
        ema50Series.setData(ema50Global.map(p => ({ time:p.time / 1000, value:p.value })));

        // Bollinger
        const bb = d.bollinger || {};
        bbUpperGlobal  = (bb.upper  || []).map(p => ({ time:p.time, value:p.value }));
        bbLowerGlobal  = (bb.lower  || []).map(p => ({ time:p.time, value:p.value }));
        bbMiddleGlobal = (bb.middle || []).map(p => ({ time:p.time, value:p.value }));

        bbUpperSeries .setData(bbUpperGlobal .map(p => ({ time:p.time / 1000, value:p.value })));
        bbLowerSeries .setData(bbLowerGlobal .map(p => ({ time:p.time / 1000, value:p.value })));
        bbMiddleSeries.setData(bbMiddleGlobal.map(p => ({ time:p.time / 1000, value:p.value })));

        // Trades
        tradesGlobal = (d.trades || []).map(t => ({
            time: t.time,     // ms
            price:t.price,
            side: t.side
        }));
        updateTradeMarkers();

        updatePriceLine();
        updateFrontStats();

        if (!initialDataLoaded) {
            chart.timeScale().fitContent();
            chart.timeScale().scrollToRealTime();
            initialDataLoaded = true;
        } else if (autoScrollToRealTime) {
            chart.timeScale().scrollToRealTime();
        }
    } catch (e) {
        console.error("❌ loadFullChart error:", e);
    }
}

// =====================================================================
// UPDATE STATS
// =====================================================================
function updateFrontStats() {
    if (!candlesGlobal.length) return;

    const first = candlesGlobal[0];
    const last  = candlesGlobal[candlesGlobal.length - 1];

    const elLast = document.getElementById("stat-last-price");
    if (elLast) elLast.textContent = last.close.toFixed(2);

    const elChange = document.getElementById("stat-change-pct");
    if (elChange) {
        const pct = ((last.close - first.close) / first.close) * 100;
        elChange.textContent = pct.toFixed(2) + "%";
        elChange.style.color = pct > 0 ? "#2ecc71" : "#e74c3c";
    }

    const elRange = document.getElementById("stat-range");
    if (elRange) {
        const lows  = candlesGlobal.map(c => c.low);
        const highs = candlesGlobal.map(c => c.high);
        elRange.textContent = `${Math.min(...lows).toFixed(2)} – ${Math.max(...highs).toFixed(2)}`;
    }
}

// =====================================================================
// TRADE MARKERS
// =====================================================================
function updateTradeMarkers() {
    candleSeries.setMarkers(
        tradesGlobal.map(t => ({
            time: t.time / 1000,  // s
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

    if (lastPriceLine) candleSeries.removePriceLine(lastPriceLine);

    lastPriceLine = candleSeries.createPriceLine({
        price: last.close,
        lineWidth: 2,
        color: "rgba(0,255,150,0.9)",
        title: last.close.toFixed(2)
    });
}

// =====================================================================
// LIVE WEBSOCKET — FIXED
// =====================================================================
function subscribeLive(symbol, timeframe) {
    console.log('[WS] subscribeLive init', { symbol, timeframe });

    // закрываем старый сокет, если был
    if (currentWs) {
        console.log('[WS] closing previous websocket');
        try {
            currentWs.close(1000, 'switch symbol/timeframe');
        } catch (e) {
            console.warn('[WS] error closing previous ws', e);
        }
        currentWs = null;
    }

    const loc = window.location;
    const protocol = loc.protocol === 'https:' ? 'wss' : 'ws';
    const wsUrl = `${protocol}://${loc.host}/ws/candles?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`;

    console.log('[WS] connecting to', wsUrl);

    const ws = new WebSocket(wsUrl);
    currentWs = ws;

    ws.onopen = () => {
        console.log('[WS] OPEN', wsUrl);
        setLiveStatus(true);
    };

    ws.onerror = (err) => {
        console.error('[WS] ERROR', err);
        setLiveStatusError('Ошибка WebSocket');
    };

    ws.onclose = (evt) => {
        console.log('[WS] CLOSE', { code: evt.code, reason: evt.reason });
        setLiveStatus(false);
    };

    ws.onmessage = (event) => {
        console.log('[WS] MESSAGE raw', event.data);

        let payload;
        try {
            payload = JSON.parse(event.data);
        } catch (e) {
            console.error('[WS] parse error', e);
            return;
        }

        if (!payload || payload.type !== 'tick' || !payload.candle) {
            console.debug('[WS] skip message (not tick)', payload);
            return;
        }

        const c = payload.candle;

        // сервер шлёт time в ms → храним в ms, в график даём секунды
        const timeMs  = c.time;
        const candleForState = {
            time:  timeMs,      // ms
            open:  c.open,
            high:  c.high,
            low:   c.low,
            close: c.close
        };
        const candleForChart = {
            time:  timeMs / 1000, // s
            open:  c.open,
            high:  c.high,
            low:   c.low,
            close: c.close
        };

        console.log('[WS] TICK parsed', candleForState);

        // обновляем график
        candleSeries.update(candleForChart);

        if (!Array.isArray(candlesGlobal)) candlesGlobal = [];

        if (candlesGlobal.length === 0) {
            candlesGlobal.push(candleForState);
        } else {
            const last = candlesGlobal[candlesGlobal.length - 1];

            if (last.time === candleForState.time) {
                candlesGlobal[candlesGlobal.length - 1] = candleForState;
            } else if (candleForState.time > last.time) {
                candlesGlobal.push(candleForState);
            } else {
                // если вдруг пришёл тик "из прошлого"
                console.warn('[WS] received out-of-order tick', { last, candle: candleForState });
            }
        }

        updatePriceLabel(candleForState.close);

        if (typeof updatePriceLine === 'function') updatePriceLine();
        if (typeof updateFrontStats === 'function') updateFrontStats();

        if (autoScrollToRealTime && chart && chart.timeScale) {
            chart.timeScale().scrollToRealTime();
        }
    };
}

// =====================================================================
// LIVE STATUS INDICATORS
// =====================================================================
function setLiveStatus(isOk) {
    const el = document.getElementById("live-status");
    if (!el) {
        console.warn("⚠️ live-status element not found");
        return;
    }

    if (isOk) {
        el.textContent = "LIVE";
        el.style.color = "#2ecc71";
    } else {
        el.textContent = "OFFLINE";
        el.style.color = "#e74c3c";
    }
}

function setLiveStatusError(msg) {
    const el = document.getElementById("live-status");
    if (!el) {
        console.warn("⚠️ live-status element not found");
        return;
    }
    el.textContent = msg || "ERROR";
    el.style.color = "#f1c40f";
}

// =====================================================================
// PRICE LABEL (top-right)
// =====================================================================
function updatePriceLabel(price) {
    const el = document.getElementById("stat-last-price");
    if (el) el.textContent = price.toFixed(2);
}

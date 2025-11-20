"use strict";

console.log("📈 strategy-chart.js loaded (FIXED EDITION v16)");

//
// === ГЛОБАЛЬНОЕ СОСТОЯНИЕ ===
//
let currentWs = null;

let chart,
    candleSeries,
    ema20Series,
    ema50Series,
    bbUpperSeries,
    bbLowerSeries,
    bbMiddleSeries;

// История
let candlesGlobal  = [];
let ema20Global    = [];
let ema50Global    = [];
let bbUpperGlobal  = [];
let bbLowerGlobal  = [];
let bbMiddleGlobal = [];
let tradesGlobal   = [];

// Флаги
let autoScrollToRealTime = true;
let initialDataLoaded    = false;

// Price lines (встроенные Lightweight Charts)
let lastPriceLine      = null;
let ema20PriceLine     = null;
let ema50PriceLine     = null;
let bbUpperPriceLine   = null;
let bbLowerPriceLine   = null;
let bbMiddlePriceLine  = null;

let strategyRunning = false;

// ограничения по глубине истории (защита памяти)
const MAX_CANDLES_HISTORY = 600;
const MAX_TRADES_HISTORY  = 400;

// внешний хук — НЕ ТРОГАЕМ
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

    // периодический full-refresh (сверка с WS)
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
// INIT CHART + контейнер с колонкой под подписи справа
// =====================================================================
function initChart() {
    const outer = document.getElementById("candles-chart");
    if (!outer) {
        console.error("❗ candles-chart element missing");
        return;
    }

    // Подготовим layout:
    // [ chartHost | labelsPane ]
    outer.innerHTML = "";
    outer.style.position = "relative";
    outer.style.height = outer.style.height || "520px";

    const chartHost = document.createElement("div");
    chartHost.id = "candles-chart-inner";
    chartHost.style.position = "absolute";
    chartHost.style.left = "0";
    chartHost.style.top = "0";
    chartHost.style.bottom = "0";
    chartHost.style.right = "70px"; // место под надписи справа

    const labelsPane = document.createElement("div");
    labelsPane.id = "price-labels-pane";
    labelsPane.style.position = "absolute";
    labelsPane.style.top = "0";
    labelsPane.style.bottom = "0";
    labelsPane.style.right = "0";
    labelsPane.style.width = "70px";
    labelsPane.style.display = "block";
    labelsPane.style.pointerEvents = "none";

    outer.appendChild(chartHost);
    outer.appendChild(labelsPane);

    const width  = chartHost.clientWidth || 600;
    const height = chartHost.clientHeight || 520;

    chart = LightweightCharts.createChart(chartHost, {
        width:  width,
        height: height,
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
            timeVisible:    true,
            secondsVisible: true,
            borderColor:    "rgba(255,255,255,0.2)",
            rightOffset:    15,   // отступ вправо, чтобы последние свечи не упирались в край
            barSpacing:     8,
            minBarSpacing:  0.5
        },
        rightPriceScale: {
            borderColor: "rgba(255,255,255,0.2)"
        }
    });

    candleSeries = chart.addCandlestickSeries({
        upColor:         "#2ecc71",
        downColor:       "#e74c3c",
        borderUpColor:   "#2ecc71",
        borderDownColor: "#e74c3c",
        wickUpColor:     "#2ecc71",
        wickDownColor:   "#e74c3c",
        priceLineVisible: false // встроенную линию цены не показываем, используем свою
    });

    ema20Series = chart.addLineSeries({
        color: "#42a5f5",
        lineWidth: 2,
        priceLineVisible: false
    });

    ema50Series = chart.addLineSeries({
        color: "#ab47bc",
        lineWidth: 2,
        priceLineVisible: false
    });

    bbUpperSeries  = chart.addLineSeries({
        color: "rgba(255,215,0,0.8)",
        lineWidth: 1,
        priceLineVisible: false
    });

    bbLowerSeries  = chart.addLineSeries({
        color: "rgba(255,215,0,0.8)",
        lineWidth: 1,
        priceLineVisible: false
    });

    bbMiddleSeries = chart.addLineSeries({
        color:     "rgba(255,255,255,0.4)",
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dotted,
        priceLineVisible: false
    });

    // автоскролл: если пользователь ушёл влево — не тянем график назад
    chart.timeScale().subscribeVisibleTimeRangeChange(() => {
        const sc = chart.timeScale().scrollPosition();
        autoScrollToRealTime = sc < 0.5;
    });

    // ресайз при изменении окна
    window.addEventListener("resize", () => {
        if (!chartHost || !chart) return;
        const w = chartHost.clientWidth || 600;
        const h = chartHost.clientHeight || 520;
        chart.applyOptions({ width: w, height: h });
    });

    setupSmoothZoomAndScroll(chartHost);
    initTooltip();
}

// =====================================================================
// SMOOTH ZOOM & SCROLL (TradingView-like)
// =====================================================================
function setupSmoothZoomAndScroll(container) {
    if (!chart) return;

    const timeScale = chart.timeScale();

    // Zoom колесом
    let zoomTimeout = null;

    container.addEventListener("wheel", (event) => {
        event.preventDefault();

        const delta   = event.deltaY || 0;
        const options = timeScale.getOptions();
        const current = options.barSpacing || 8;

        const zoomFactor = Math.exp(-delta * 0.0015);
        let next = current * zoomFactor;

        next = Math.max(0.5, Math.min(40, next));
        timeScale.applyOptions({ barSpacing: next });

        if (zoomTimeout) clearTimeout(zoomTimeout);
        zoomTimeout = setTimeout(() => {
            if (autoScrollToRealTime) {
                timeScale.scrollToRealTime();
            }
        }, 350);
    }, { passive: false });

    // Scroll drag мышью
    let isDragging = false;
    let lastX = 0;

    container.addEventListener("mousedown", (e) => {
        isDragging = true;
        lastX = e.clientX;
    });

    container.addEventListener("mousemove", (e) => {
        if (!isDragging) return;

        const dx = e.clientX - lastX;
        lastX = e.clientX;

        const pos = timeScale.scrollPosition();
        // dx > 0 => двигаем в прошлое
        timeScale.scrollToPosition(pos - dx / 5, false);
    });

    container.addEventListener("mouseleave", () => {
        isDragging = false;
    });

    document.addEventListener("mouseup", () => {
        isDragging = false;
    });
}

// =====================================================================
// TOOLTIP
// =====================================================================
function initTooltip() {
    const container = document.getElementById("candles-chart-inner");
    if (!container || !chart) return;

    container.style.position = "relative";

    const tt = document.createElement("div");
    tt.id = "chart-tooltip";
    tt.style.position      = "absolute";
    tt.style.pointerEvents = "none";
    tt.style.display       = "none";
    tt.style.background    = "rgba(0,0,0,0.90)";
    tt.style.border        = "1px solid rgba(255,255,255,0.15)";
    tt.style.borderRadius  = "6px";
    tt.style.padding       = "8px 12px";
    tt.style.color         = "#fff";
    tt.style.fontSize      = "12px";
    tt.style.zIndex        = "99999";

    container.appendChild(tt);

    chart.subscribeCrosshairMove(param => {
        if (!param.point || param.time === undefined || param.time === null) {
            tt.style.display = "none";
            return;
        }

        // time может быть числом или объектом с timestamp
        const timeSec = typeof param.time === "object" && "timestamp" in param.time
            ? param.time.timestamp
            : param.time;

        const tMs = Math.round(timeSec * 1000);

        const c = candlesGlobal.find(x => x.time === tMs);
        if (!c) {
            tt.style.display = "none";
            return;
        }

        const e20 = ema20Global.find(e => e.time === tMs);
        const e50 = ema50Global.find(e => e.time === tMs);

        // сделка в пределах +-1500 ms
        let tr = null;
        for (const t of tradesGlobal) {
            if (Math.abs(t.time - tMs) <= 1500) {
                tr = t;
                break;
            }
        }

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

        if (left + w > rect.width) {
            left = param.point.x - w - 30;
        }
        if (top + h > rect.height) {
            top = param.point.y - h - 20;
        }

        tt.style.left   = left + "px";
        tt.style.top    = top  + "px";
        tt.style.display = "block";
    });
}

// =====================================================================
// LOAD TIMEFRAMES
// =====================================================================
async function loadTimeframes(exchange, network, currentTf) {
    try {
        const r = await fetch(
            `/api/exchange/timeframes?exchange=${encodeURIComponent(exchange)}&networkType=${encodeURIComponent(network)}`
        );
        if (!r.ok) {
            console.error("❌ loadTimeframes HTTP error", r.status);
            return;
        }

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
            const root   = document.getElementById("strategy-dashboard");
            const chatId = Number(root.dataset.chatId || "0");
            const symbol = root.dataset.symbol;
            const tf     = sel.value;

            initialDataLoaded    = false;
            autoScrollToRealTime = true;

            loadFullChart(chatId, symbol, tf, { initial: true });
            subscribeLive(symbol, tf);
        });
    } catch (e) {
        console.error("❌ loadTimeframes error:", e);
    }
}

// =====================================================================
// ВСПОМОГАТЕЛЬНЫЕ
// =====================================================================
function normalizeTimeMs(t) {
    if (t == null) return null;
    // если меньше триллиона — это, скорее всего, секунды
    return t < 1e12 ? t * 1000 : t;
}

function formatPrice(p) {
    return typeof p === "number" ? p.toFixed(2) : String(p);
}

// =====================================================================
// LOAD FULL CHART
// =====================================================================
async function loadFullChart(chatId, symbol, timeframe, opts = {}) {
    const initial = !!opts.initial;
    try {
        const url =
            `/api/chart/full?chatId=${encodeURIComponent(chatId)}` +
            `&symbol=${encodeURIComponent(symbol)}` +
            `&timeframe=${encodeURIComponent(timeframe)}` +
            `&limit=300`;

        const r = await fetch(url);
        if (!r.ok) {
            console.error("❌ loadFullChart HTTP error:", r.status);
            return;
        }

        const d = await r.json();

        // -------- Candles --------
        candlesGlobal = (d.candles || []).map(c => {
            const tMs = normalizeTimeMs(c.time);
            return {
                time:  tMs,
                open:  c.open,
                high:  c.high,
                low:   c.low,
                close: c.close
            };
        });

        if (candlesGlobal.length > MAX_CANDLES_HISTORY) {
            candlesGlobal = candlesGlobal.slice(-MAX_CANDLES_HISTORY);
        }

        candleSeries.setData(
            candlesGlobal.map(c => ({
                time:  c.time / 1000,
                open:  c.open,
                high:  c.high,
                low:   c.low,
                close: c.close
            }))
        );

        // -------- EMA --------
        ema20Global = (d.ema20 || []).map(p => ({
            time:  normalizeTimeMs(p.time),
            value: p.value
        }));
        ema50Global = (d.ema50 || []).map(p => ({
            time:  normalizeTimeMs(p.time),
            value: p.value
        }));

        ema20Series.setData(ema20Global.map(p => ({ time: p.time / 1000, value: p.value })));
        ema50Series.setData(ema50Global.map(p => ({ time: p.time / 1000, value: p.value })));

        // -------- Bollinger --------
        const bb = d.bollinger || {};
        bbUpperGlobal  = (bb.upper  || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));
        bbLowerGlobal  = (bb.lower  || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));
        bbMiddleGlobal = (bb.middle || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));

        bbUpperSeries .setData(bbUpperGlobal .map(p => ({ time: p.time / 1000, value: p.value })));
        bbLowerSeries .setData(bbLowerGlobal .map(p => ({ time: p.time / 1000, value: p.value })));
        bbMiddleSeries.setData(bbMiddleGlobal.map(p => ({ time: p.time / 1000, value: p.value })));

        // -------- Trades --------
        tradesGlobal = (d.trades || []).map(t => ({
            time:  normalizeTimeMs(t.time),
            price: t.price,
            side:  t.side
        }));

        if (tradesGlobal.length > MAX_TRADES_HISTORY) {
            tradesGlobal = tradesGlobal.slice(-MAX_TRADES_HISTORY);
        }

        updateTradeMarkers();
        updatePriceLine();
        updateIndicatorPriceLines();
        updateFrontStats();
        renderIndicatorLabels(); // сразу обновить подписи

        const ts = chart.timeScale();

        if (!initialDataLoaded) {
            // вместо fitContent — показываем только хвост истории
            if (candlesGlobal.length) {
                const bars = candlesGlobal.length;
                const visibleBars = Math.min(80, bars); // сколько баров влезет из хвоста
                const fromIndex = Math.max(0, bars - visibleBars);
                const from = candlesGlobal[fromIndex].time / 1000;
                const to   = candlesGlobal[bars - 1].time / 1000;
                ts.setVisibleRange({ from, to });
            }
            ts.scrollToRealTime();
            initialDataLoaded = true;
        } else if (autoScrollToRealTime) {
            ts.scrollToRealTime();
        }
    } catch (e) {
        console.error("❌ loadFullChart error:", e);
    }
}

// =====================================================================
// UPDATE STATS (card сверху)
// =====================================================================
function updateFrontStats() {
    if (!candlesGlobal.length) return;

    const first = candlesGlobal[0];
    const last  = candlesGlobal[candlesGlobal.length - 1];

    const elLast = document.getElementById("stat-last-price");
    if (elLast) {
        elLast.textContent =
            typeof last.close === "number" ? last.close.toFixed(2) : String(last.close);
    }

    const elChange = document.getElementById("stat-change-pct");
    if (elChange && typeof first.close === "number" && first.close !== 0) {
        const pct = ((last.close - first.close) / first.close) * 100;
        elChange.textContent = pct.toFixed(2) + "%";
        elChange.style.color = pct >= 0 ? "#2ecc71" : "#e74c3c";
    }

    const elRange = document.getElementById("stat-range");
    if (elRange) {
        const lows  = candlesGlobal.map(c => c.low);
        const highs = candlesGlobal.map(c => c.high);
        elRange.textContent =
            `${Math.min(...lows).toFixed(2)} – ${Math.max(...highs).toFixed(2)}`;
    }
}

// =====================================================================
// TRADE MARKERS
// =====================================================================
function updateTradeMarkers() {
    candleSeries.setMarkers(
        tradesGlobal.map(t => ({
            time:     t.time / 1000,
            position: t.side === "BUY" ? "belowBar" : "aboveBar",
            color:    t.side === "BUY" ? "#26a69a" : "#ef5350",
            shape:    t.side === "BUY" ? "arrowUp"  : "arrowDown",
            text:     `${t.side} @ ${t.price}`
        }))
    );
}

// =====================================================================
// PRICE LINE (цена свечи)
// =====================================================================
function updatePriceLine() {
    const last = candlesGlobal[candlesGlobal.length - 1];
    if (!last || typeof last.close !== "number") return;

    if (lastPriceLine) {
        try {
            candleSeries.removePriceLine(lastPriceLine);
        } catch (e) {
            // уже удалён — ок
        }
    }

    lastPriceLine = candleSeries.createPriceLine({
        price:     last.close,
        lineWidth: 2,
        color:     "rgba(0,255,150,0.9)",
        title:     `PRICE ${last.close.toFixed(2)}`
    });
}

// =====================================================================
// Встроенные priceLine для индикаторов (для совместимости, если надо)
// =====================================================================
function updateIndicatorPriceLines() {
    // EMA20
    if (ema20PriceLine) {
        try { ema20Series.removePriceLine(ema20PriceLine); } catch (e) {}
        ema20PriceLine = null;
    }
    if (ema20Global.length) {
        const last = ema20Global[ema20Global.length - 1];
        if (last && typeof last.value === "number") {
            ema20PriceLine = ema20Series.createPriceLine({
                price: last.value,
                color: "#42a5f5",
                lineWidth: 1,
                title: `EMA20 ${formatPrice(last.value)}`
            });
        }
    }

    // EMA50
    if (ema50PriceLine) {
        try { ema50Series.removePriceLine(ema50PriceLine); } catch (e) {}
        ema50PriceLine = null;
    }
    if (ema50Global.length) {
        const last = ema50Global[ema50Global.length - 1];
        if (last && typeof last.value === "number") {
            ema50PriceLine = ema50Series.createPriceLine({
                price: last.value,
                color: "#ab47bc",
                lineWidth: 1,
                title: `EMA50 ${formatPrice(last.value)}`
            });
        }
    }

    // BB Upper
    if (bbUpperPriceLine) {
        try { bbUpperSeries.removePriceLine(bbUpperPriceLine); } catch (e) {}
        bbUpperPriceLine = null;
    }
    if (bbUpperGlobal.length) {
        const last = bbUpperGlobal[bbUpperGlobal.length - 1];
        if (last && typeof last.value === "number") {
            bbUpperPriceLine = bbUpperSeries.createPriceLine({
                price: last.value,
                color: "rgba(255,215,0,0.9)",
                lineWidth: 1,
                title: `BB↑ ${formatPrice(last.value)}`
            });
        }
    }

    // BB Lower
    if (bbLowerPriceLine) {
        try { bbLowerSeries.removePriceLine(bbLowerPriceLine); } catch (e) {}
        bbLowerPriceLine = null;
    }
    if (bbLowerGlobal.length) {
        const last = bbLowerGlobal[bbLowerGlobal.length - 1];
        if (last && typeof last.value === "number") {
            bbLowerPriceLine = bbLowerSeries.createPriceLine({
                price: last.value,
                color: "rgba(255,215,0,0.9)",
                lineWidth: 1,
                title: `BB↓ ${formatPrice(last.value)}`
            });
        }
    }

    // BB Middle
    if (bbMiddlePriceLine) {
        try { bbMiddleSeries.removePriceLine(bbMiddlePriceLine); } catch (e) {}
        bbMiddlePriceLine = null;
    }
    if (bbMiddleGlobal.length) {
        const last = bbMiddleGlobal[bbMiddleGlobal.length - 1];
        if (last && typeof last.value === "number") {
            bbMiddlePriceLine = bbMiddleSeries.createPriceLine({
                price: last.value,
                color: "rgba(255,255,255,0.7)",
                lineWidth: 1,
                title: `BB mid ${formatPrice(last.value)}`
            });
        }
    }
}

// =====================================================================
// КАСТОМНЫЕ НАДПИСИ СПРАВА (ОТДЕЛЬНАЯ КОЛОНКА, НЕ НА СВЕЧАХ)
// =====================================================================
function renderIndicatorLabels() {
    if (!chart) return;

    const labelsPane = document.getElementById("price-labels-pane");
    if (!labelsPane) return;

    // удаляем старые подписи
    labelsPane.innerHTML = "";

    const priceScale = chart.priceScale("right");
    const priceToY = p => priceScale.priceToCoordinate(p);

    function addLabel(text, color, y) {
        if (y == null) return;
        const div = document.createElement("div");
        div.className = "indicator-label";
        div.style.position = "absolute";
        div.style.left = "4px";
        div.style.right = "4px";
        div.style.top = (y - 8) + "px";
        div.style.padding = "2px 4px";
        div.style.fontSize = "10px";
        div.style.color = color;
        div.style.background = "rgba(0,0,0,0.8)";
        div.style.border = "1px solid rgba(255,255,255,0.15)";
        div.style.borderRadius = "4px";
        div.style.pointerEvents = "none";
        div.style.zIndex = "9999";
        div.innerText = text;
        labelsPane.appendChild(div);
    }

    // === PRICE ===
    if (candlesGlobal.length) {
        const last = candlesGlobal[candlesGlobal.length - 1];
        if (typeof last.close === "number") {
            const y = priceToY(last.close);
            addLabel(`PRICE ${last.close.toFixed(2)}`, "#00ff99", y);
        }
    }

    // === EMA20 ===
    if (ema20Global.length) {
        const last = ema20Global[ema20Global.length - 1];
        if (last && typeof last.value === "number") {
            const y = priceToY(last.value);
            addLabel(`EMA20 ${last.value.toFixed(2)}`, "#42a5f5", y);
        }
    }

    // === EMA50 ===
    if (ema50Global.length) {
        const last = ema50Global[ema50Global.length - 1];
        if (last && typeof last.value === "number") {
            const y = priceToY(last.value);
            addLabel(`EMA50 ${last.value.toFixed(2)}`, "#ab47bc", y);
        }
    }

    // === BB UPPER ===
    if (bbUpperGlobal.length) {
        const last = bbUpperGlobal[bbUpperGlobal.length - 1];
        if (last && typeof last.value === "number") {
            const y = priceToY(last.value);
            addLabel(`BB↑ ${last.value.toFixed(2)}`, "gold", y);
        }
    }

    // === BB LOWER ===
    if (bbLowerGlobal.length) {
        const last = bbLowerGlobal[bbLowerGlobal.length - 1];
        if (last && typeof last.value === "number") {
            const y = priceToY(last.value);
            addLabel(`BB↓ ${last.value.toFixed(2)}`, "gold", y);
        }
    }

    // === BB MIDDLE ===
    if (bbMiddleGlobal.length) {
        const last = bbMiddleGlobal[bbMiddleGlobal.length - 1];
        if (last && typeof last.value === "number") {
            const y = priceToY(last.value);
            addLabel(`BB MID ${last.value.toFixed(2)}`, "#cccccc", y);
        }
    }
}

// обновляем подписи регулярно (и при зуме/скролле всё будет переставляться)
setInterval(renderIndicatorLabels, 200);

// =====================================================================
// LIVE WEBSOCKET
// =====================================================================
function subscribeLive(symbol, timeframe) {
    console.log("[WS] subscribeLive init", { symbol, timeframe });

    // закрываем старый сокет
    if (currentWs) {
        console.log("[WS] closing previous websocket");
        try {
            currentWs.close(1000, "switch symbol/timeframe");
        } catch (e) {
            console.warn("[WS] error closing previous ws", e);
        }
        currentWs = null;
    }

    const loc      = window.location;
    const protocol = loc.protocol === "https:" ? "wss" : "ws";
    const wsUrl    =
        `${protocol}://${loc.host}/ws/candles` +
        `?symbol=${encodeURIComponent(symbol)}` +
        `&timeframe=${encodeURIComponent(timeframe)}`;

    console.log("[WS] connecting to", wsUrl);

    const ws = new WebSocket(wsUrl);
    currentWs = ws;

    ws.onopen = () => {
        console.log("[WS] OPEN", wsUrl);
        setLiveStatus(true);
    };

    ws.onerror = (err) => {
        console.error("[WS] ERROR", err);
        setLiveStatusError("Ошибка WebSocket");
    };

    ws.onclose = (evt) => {
        console.log("[WS] CLOSE", { code: evt.code, reason: evt.reason });
        setLiveStatus(false);
    };

    ws.onmessage = (event) => {
        let payload;
        try {
            payload = JSON.parse(event.data);
        } catch (e) {
            console.error("[WS] parse error", e);
            return;
        }

        if (!payload || payload.type !== "tick" || !payload.candle) {
            return;
        }

        const c = payload.candle;
        let timeMs = normalizeTimeMs(c.time);
        if (timeMs == null) return;

        const candleForState = {
            time:  timeMs,
            open:  c.open,
            high:  c.high,
            low:   c.low,
            close: c.close
        };

        const candleForChart = {
            time:  timeMs / 1000,
            open:  c.open,
            high:  c.high,
            low:   c.low,
            close: c.close
        };

        // обновляем график
        candleSeries.update(candleForChart);

        // обновляем историю свечей
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
                console.warn("[WS] received out-of-order tick", { last, candle: candleForState });
            }
        }

        if (candlesGlobal.length > MAX_CANDLES_HISTORY) {
            candlesGlobal = candlesGlobal.slice(-MAX_CANDLES_HISTORY);
        }

        updatePriceLabel(candleForState.close);
        updatePriceLine();
        updateFrontStats();
        renderIndicatorLabels();

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
// PRICE LABEL (top-right карточка)
// =====================================================================
function updatePriceLabel(price) {
    const el = document.getElementById("stat-last-price");
    if (!el) return;

    if (typeof price === "number") {
        el.textContent = price.toFixed(2);
    } else {
        el.textContent = String(price);
    }
}

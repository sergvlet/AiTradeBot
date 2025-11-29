"use strict";

console.log("📈 strategy-chart.js loaded (dashboard modular)");

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

let lastPriceLine   = null;
let strategyRunning = false;

// ограничения
const MAX_CANDLES_HISTORY = 600;
const MAX_TRADES_HISTORY  = 400;

// отступ справа ~3 см (в пикселях)
const RIGHT_OFFSET_PX   = 110;
const BASE_BAR_SPACING  = 8;
let   lastBarSpacing    = BASE_BAR_SPACING;

// внешний хук — оставить
window.setStrategyRunning = f => (strategyRunning = !!f);

// =============================================================
// ХЕЛПЕРЫ
// =============================================================
function getCurrentTf() {
    const s = document.getElementById("timeframe-select");
    return s ? s.value || "1m" : "1m";
}

function normalizeTimeMs(t) {
    if (t == null) return null;
    // если пришло в секундах — переводим в миллисекунды
    return t < 1e12 ? t * 1000 : t;
}

function formatPrice(p, digits = 2) {
    return typeof p === "number" ? p.toFixed(digits) : String(p);
}

// кастомный форматтер оси Y
function customPriceFormatter(price) {
    const lastCandle = candlesGlobal.length ? candlesGlobal[candlesGlobal.length - 1] : null;
    const lastEma20  = ema20Global.length   ? ema20Global[ema20Global.length - 1]   : null;
    const lastEma50  = ema50Global.length   ? ema50Global[ema50Global.length - 1]   : null;
    const lastBBU    = bbUpperGlobal.length ? bbUpperGlobal[bbUpperGlobal.length - 1] : null;
    const lastBBL    = bbLowerGlobal.length ? bbLowerGlobal[bbLowerGlobal.length - 1] : null;
    const lastBBM    = bbMiddleGlobal.length? bbMiddleGlobal[bbMiddleGlobal.length - 1]: null;

    const basePrice = lastCandle && typeof lastCandle.close === "number"
        ? lastCandle.close
        : (typeof price === "number" ? price : Number(price) || 0);

    const pStr   = formatPrice(basePrice, 2);
    const ema20  = lastEma20 ? formatPrice(lastEma20.value, 2) : "-";
    const ema50  = lastEma50 ? formatPrice(lastEma50.value, 2) : "-";
    const bbu    = lastBBU   ? formatPrice(lastBBU.value, 2)   : "-";
    const bbl    = lastBBL   ? formatPrice(lastBBL.value, 2)   : "-";
    const bbm    = lastBBM   ? formatPrice(lastBBM.value, 2)   : "-";

    return (
        `PRICE ${pStr}\n` +
        `EMA20 ${ema20}\n` +
        `EMA50 ${ema50}\n` +
        `BB↑ ${bbu}\n` +
        `BB↓ ${bbl}\n` +
        `BB mid ${bbm}`
    );
}

// =============================================================
// АДАПТИВНЫЙ ОТСТУП СПРАВА
// =============================================================
function applyRightOffset() {
    if (!chart) return;

    const ts = chart.timeScale();
    const bs = lastBarSpacing || BASE_BAR_SPACING;
    const rightOffsetBars = RIGHT_OFFSET_PX / bs;

    ts.applyOptions({ rightOffset: rightOffsetBars });
}

// =============================================================
// INIT CHART
// =============================================================
function initChart() {
    const el = document.getElementById("candles-chart");
    if (!el) {
        console.error("❗ candles-chart element missing");
        return;
    }

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
            timeVisible:    true,
            secondsVisible: true,
            borderColor:    "rgba(255,255,255,0.2)",
            barSpacing:     BASE_BAR_SPACING,
            minBarSpacing:  0.5
        },
        rightPriceScale: {
            borderColor: "rgba(255,255,255,0.2)",
            priceFormatter: customPriceFormatter
        }
    });

    lastBarSpacing = BASE_BAR_SPACING;

    candleSeries = chart.addCandlestickSeries({
        upColor:         "#2ecc71",
        downColor:       "#e74c3c",
        borderUpColor:   "#2ecc71",
        borderDownColor: "#e74c3c",
        wickUpColor:     "#2ecc71",
        wickDownColor:   "#e74c3c",
        priceLineVisible: false
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

    chart.timeScale().subscribeVisibleTimeRangeChange(() => {
        const sc = chart.timeScale().scrollPosition();
        autoScrollToRealTime = sc < 0.5;
    });

    setupSmoothZoomAndScroll(el);
    initTooltip();
    applyRightOffset();

    window.addEventListener("resize", () => {
        if (!chart) return;
        chart.applyOptions({ width: el.clientWidth });
        applyRightOffset();
    });
}

// плавный zoom/scroll
function setupSmoothZoomAndScroll(container) {
    if (!chart) return;
    const timeScale = chart.timeScale();

    timeScale.applyOptions({
        barSpacing:    lastBarSpacing,
        minBarSpacing: 0.5
    });

    let zoomTimeout = null;

    container.addEventListener("wheel", (event) => {
        event.preventDefault();

        const delta  = event.deltaY || 0;
        const current = lastBarSpacing || BASE_BAR_SPACING;

        const zoomFactor = Math.exp(-delta * 0.0015);
        let next = current * zoomFactor;

        next = Math.max(0.5, Math.min(40, next));

        lastBarSpacing = next;
        timeScale.applyOptions({ barSpacing: next });
        applyRightOffset();

        if (zoomTimeout) clearTimeout(zoomTimeout);
        zoomTimeout = setTimeout(() => {
            if (autoScrollToRealTime) {
                timeScale.scrollToRealTime();
            }
        }, 350);
    }, { passive: false });

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
        timeScale.scrollToPosition(pos - dx / 5, false);
    });

    container.addEventListener("mouseleave", () => {
        isDragging = false;
    });

    document.addEventListener("mouseup", () => {
        isDragging = false;
    });
}

// =============================================================
// TOOLTIP
// =============================================================
function initTooltip() {
    const container = document.getElementById("candles-chart");
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

// =============================================================
// LOAD TIMEFRAMES
// =============================================================
async function loadTimeframes(exchange, network, currentTf, chatId, symbol) {
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

        // не плодим миллионы обработчиков
        sel.onchange = () => {
            const tf = sel.value;

            initialDataLoaded    = false;
            autoScrollToRealTime = true;
            lastBarSpacing       = BASE_BAR_SPACING;

            window.AiStrategyChart.loadFullChart(chatId, symbol, tf, { initial: true });
            window.AiStrategyChart.subscribeLive(symbol, tf);
        };
    } catch (e) {
        console.error("❌ loadTimeframes error:", e);
    }
}

// =============================================================
// LOAD FULL CHART
// =============================================================
async function loadFullChart(chatId, symbol, timeframe, opts = {}) {
    const initial = !!opts.initial;

    if (!chart) {
        initChart();
        if (!chart) {
            console.error("❌ Chart not initialized");
            return;
        }
    }

    // Берём тип стратегии из data-атрибута корневого блока
    const root = document.getElementById("strategy-dashboard");
    const type = root?.dataset?.type || "SMART_FUSION";

    try {
        const url =
            `/api/chart/strategy` +
            `?chatId=${encodeURIComponent(chatId)}` +
            `&type=${encodeURIComponent(type)}` +
            `&symbol=${encodeURIComponent(symbol)}` +
            `&timeframe=${encodeURIComponent(timeframe)}` +
            `&limit=300`;

        const r = await fetch(url);
        if (!r.ok) {
            console.error("❌ loadFullChart HTTP error:", r.status);
            return;
        }

        const d = await r.json();

        // Candles
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

        // EMA
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

        // Bollinger
        const bb = d.bollinger || {};
        bbUpperGlobal  = (bb.upper  || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));
        bbLowerGlobal  = (bb.lower  || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));
        bbMiddleGlobal = (bb.middle || []).map(p => ({ time: normalizeTimeMs(p.time), value: p.value }));

        bbUpperSeries .setData(bbUpperGlobal .map(p => ({ time: p.time / 1000, value: p.value })));
        bbLowerSeries .setData(bbLowerGlobal .map(p => ({ time: p.time / 1000, value: p.value })));
        bbMiddleSeries.setData(bbMiddleGlobal.map(p => ({ time: p.time / 1000, value: p.value })));

        // Trades
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
        updateFrontStats();

        applyRightOffset();

        if (candlesGlobal.length) {
            const lastIdx    = candlesGlobal.length - 1;
            const windowSize = Math.min(120, Math.max(80, candlesGlobal.length));
            const fromIdx    = Math.max(0, lastIdx - windowSize + 1);

            const from = candlesGlobal[fromIdx].time / 1000;
            const to   = candlesGlobal[lastIdx].time / 1000;

            chart.timeScale().setVisibleRange({ from, to });
        }

        if (!initialDataLoaded) {
            chart.timeScale().scrollToRealTime();
            initialDataLoaded = true;
        } else if (autoScrollToRealTime) {
            chart.timeScale().scrollToRealTime();
        }

    } catch (e) {
        console.error("❌ loadFullChart error:", e);
    }
}

// =============================================================
// STATS
// =============================================================
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
    const elTrend  = document.getElementById("stat-trend");

    if (elChange && typeof first.close === "number" && first.close !== 0) {
        const pct = ((last.close - first.close) / first.close) * 100;
        elChange.textContent = pct.toFixed(2) + "%";
        elChange.style.color = pct >= 0 ? "#2ecc71" : "#e74c3c";

        if (elTrend) {
            if (pct > 0.4) {
                elTrend.textContent = "Восходящий";
                elTrend.style.color = "#2ecc71";
            } else if (pct < -0.4) {
                elTrend.textContent = "Нисходящий";
                elTrend.style.color = "#e74c3c";
            } else {
                elTrend.textContent = "Боковой";
                elTrend.style.color = "#f1c40f";
            }
        }
    }

    const elRange = document.getElementById("stat-range");
    if (elRange) {
        const lows  = candlesGlobal.map(c => c.low);
        const highs = candlesGlobal.map(c => c.high);
        elRange.textContent =
            `${Math.min(...lows).toFixed(2)} – ${Math.max(...highs).toFixed(2)}`;
    }
}

// =============================================================
// TRADE MARKERS
// =============================================================
function updateTradeMarkers() {
    if (!candleSeries) return;
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

// =============================================================
// PRICE LINE
// =============================================================
function updatePriceLine() {
    const last = candlesGlobal[candlesGlobal.length - 1];
    if (!last || typeof last.close !== "number") return;

    if (lastPriceLine) {
        try {
            candleSeries.removePriceLine(lastPriceLine);
        } catch (e) {
            // ignore
        }
    }

    lastPriceLine = candleSeries.createPriceLine({
        price:     last.close,
        lineWidth: 1,
        color:     "rgba(0,255,150,0.7)",
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: false,
        title: ""
    });
}

// =============================================================
// LIVE WEBSOCKET
// =============================================================
function subscribeLive(symbol, timeframe) {
    console.log("[WS] subscribeLive init", { symbol, timeframe });

    if (currentWs) {
        try { currentWs.close(1000, "switch"); } catch {}
        currentWs = null;
    }

    const loc      = window.location;
    const protocol = loc.protocol === "https:" ? "wss" : "ws";
    const wsUrl    =
        `${protocol}://${loc.host}/ws/candles` +
        `?symbol=${encodeURIComponent(symbol)}` +
        `&timeframe=${encodeURIComponent(timeframe)}`;

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
        console.log("[WS] CLOSE", evt.code, evt.reason);
        setLiveStatus(false);
    };

    // ============================================================
    //   🔥 Обработка всех форматов Binance + закрытия свечей
    // ============================================================
    ws.onmessage = (event) => {
        let payload;
        try { payload = JSON.parse(event.data); }
        catch { return; }

        // ---- Универсальная нормализация формата WS ----
        let k = null;

        // Binance format combined stream
        if (payload && payload.data && payload.data.k) {
            k = payload.data.k;
        }
// Single kline update
        else if (payload && payload.k) {
            k = payload.k;
        }
// Old format (если останется)
        else if (payload.type === "tick" && payload.candle) {
            k = payload.candle;
        }

        else if (payload.data && payload.data.k) {
            k = payload.data.k;
        }
        else if (payload.k) {
            k = payload.k;
        }

        if (!k) return;

        // ---- Важное поле "закрыта свеча" ----
        const isClosed = k.x === true;

        const timeMs = normalizeTimeMs(k.t);
        const open   = parseFloat(k.o);
        const high   = parseFloat(k.h);
        const low    = parseFloat(k.l);
        const close  = parseFloat(k.c);

        const stateCandle = { time: timeMs, open, high, low, close, closed: isClosed };
        const chartCandle = { time: timeMs / 1000, open, high, low, close };

        candleSeries.update(chartCandle);

        // ============================================================
        //             🔥 Правильная логика закрытия свечей
        // ============================================================
        if (candlesGlobal.length === 0) {
            candlesGlobal.push(stateCandle);
        } else {
            const last = candlesGlobal[candlesGlobal.length - 1];

            // обновляем текущую свечу
            if (last.time === stateCandle.time) {
                candlesGlobal[candlesGlobal.length - 1] = stateCandle;

                if (isClosed) {
                    console.log("🟩 Свеча ЗАКРЫТА:", new Date(timeMs).toLocaleString());
                    // На следующем тике Binance пришлёт новую t — сработает следующий блок
                }
            }
            // новая свеча
            else if (stateCandle.time > last.time) {
                candlesGlobal.push(stateCandle);
                console.log("🆕 Новая свеча", new Date(timeMs).toLocaleString());
            }
        }

        // Ограничение памяти
        if (candlesGlobal.length > MAX_CANDLES_HISTORY) {
            candlesGlobal = candlesGlobal.slice(-MAX_CANDLES_HISTORY);
        }

        updatePriceLabel(close);
        updatePriceLine();
        updateFrontStats();

        if (autoScrollToRealTime) {
            chart.timeScale().scrollToRealTime();
        }
    };
}

// =============================================================
// WS STATUS
// =============================================================
function setLiveStatus(isOk) {
    const el = document.getElementById("live-status");
    if (!el) return;

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
    if (!el) return;
    el.textContent = msg || "ERROR";
    el.style.color = "#f1c40f";
}

// =============================================================
// LABEL В КАРТОЧКЕ
// =============================================================
function updatePriceLabel(price) {
    const el = document.getElementById("stat-last-price");
    if (!el) return;

    if (typeof price === "number") {
        el.textContent = price.toFixed(2);
    } else {
        el.textContent = String(price);
    }
}

// =============================================================
// EXPORT PNG
// =============================================================
function initExportPng() {
    const btn = document.getElementById("btn-export-png");
    if (!btn) return;

    btn.addEventListener("click", async () => {
        if (!window.html2canvas) {
            console.warn("html2canvas not loaded");
            return;
        }
        const el = document.querySelector(".chart-wrapper-main");
        if (!el) return;

        try {
            const canvas = await window.html2canvas(el);
            const link = document.createElement("a");
            link.href = canvas.toDataURL("image/png");
            link.download = "strategy-chart.png";
            link.click();
        } catch (e) {
            console.error("Export PNG error", e);
        }
    });
}

// =============================================================
// START/STOP BUTTONS
// =============================================================
function initStartStopButtons() {
    const root = document.getElementById("strategy-dashboard");
    if (!root) return;

    const chatId = Number(root.dataset.chatId || "0");
    const type   = root.dataset.type;

    const btnStart = document.getElementById("btn-start");
    const btnStop  = document.getElementById("btn-stop");

    if (btnStart) {
        btnStart.addEventListener("click", async () => {
            try {
                await fetch(`/api/strategy/start?chatId=${chatId}&type=${encodeURIComponent(type)}`, {
                    method: "POST"
                });
            } catch (e) {
                console.error("start strategy error", e);
            }
        });
    }

    if (btnStop) {
        btnStop.addEventListener("click", async () => {
            try {
                await fetch(`/api/strategy/stop?chatId=${chatId}&type=${encodeURIComponent(type)}`, {
                    method: "POST"
                });
            } catch (e) {
                console.error("stop strategy error", e);
            }
        });
    }
}

// =============================================================
// ПУБЛИЧНЫЙ API ДЛЯ ДРУГИХ ФАЙЛОВ
// =============================================================
window.AiStrategyChart = {
    initChart,
    loadFullChart,
    subscribeLive,
    loadTimeframes,
    getCurrentTf,
    initExportPng,
    initStartStopButtons
};

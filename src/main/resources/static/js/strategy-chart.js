(function () {
    console.log("📈 strategy-chart.js loaded (ULTRA FIX v3, REAL-TIME)");

    const toastAvailable = typeof window.showToast === "function";
    function notify(msg, type = "success") {
        if (toastAvailable) window.showToast(msg, type);
        else console.log(`[${type}] ${msg}`);
    }

    document.addEventListener("DOMContentLoaded", () => {
        const root = document.getElementById("strategy-dashboard");
        if (!root) {
            console.warn("strategy-dashboard root not found");
            return;
        }

        const chatId = Number(root.dataset.chatId || 0);
        const type = root.dataset.type || "SMART_FUSION";
        const symbol = root.dataset.symbol || "BTCUSDT";
        const exchange = root.dataset.exchange || "BINANCE";
        const network = root.dataset.network || "MAINNET";
        let currentTimeframe = root.dataset.timeframe || "15m";

        const chartApiUrl = "/api/chart/history";
        const timeframesApiUrl = "/api/exchange/timeframes";

        const chartContainer = document.getElementById("candles-chart");
        const tfSelect = document.getElementById("timeframe-select");
        const btnRefresh = document.getElementById("btn-refresh-chart");
        const btnExport = document.getElementById("btn-export-png");
        const btnStart = document.getElementById("btn-start");
        const btnStop = document.getElementById("btn-stop");

        // Метрики сверху
        const elLastPrice = document.getElementById("stat-last-price");
        const elChangePct = document.getElementById("stat-change-pct");
        const elRange = document.getElementById("stat-range");
        const elTrend = document.getElementById("stat-trend");

        let chart,
            candleSeries,
            emaFastSeries,
            emaSlowSeries,
            rsiSeries,
            atrSeries,
            resizeObserver,
            tooltip;

        window.__chartTrades = [];
        const indicatorByTime = new Map();

        // ====================================================
        //  ГРАФИК
        // ====================================================
        function initChart() {
            if (!chartContainer || !window.LightweightCharts) return;

            chart = LightweightCharts.createChart(chartContainer, {
                width: chartContainer.clientWidth,
                height: 420,
                layout: {
                    background: { type: "solid", color: "#050814" },
                    textColor: "#dce6ff",
                },
                grid: {
                    vertLines: { color: "rgba(255,255,255,0.04)" },
                    horzLines: { color: "rgba(255,255,255,0.04)" },
                },
                rightPriceScale: { borderColor: "rgba(197,203,206,0.4)" },
                leftPriceScale: { visible: true, borderColor: "rgba(197,203,206,0.4)" },
                timeScale: {
                    borderColor: "rgba(197,203,206,0.4)",
                    timeVisible: true,
                },
            });

            candleSeries = chart.addCandlestickSeries({
                upColor: "#2ecc71",
                downColor: "#e74c3c",
                borderUpColor: "#2ecc71",
                borderDownColor: "#e74c3c",
                wickUpColor: "#2ecc71",
                wickDownColor: "#e74c3c",
            });

            emaFastSeries = chart.addLineSeries({
                color: "#00bcd4",
                lineWidth: 2,
                priceLineVisible: false,
            });

            emaSlowSeries = chart.addLineSeries({
                color: "#ff9800",
                lineWidth: 2,
                priceLineVisible: false,
            });

            rsiSeries = chart.addLineSeries({
                priceScaleId: "rsi",
                color: "#9c27b0",
                lineWidth: 1,
                priceLineVisible: false,
            });

            atrSeries = chart.addLineSeries({
                priceScaleId: "rsi",
                color: "#cddc39",
                lineWidth: 1,
                priceLineVisible: false,
            });

            resizeObserver = new ResizeObserver((entries) => {
                for (const entry of entries) {
                    chart.applyOptions({ width: entry.contentRect.width });
                }
            });
            resizeObserver.observe(chartContainer);

            initTooltip();
        }

        // ====================================================
        //  ТУЛТИП
        // ====================================================
        function initTooltip() {
            tooltip = document.createElement("div");
            tooltip.className = "chart-tooltip";
            tooltip.style.position = "absolute";
            tooltip.style.zIndex = "1000";
            tooltip.style.pointerEvents = "none";
            tooltip.style.background = "rgba(5,8,20,0.95)";
            tooltip.style.borderRadius = "8px";
            tooltip.style.padding = "8px 10px";
            tooltip.style.fontSize = "11px";
            tooltip.style.color = "#dce6ff";
            tooltip.style.border = "1px solid rgba(120,140,255,0.7)";
            tooltip.style.display = "none";
            tooltip.style.whiteSpace = "nowrap";
            chartContainer.style.position = "relative";
            chartContainer.appendChild(tooltip);

            chart.subscribeCrosshairMove((param) => {
                if (!param?.time || !param?.point) {
                    tooltip.style.display = "none";
                    return;
                }

                const price = param.seriesData.get
                    ? param.seriesData.get(candleSeries)
                    : param.seriesData[candleSeries];

                if (!price) return (tooltip.style.display = "none");

                const tsSec = typeof param.time === "number" ? param.time : param.time;
                const tsMs = tsSec * 1000;
                const d = new Date(tsMs);

                const dateStr = `${String(d.getDate()).padStart(2, "0")}.${String(
                    d.getMonth() + 1
                ).padStart(2, "0")}.${d.getFullYear()} ${String(
                    d.getHours()
                ).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

                const ind = indicatorByTime.get(tsSec) || {};
                const trades = window.__chartTrades.filter(
                    (t) => Math.abs(t.time - tsSec) <= 60
                );

                let html = `
                    <div><b>${symbol}</b> (${currentTimeframe})</div>
                    <div class="text-muted">${dateStr}</div>
                    <hr style="margin:4px 0;border-color:rgba(255,255,255,0.1)">
                    <div>O: <b>${price.open}</b> H: <b>${price.high}</b></div>
                    <div>L: <b>${price.low}</b> C: <b>${price.close}</b></div>
                `;

                if (ind.rsi || ind.atr) {
                    html += `<div style="margin-top:4px;">`;
                    if (ind.rsi) html += `RSI: <b>${ind.rsi.toFixed(1)}</b> `;
                    if (ind.atr)
                        html += `ATR: <b>${ind.atr.toFixed(2)}</b>`;
                    html += `</div>`;
                }

                trades.forEach((t, i) => {
                    html += `<hr style="margin:4px 0;border-color:rgba(255,255,255,0.1)">`;
                    html += `<div><span style="color:#ffcd4c;">Сделка</span> ${t.side} ${t.qty} @${t.price}</div>`;
                    if (t.entryReason) html += `<div>Причина: <b>${t.entryReason}</b></div>`;
                    if (t.exitReason) html += `<div>Выход: <b>${t.exitReason}</b></div>`;
                });

                tooltip.innerHTML = html;
                tooltip.style.display = "block";
                tooltip.style.left = param.point.x + 20 + "px";
                tooltip.style.top = param.point.y + 20 + "px";
            });
        }

        // ====================================================
        //  RSI / ATR
        // ====================================================
        function computeRsi(c, p = 14) {
            const r = [];
            if (c.length <= p) return r;
            let g = 0,
                l = 0;

            for (let i = 1; i <= p; i++) {
                const change = c[i].close - c[i - 1].close;
                change >= 0 ? (g += change) : (l -= change);
            }

            let ag = g / p;
            let al = l / p;

            for (let i = p + 1; i < c.length; i++) {
                const change = c[i].close - c[i - 1].close;
                const gain = Math.max(0, change);
                const loss = Math.max(0, -change);
                ag = (ag * (p - 1) + gain) / p;
                al = (al * (p - 1) + loss) / p;

                const rs = al === 0 ? 100 : ag / al;
                r.push({ time: c[i].time, value: 100 - 100 / (1 + rs) });
            }
            return r;
        }

        function computeAtr(c, p = 14) {
            const r = [];
            if (c.length <= p) return r;

            const trs = [];
            for (let i = 1; i < c.length; i++) {
                const cur = c[i];
                const prev = c[i - 1];
                const tr = Math.max(
                    cur.high - cur.low,
                    Math.abs(cur.high - prev.close),
                    Math.abs(cur.low - prev.close)
                );
                trs.push({ time: cur.time, tr });
            }

            if (trs.length < p) return r;

            let atr = trs.slice(0, p).reduce((a, b) => a + b.tr, 0) / p;
            r.push({ time: trs[p - 1].time, value: atr });

            for (let i = p; i < trs.length; i++) {
                atr = (atr * (p - 1) + trs[i].tr) / p;
                r.push({ time: trs[i].time, value: atr });
            }

            return r;
        }

        function findNearestCandleTime(t, c) {
            if (!c.length) return t;
            let best = c[0].time;
            let bestDiff = Math.abs(best - t);
            for (let i = 1; i < c.length; i++) {
                const diff = Math.abs(c[i].time - t);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = c[i].time;
                }
            }
            return best;
        }

        // ====================================================
        //  ОБНОВЛЕНИЕ МЕТРИК
        // ====================================================
        function updateTopStats(c) {
            if (!c.length) return;

            let min = c[0].low,
                max = c[0].high;
            const first = c[0].close;
            const last = c[c.length - 1].close;

            c.forEach((x) => {
                if (x.low < min) min = x.low;
                if (x.high > max) max = x.high;
            });

            const change = ((last - first) / first) * 100;
            const trend =
                change > 0.2 ? "Восходящий" : change < -0.2 ? "Нисходящий" : "Флэт";

            elLastPrice.textContent = last.toFixed(4);
            elChangePct.textContent = change.toFixed(2) + "%";
            elRange.textContent = `${min.toFixed(4)} – ${max.toFixed(4)}`;
            elTrend.textContent = trend;
        }

        // ====================================================
        //  ЗАГРУЗКА ИСТОРИИ
        // ====================================================
        async function loadChartData(showMsg = true) {
            try {
                if (showMsg) notify("Загружаю данные графика…");

                const url =
                    `${chartApiUrl}?chatId=${chatId}` +
                    `&symbol=${symbol}` +
                    `&timeframe=${currentTimeframe}` +
                    `&limit=250`;

                const resp = await fetch(url);
                const data = await resp.json();

                const candles = (data.candles || []).map((c) => ({
                    time: Math.floor(c.time / 1000),
                    open: c.open,
                    high: c.high,
                    low: c.low,
                    close: c.close,
                }));
                candleSeries.setData(candles);

                updateTopStats(candles);

                // EMA
                emaFastSeries.setData(
                    (data.emaFast || []).map((p) => ({
                        time: Math.floor(p.time / 1000),
                        value: p.value,
                    }))
                );
                emaSlowSeries.setData(
                    (data.emaSlow || []).map((p) => ({
                        time: Math.floor(p.time / 1000),
                        value: p.value,
                    }))
                );

                // RSI/ATR
                indicatorByTime.clear();
                const rsi = computeRsi(candles);
                const atr = computeAtr(candles);

                rsiSeries.setData(rsi);
                atrSeries.setData(atr);

                rsi.forEach((p) => {
                    const obj = indicatorByTime.get(p.time) || {};
                    obj.rsi = p.value;
                    indicatorByTime.set(p.time, obj);
                });

                atr.forEach((p) => {
                    const obj = indicatorByTime.get(p.time) || {};
                    obj.atr = p.value;
                    indicatorByTime.set(p.time, obj);
                });

                // ТРЕЙДЫ
                const trades = (data.trades || []).map((t) => {
                    const sec = Math.floor(t.time / 1000);
                    const mapped = findNearestCandleTime(sec, candles);

                    return {
                        id: t.id,
                        time: mapped,
                        rawTime: t.time,
                        side: t.side,
                        price: t.price,
                        qty: t.qty,
                        status: t.status,
                        tpPrice: t.tpPrice,
                        slPrice: t.slPrice,
                        pnlUsd: t.pnlUsd,
                        pnlPct: t.pnlPct,
                        strategyType: t.strategyType,
                        entryReason: t.entryReason,
                        exitReason: t.exitReason,
                        mlConfidence: t.mlConfidence,
                    };
                });

                window.__chartTrades = trades;

                candleSeries.setMarkers(
                    trades.map((t) => ({
                        time: t.time,
                        position: t.side === "BUY" ? "belowBar" : "aboveBar",
                        color: t.side === "BUY" ? "#2ecc71" : "#e74c3c",
                        shape: t.side === "BUY" ? "arrowUp" : "arrowDown",
                        text: `${t.side} ${t.qty} @${t.price}`,
                    }))
                );

                chart.timeScale().fitContent();
                if (showMsg) notify("График обновлён");
            } catch (e) {
                console.error("Ошибка графика:", e);
                notify("Ошибка загрузки графика", "danger");
            }
        }

        // ====================================================
        //  REAL-TIME: трейды
        // ====================================================
        function initRealtimeTrades() {
            const wsUrl = `ws://${location.host}/ws/trades?chatId=${chatId}&symbol=${symbol}`;
            console.log("🔌 WS TRADES CONNECT:", wsUrl);
            let ws;

            function connect() {
                ws = new WebSocket(wsUrl);

                ws.onopen = () => console.log("🟢 WebSocket (trades) открыт");

                ws.onmessage = (msg) => {
                    try {
                        const t = JSON.parse(msg.data);
                        console.log("📩 WS TRADE:", t);

                        const timeSec = Math.floor(t.time / 1000);

                        const obj = {
                            id: t.id,
                            time: timeSec,
                            side: t.side,
                            price: t.price,
                            qty: t.quantity,
                            entryReason: t.entryReason,
                            exitReason: t.exitReason,
                            pnlUsd: t.realizedPnlUsd,
                            pnlPct: t.realizedPnlPct,
                            tpPrice: t.takeProfitPrice,
                            slPrice: t.stopLossPrice,
                            status: t.status,
                        };

                        window.__chartTrades.push(obj);

                        candleSeries.setMarkers(
                            window.__chartTrades.map((x) => ({
                                time: x.time,
                                position: x.side === "BUY" ? "belowBar" : "aboveBar",
                                color: x.side === "BUY" ? "#2ecc71" : "#e74c3c",
                                shape: x.side === "BUY" ? "arrowUp" : "arrowDown",
                                text: `${x.side} ${x.qty} @${x.price}`,
                            }))
                        );
                    } catch (err) {
                        console.error("WS TRADE parse error:", err);
                    }
                };

                ws.onerror = () => ws.close();
                ws.onclose = () => {
                    console.log("🔌 WS TRADE закрыт → reconnect 3s");
                    setTimeout(connect, 3000);
                };
            }

            connect();
        }

        // ====================================================
        //  REAL-TIME: свечи
        // ====================================================
        function initRealtimeCandles() {
            const wsUrl = `ws://${location.host}/ws/candles?symbol=${symbol}&timeframe=${currentTimeframe}`;
            console.log("🔌 WS CANDLES CONNECT:", wsUrl);
            let ws;

            function connect() {
                ws = new WebSocket(wsUrl);

                ws.onopen = () => console.log("🟢 WebSocket (candles) открыт");

                ws.onmessage = (msg) => {
                    try {
                        const k = JSON.parse(msg.data);

                        const candle = {
                            time: Math.floor(k.time / 1000),
                            open: k.open,
                            high: k.high,
                            low: k.low,
                            close: k.close,
                        };

                        candleSeries.update(candle);
                        if (elLastPrice) elLastPrice.textContent = candle.close.toFixed(4);
                    } catch (err) {
                        console.error("WS CANDLE parse error:", err);
                    }
                };

                ws.onerror = () => ws.close();
                ws.onclose = () => {
                    console.log("🔌 WS CANDLE закрыт → reconnect 3s");
                    setTimeout(connect, 3000);
                };
            }

            connect();
        }

        // ====================================================
        //  Кнопки
        // ====================================================
        function initButtons() {
            if (btnRefresh) btnRefresh.addEventListener("click", () => loadChartData(true));

            if (btnExport && window.html2canvas) {
                btnExport.addEventListener("click", async () => {
                    const node = document.querySelector(".chart-wrapper-main");
                    if (!node) return;
                    const canvas = await window.html2canvas(node);
                    const link = document.createElement("a");
                    link.download = `chart-${symbol}-${currentTimeframe}.png`;
                    link.href = canvas.toDataURL("image/png");
                    link.click();
                });
            }
        }

        // ====================================================
        //  START
        // ====================================================
        initChart();
        initButtons();

        loadTimeframes().then(() => {
            setTimeout(async () => {
                await loadChartData(false);

                // 🚀 REAL-TIME
                initRealtimeTrades();
                initRealtimeCandles();
            }, 100);
        });
    });
})();

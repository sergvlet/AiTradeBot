(function () {
    console.log("📈 strategy-chart.js loaded (ULTRA FIX v2)");

    const toastAvailable = typeof window.showToast === "function";
    function notify(msg, type = "success") {
        if (toastAvailable) {
            window.showToast(msg, type);
        } else {
            console.log(`[${type}] ${msg}`);
        }
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

        // элементы для верхних метрик
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

        // trades и индикаторы для тултипа
        window.__chartTrades = [];
        const indicatorByTime = new Map(); // key: time(sec) -> { rsi, atr }

        // ====================================================
        //  ИНИЦИАЛИЗАЦИЯ ГРАФИКА
        // ====================================================
        function initChart() {
            if (!chartContainer || !window.LightweightCharts) {
                console.warn("Chart container or LightweightCharts not available");
                return;
            }

            const { clientWidth } = chartContainer;

            chart = LightweightCharts.createChart(chartContainer, {
                width: clientWidth,
                height: 420,
                layout: {
                    background: { type: "solid", color: "#050814" },
                    textColor: "#dce6ff"
                },
                grid: {
                    vertLines: { color: "rgba(255,255,255,0.04)" },
                    horzLines: { color: "rgba(255,255,255,0.04)" }
                },
                rightPriceScale: { borderColor: "rgba(197,203,206,0.4)" },
                leftPriceScale: {
                    visible: true,
                    borderColor: "rgba(197,203,206,0.4)"
                },
                timeScale: {
                    borderColor: "rgba(197,203,206,0.4)",
                    timeVisible: true,
                    secondsVisible: false
                },
                crosshair: {
                    mode: LightweightCharts.CrosshairMode.Normal
                }
            });

            // Свечи (правая шкала)
            candleSeries = chart.addCandlestickSeries({
                upColor: "#2ecc71",
                downColor: "#e74c3c",
                borderUpColor: "#2ecc71",
                borderDownColor: "#e74c3c",
                wickUpColor: "#2ecc71",
                wickDownColor: "#e74c3c"
            });

            // EMA fast/slow (правая шкала, РАЗНЫЕ цвета)
            emaFastSeries = chart.addLineSeries({
                lineWidth: 2,
                priceLineVisible: false,
                color: "#00bcd4" // бирюзовый
            });

            emaSlowSeries = chart.addLineSeries({
                lineWidth: 2,
                priceLineVisible: false,
                color: "#ff9800" // оранжевый
            });

            // RSI и ATR — отдельная левая шкала `rsi`
            rsiSeries = chart.addLineSeries({
                priceScaleId: "rsi",
                lineWidth: 1,
                priceLineVisible: false,
                color: "#9c27b0" // фиолетовый
            });

            atrSeries = chart.addLineSeries({
                priceScaleId: "rsi",
                lineWidth: 1,
                priceLineVisible: false,
                color: "#cddc39" // лаймовый
            });

            resizeObserver = new ResizeObserver((entries) => {
                for (const entry of entries) {
                    const newWidth = entry.contentRect.width;
                    chart.applyOptions({ width: newWidth });
                }
            });
            resizeObserver.observe(chartContainer);

            initTooltip();
        }

        // ====================================================
        //  ТУЛТИП
        // ====================================================
        function initTooltip() {
            if (!chart || !chartContainer) return;

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
                if (!param || !param.time || !param.point) {
                    tooltip.style.display = "none";
                    return;
                }

                const seriesPrice = param.seriesData.get
                    ? param.seriesData.get(candleSeries)
                    : param.seriesData[candleSeries];
                const candle = seriesPrice;
                if (!candle) {
                    tooltip.style.display = "none";
                    return;
                }

                const tsSec = typeof param.time === "number" ? param.time : param.time;
                const tsMs = typeof tsSec === "number" ? tsSec * 1000 : tsSec;
                const d = new Date(tsMs);

                const pad = (n) => (n < 10 ? "0" + n : "" + n);
                const dateStr =
                    pad(d.getDate()) +
                    "." +
                    pad(d.getMonth() + 1) +
                    "." +
                    d.getFullYear() +
                    " " +
                    pad(d.getHours()) +
                    ":" +
                    pad(d.getMinutes());

                const o = candle.open;
                const h = candle.high;
                const l = candle.low;
                const c = candle.close;

                const ind = indicatorByTime.get(tsSec) || {};
                const rsiVal = ind.rsi;
                const atrVal = ind.atr;

                // ищем сделки ±60 сек от свечи
                const tradesAtTime = (window.__chartTrades || []).filter(
                    (t) => Math.abs(t.time - tsSec) <= 60
                );

                let html = "";
                html += `<div><b>${symbol}</b> (${currentTimeframe})</div>`;
                html += `<div class="text-muted">${dateStr}</div>`;
                html += `<hr style="margin:4px 0;border-color:rgba(255,255,255,0.1)">`;
                html += `<div>O: <b>${o.toFixed(4)}</b>  H: <b>${h.toFixed(4)}</b></div>`;
                html += `<div>L: <b>${l.toFixed(4)}</b>  C: <b>${c.toFixed(4)}</b></div>`;

                if (rsiVal != null || atrVal != null) {
                    html += `<div style="margin-top:4px;">`;
                    if (rsiVal != null) {
                        html += `RSI: <b>${rsiVal.toFixed(1)}</b>`;
                    }
                    if (atrVal != null) {
                        if (rsiVal != null) html += " &nbsp; ";
                        html += `ATR: <b>${atrVal.toFixed(2)}</b>`;
                    }
                    html += `</div>`;
                }

                if (tradesAtTime.length > 0) {
                    tradesAtTime.forEach((t, idx) => {
                        if (idx === 0) {
                            html += `<hr style="margin:4px 0;border-color:rgba(255,255,255,0.1)">`;
                        } else {
                            html += `<hr style="margin:2px 0;border-color:rgba(255,255,255,0.05)">`;
                        }

                        html += `<div style="margin-bottom:2px;">
<span style="color:#ffcd4c;">Сделка</span> <b>${t.side}</b> ${t.qty ?? ""} @ ${t.price ?? ""}
</div>`;

                        if (t.entryReason) {
                            html += `<div>Причина входа: <b>${t.entryReason}</b></div>`;
                        }
                        if (t.exitReason) {
                            html += `<div>Причина выхода: <b>${t.exitReason}</b></div>`;
                        }
                        if (t.tpPrice || t.slPrice) {
                            html += `<div>TP/SL: `;
                            if (t.tpPrice) html += `TP <b>${t.tpPrice}</b> `;
                            if (t.slPrice) html += `SL <b>${t.slPrice}</b>`;
                            html += `</div>`;
                        }
                        if (t.pnlUsd != null || t.pnlPct != null) {
                            html += `<div>P&L: `;
                            if (t.pnlUsd != null)
                                html += `<b>${t.pnlUsd.toFixed(4)} USDT</b> `;
                            if (t.pnlPct != null)
                                html += `(<b>${t.pnlPct.toFixed(2)}%</b>)`;
                            html += `</div>`;
                        }
                        if (t.mlConfidence != null) {
                            html += `<div>ML conf: <b>${t.mlConfidence.toFixed(3)}</b></div>`;
                        }
                        if (t.status) {
                            html += `<div>Статус: <b>${t.status}</b></div>`;
                        }
                    });
                }

                tooltip.innerHTML = html;
                tooltip.style.display = "block";

                const x = param.point.x;
                const y = param.point.y;

                const tooltipWidth = tooltip.clientWidth;
                const tooltipHeight = tooltip.clientHeight;

                let left = x + 15;
                let top = y + 15;

                if (left + tooltipWidth > chartContainer.clientWidth) {
                    left = x - tooltipWidth - 15;
                }
                if (top + tooltipHeight > chartContainer.clientHeight) {
                    top = y - tooltipHeight - 15;
                }

                tooltip.style.left = `${left}px`;
                tooltip.style.top = `${top}px`;
            });
        }

        // ====================================================
        //  ТАЙМФРЕЙМЫ
        // ====================================================
        async function loadTimeframes() {
            if (!tfSelect) return;

            try {
                tfSelect.innerHTML = '<option value="">Загрузка…</option>';

                const url =
                    `${timeframesApiUrl}?exchange=${encodeURIComponent(exchange)}` +
                    `&networkType=${encodeURIComponent(network)}`;

                const resp = await fetch(url);
                if (!resp.ok) throw new Error("HTTP " + resp.status);

                const data = await resp.json();
                const arr = Array.isArray(data) ? data : data.timeframes || [];
                const timeframes = arr && arr.length ? arr : [currentTimeframe];

                if (!timeframes.includes(currentTimeframe)) {
                    timeframes.unshift(currentTimeframe);
                }

                tfSelect.innerHTML = "";
                timeframes.forEach((tf) => {
                    const opt = document.createElement("option");
                    opt.value = tf;
                    opt.textContent = tf;
                    if (tf === currentTimeframe) opt.selected = true;
                    tfSelect.appendChild(opt);
                });

                tfSelect.addEventListener("change", () => {
                    currentTimeframe = tfSelect.value || currentTimeframe;
                    loadChartData(true);
                });
            } catch (e) {
                console.error("Ошибка загрузки таймфреймов", e);
                notify("Не удалось загрузить таймфреймы", "danger");

                if (tfSelect) {
                    tfSelect.innerHTML = "";
                    const opt = document.createElement("option");
                    opt.value = currentTimeframe;
                    opt.textContent = currentTimeframe;
                    opt.selected = true;
                    tfSelect.appendChild(opt);
                }
            }
        }

        // ====================================================
        //  RSI / ATR
        // ====================================================
        function computeRsi(candles, period = 14) {
            const result = [];
            if (!candles || candles.length <= period) return result;

            let gains = 0;
            let losses = 0;

            for (let i = 1; i <= period; i++) {
                const change = candles[i].close - candles[i - 1].close;
                if (change >= 0) gains += change;
                else losses -= change;
            }

            let avgGain = gains / period;
            let avgLoss = losses / period;

            for (let i = period + 1; i < candles.length; i++) {
                const change = candles[i].close - candles[i - 1].close;
                const gain = change > 0 ? change : 0;
                const loss = change < 0 ? -change : 0;

                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;

                let rs;
                if (avgLoss === 0) rs = 100;
                else rs = avgGain / avgLoss;

                const rsi = 100 - 100 / (1 + rs);

                result.push({
                    time: candles[i].time,
                    value: rsi
                });
            }

            return result;
        }

        function computeAtr(candles, period = 14) {
            const result = [];
            if (!candles || candles.length <= period) return result;

            const trs = [];

            for (let i = 1; i < candles.length; i++) {
                const cur = candles[i];
                const prev = candles[i - 1];

                const highLow = cur.high - cur.low;
                const highClose = Math.abs(cur.high - prev.close);
                const lowClose = Math.abs(cur.low - prev.close);

                const tr = Math.max(highLow, highClose, lowClose);
                trs.push({ time: cur.time, tr });
            }

            if (trs.length < period) return result;

            let atr = 0;
            for (let i = 0; i < period; i++) {
                atr += trs[i].tr;
            }
            atr = atr / period;

            result.push({
                time: trs[period - 1].time,
                value: atr
            });

            for (let i = period; i < trs.length; i++) {
                atr = (atr * (period - 1) + trs[i].tr) / period;
                result.push({
                    time: trs[i].time,
                    value: atr
                });
            }

            return result;
        }

        // ====================================================
        //  ПОИСК БЛИЖАЙШЕЙ СВЕЧИ ДЛЯ СДЕЛКИ
        // ====================================================
        function findNearestCandleTime(timeSec, candles) {
            if (!candles || candles.length === 0) return timeSec;
            let best = candles[0].time;
            let bestDiff = Math.abs(best - timeSec);
            for (let i = 1; i < candles.length; i++) {
                const t = candles[i].time;
                const diff = Math.abs(t - timeSec);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = t;
                }
            }
            return best;
        }

        // ====================================================
        //  ОБНОВЛЕНИЕ ВЕРХНИХ МЕТРИК
        // ====================================================
        function updateTopStats(candles) {
            if (!candles || candles.length === 0) {
                if (elLastPrice) elLastPrice.textContent = "—";
                if (elChangePct) elChangePct.textContent = "—";
                if (elRange) elRange.textContent = "—";
                if (elTrend) elTrend.textContent = "—";
                return;
            }

            const first = candles[0].close;
            const last = candles[candles.length - 1].close;

            let min = candles[0].low;
            let max = candles[0].high;
            candles.forEach(c => {
                if (c.low < min) min = c.low;
                if (c.high > max) max = c.high;
            });

            const changePct = first !== 0 ? ((last - first) / first) * 100 : 0;
            const trend =
                changePct > 0.2 ? "Восходящий" :
                    changePct < -0.2 ? "Нисходящий" : "Флэт";

            if (elLastPrice) elLastPrice.textContent = last.toFixed(4);
            if (elChangePct) elChangePct.textContent = `${changePct.toFixed(2)}%`;
            if (elRange) elRange.textContent = `${min.toFixed(4)} – ${max.toFixed(4)}`;
            if (elTrend) elTrend.textContent = trend;
        }

        // ====================================================
        //  ЗАГРУЗКА ДАННЫХ ДЛЯ ГРАФИКА
        // ====================================================
        async function loadChartData(showMsg = true) {
            if (!chart || !candleSeries) return;

            try {
                if (showMsg) notify("Загружаю данные графика…", "success");

                const url =
                    `${chartApiUrl}?chatId=${encodeURIComponent(chatId)}` +
                    `&symbol=${encodeURIComponent(symbol)}` +
                    `&timeframe=${encodeURIComponent(currentTimeframe)}` +
                    `&limit=250`;

                const resp = await fetch(url);
                if (!resp.ok) throw new Error("HTTP " + resp.status);
                const data = await resp.json();

                // --- свечи ---
                const candles = (data.candles || []).map((c) => ({
                    // backend отдаёт ms → sec
                    time: Math.floor(c.time / 1000),
                    open: c.open,
                    high: c.high,
                    low: c.low,
                    close: c.close
                }));
                candleSeries.setData(candles);

                // обновляем верхние метрики
                updateTopStats(candles);

                // --- EMA ---
                const emaFast = (data.emaFast || []).map((p) => ({
                    time: Math.floor(p.time / 1000),
                    value: p.value
                }));
                const emaSlow = (data.emaSlow || []).map((p) => ({
                    time: Math.floor(p.time / 1000),
                    value: p.value
                }));
                emaFastSeries.setData(emaFast);
                emaSlowSeries.setData(emaSlow);

                // --- RSI / ATR ---
                indicatorByTime.clear();

                const rsiData = computeRsi(candles, 14); // период 14 (можно потом сделать динамическим)
                const atrData = computeAtr(candles, 14);

                rsiSeries.setData(rsiData);
                atrSeries.setData(atrData);

                rsiData.forEach((p) => {
                    const t = p.time;
                    const obj = indicatorByTime.get(t) || {};
                    obj.rsi = p.value;
                    indicatorByTime.set(t, obj);
                });

                atrData.forEach((p) => {
                    const t = p.time;
                    const obj = indicatorByTime.get(t) || {};
                    obj.atr = p.value;
                    indicatorByTime.set(t, obj);
                });

                // --- трейды ---
                const trades = (data.trades || []).map((t) => {
                    const timeSecOriginal = Math.floor(t.time / 1000);
                    const mappedTime = findNearestCandleTime(timeSecOriginal, candles);

                    return {
                        id: t.id,
                        time: mappedTime,       // ⬅ время привязано к ближайшей свече
                        rawTime: t.time,        // ms
                        side: t.side,
                        price: t.price,
                        qty: t.qty,
                        status: t.status,
                        strategyType: t.strategyType,
                        exitPrice: t.exitPrice,
                        exitTime: t.exitTime,
                        tpPrice: t.tpPrice,
                        slPrice: t.slPrice,
                        tpHit: t.tpHit,
                        slHit: t.slHit,
                        pnlUsd: t.pnlUsd,
                        pnlPct: t.pnlPct,
                        entryReason: t.entryReason,
                        exitReason: t.exitReason,
                        mlConfidence: t.mlConfidence
                    };
                });
                window.__chartTrades = trades;

                const markers = trades.map((t) => ({
                    time: t.time,
                    position: t.side === "BUY" ? "belowBar" : "aboveBar",
                    color: t.side === "BUY" ? "#2ecc71" : "#e74c3c",
                    shape: t.side === "BUY" ? "arrowUp" : "arrowDown",
                    text: buildMarkerText(t)
                }));
                candleSeries.setMarkers(markers);

                chart.timeScale().fitContent();
                if (showMsg) notify("График обновлён", "success");
            } catch (e) {
                console.error("Ошибка загрузки графика", e);
                notify("Ошибка загрузки графика", "danger");
            }
        }

        function buildMarkerText(t) {
            let txt = `${t.side}`;
            if (t.qty) txt += ` ${t.qty}`;
            if (t.price) txt += ` @${t.price}`;
            if (t.pnlPct != null) txt += ` (${t.pnlPct.toFixed(1)}%)`;
            return txt;
        }

        // ====================================================
        //  КНОПКИ
        // ====================================================
        function initButtons() {
            if (btnRefresh) {
                btnRefresh.addEventListener("click", () => loadChartData(true));
            }

            if (btnExport && window.html2canvas) {
                btnExport.addEventListener("click", () => {
                    const node = document.querySelector(".chart-wrapper-main");
                    if (!node) return;
                    window.html2canvas(node).then((canvas) => {
                        const link = document.createElement("a");
                        link.download = `strategy-chart-${symbol}-${currentTimeframe}.png`;
                        link.href = canvas.toDataURL("image/png");
                        link.click();
                    });
                });
            }

            if (btnStart) {
                btnStart.addEventListener("click", () => {
                    notify(`Запуск стратегии ${type} для ${symbol}`, "success");
                    // TODO: сюда потом добавишь реальный fetch на /api/strategies/start
                });
            }

            if (btnStop) {
                btnStop.addEventListener("click", () => {
                    notify(`Остановка стратегии ${type} для ${symbol}`, "warning");
                    // TODO: сюда потом добавишь реальный fetch на /api/strategies/stop
                });
            }
        }

        // ====================================================
        //  СТАРТ
        // ====================================================
        initChart();
        initButtons();
        loadTimeframes().then(() => {
            setTimeout(() => loadChartData(false), 100);
        });
    });
})();

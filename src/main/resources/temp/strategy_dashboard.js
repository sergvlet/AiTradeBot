"use strict";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 strategy_dashboard.js loaded (UNIVERSAL LAYERS v1)");

    // =========================================================
    // CONTEXT
    // =========================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    const chatId = root?.dataset.chatId;
    const strategyType = root?.dataset.type;
    const symbol = root?.dataset.symbol;

    if (!chatId || !strategyType || !symbol) {
        console.error("❌ Missing dashboard context");
        return;
    }

    // =========================================================
    // CHART INIT
    // =========================================================
    const container = document.getElementById("strategy-chart");
    if (!container || typeof LightweightCharts === "undefined") {
        console.error("❌ Chart container or LightweightCharts missing");
        return;
    }

    const chart = LightweightCharts.createChart(container, {
        height: 420,
        layout: { background: { color: "#0e0f11" }, textColor: "#e0e0e0" },
        grid: { vertLines: { visible: false }, horzLines: { visible: false } },
        rightPriceScale: { borderColor: "#444", autoScale: true },
        timeScale: {
            borderColor: "#444",
            timeVisible: true,
            secondsVisible: false,
            rightOffset: 8, // ✅ смещение последней свечи влево (визуально 4-5 см)
            barSpacing: 8
        }
    });

    // =========================================================
    // MAIN SERIES
    // =========================================================
    const candleSeries = chart.addCandlestickSeries({
        upColor: "#26a69a",
        downColor: "#ef5350",
        wickUpColor: "#26a69a",
        wickDownColor: "#ef5350",
        borderVisible: false,

        // ✅ убираем дубли справа
        lastValueVisible: false,
        priceLineVisible: false
    });

    // =========================================================
    // STATE
    // =========================================================
    let lastCandleTimeSec = null; // unix seconds
    let lastPrice = null;

    // One Binance-like price label (single)
    let priceLine = null;

    // Layers
    const markers = [];
    const levelLines = new Map(); // key -> PriceLine
    const zoneLines = new Map();  // "top"/"bottom" -> PriceLine

    // =========================================================
    // HELPERS
    // =========================================================
    function toNumber(v) {
        const n = Number(v);
        return Number.isFinite(n) ? n : null;
    }

    function toTimeSec(msOrSec) {
        const n = Number(msOrSec);
        if (!Number.isFinite(n)) return null;
        // если больше ~10^12 — это миллисекунды
        return n > 10_000_000_000 ? Math.floor(n / 1000) : Math.floor(n);
    }

    function ensureRightOffset() {
        // держим постоянный отступ как на Binance
        chart.timeScale().applyOptions({ rightOffset: 8 });
    }

    // =========================================================
    // BINANCE PRICE LINE (ONE LABEL)
    // =========================================================
    function updatePriceLine(price) {
        const p = toNumber(price);
        if (p === null) return;

        const up = lastPrice === null || p >= lastPrice;
        const color = up ? "#26a69a" : "#ef5350";

        if (priceLine) candleSeries.removePriceLine(priceLine);

        priceLine = candleSeries.createPriceLine({
            price: p,
            color,
            lineWidth: 1,
            lineStyle: 2,
            axisLabelVisible: true,
            title: ""
        });

        lastPrice = p;
    }

    // =========================================================
    // LAYER RENDERER (UNIVERSAL)
    // =========================================================
    const LayerRenderer = {
        levels(payload) {
            // поддерживаем:
            // 1) payload = [123.45, 120.00]
            // 2) payload = [{price:123.45, color:"#..", id:".."}]
            const list = Array.isArray(payload) ? payload : [];
            if (!list.length) return;

            // сначала зачистим прошлые уровни (если стратегия присылает заново)
            // если хочешь "накоплением" — убери clearLevels()
            clearLevels();

            list.forEach((item, idx) => {
                const price = typeof item === "object" ? toNumber(item.price) : toNumber(item);
                if (price === null) return;

                const key = (typeof item === "object" && item.id) ? String(item.id) : `lvl_${idx}_${price}`;
                const color = (typeof item === "object" && item.color) ? String(item.color) : "#4b5563";

                const line = candleSeries.createPriceLine({
                    price,
                    color,
                    lineWidth: 1,
                    lineStyle: 0,
                    axisLabelVisible: false,
                    title: ""
                });

                levelLines.set(key, line);
            });
        },

        zone(zonePayload) {
            // поддерживаем:
            // zonePayload = {top: 100, bottom: 90}
            // zonePayload = {from: 100, to: 90}
            // zonePayload = {high: 100, low: 90}
            if (!zonePayload || typeof zonePayload !== "object") return;

            const top =
                toNumber(zonePayload.top) ??
                toNumber(zonePayload.from) ??
                toNumber(zonePayload.high);

            const bottom =
                toNumber(zonePayload.bottom) ??
                toNumber(zonePayload.to) ??
                toNumber(zonePayload.low);

            if (top === null || bottom === null) return;

            const zTop = Math.max(top, bottom);
            const zBottom = Math.min(top, bottom);

            // Binance-like band:
            // 1) верхняя линия невидимая, но задаёт backgroundColor
            // 2) нижняя линия невидимая
            // backgroundColor рисуется между ними
            clearZone();

            const bandColor = zonePayload.color || "rgba(56,189,248,0.12)"; // голубоватая полоса, полупрозрачная

            const topLine = candleSeries.createPriceLine({
                price: zTop,
                color: "rgba(0,0,0,0)", // линия невидима
                lineWidth: 1,
                lineStyle: 0,
                axisLabelVisible: false,
                title: "",
                backgroundColor: bandColor // ✅ полоса между top и bottom
            });

            const bottomLine = candleSeries.createPriceLine({
                price: zBottom,
                color: "rgba(0,0,0,0)",
                lineWidth: 1,
                lineStyle: 0,
                axisLabelVisible: false,
                title: ""
            });

            zoneLines.set("top", topLine);
            zoneLines.set("bottom", bottomLine);
        },

        trade(tradePayload, timeSec) {
            if (!tradePayload) return;

            const side = String(tradePayload.side || tradePayload.type || "").toUpperCase();
            const t = timeSec ?? lastCandleTimeSec;
            if (!t) return;

            const isBuy = side === "BUY";
            const isSell = side === "SELL";

            if (!isBuy && !isSell) return;

            markers.push({
                time: t,
                position: isBuy ? "belowBar" : "aboveBar",
                color: isBuy ? "#00e676" : "#ff5252",
                shape: isBuy ? "arrowUp" : "arrowDown",
                text: isBuy ? "BUY" : "SELL"
            });

            // ограничиваем, чтобы не лагало
            candleSeries.setMarkers(markers.slice(-300));
        }
    };

    function clearLevels() {
        for (const [k, pl] of levelLines.entries()) {
            try { candleSeries.removePriceLine(pl); } catch (_) {}
            levelLines.delete(k);
        }
    }

    function clearZone() {
        for (const [k, pl] of zoneLines.entries()) {
            try { candleSeries.removePriceLine(pl); } catch (_) {}
            zoneLines.delete(k);
        }
    }

    // =========================================================
    // LIVE CANDLE UPDATE (BINANCE-LIKE)
    // =========================================================
    function updateLastBarWithPriceTick(price) {
        const p = toNumber(price);
        if (p === null || !lastCandleTimeSec) return;

        // мы не знаем open/high/low текущего бара из price tick,
        // поэтому делаем "мягко": обновляем close + расширяем high/low, если нужно.
        // Для этого нужен последний бар. LightweightCharts не даёт getLastBar(),
        // поэтому мы храним "lastBarShadow".
    }

    let lastBarShadow = null; // {time, open, high, low, close}

    function applyCandle(bar) {
        if (!bar) return;
        candleSeries.update(bar);
        lastBarShadow = { ...bar };
    }

    function applyPriceTick(price) {
        const p = toNumber(price);
        if (p === null || !lastCandleTimeSec) return;

        if (!lastBarShadow || lastBarShadow.time !== lastCandleTimeSec) {
            // если тика пришло, а свечи ещё нет — создаём минимальный бар
            lastBarShadow = {
                time: lastCandleTimeSec,
                open: p,
                high: p,
                low: p,
                close: p
            };
            candleSeries.update(lastBarShadow);
            return;
        }

        const hi = Math.max(lastBarShadow.high, p);
        const lo = Math.min(lastBarShadow.low, p);

        const updated = {
            time: lastBarShadow.time,
            open: lastBarShadow.open,
            high: hi,
            low: lo,
            close: p
        };

        candleSeries.update(updated);
        lastBarShadow = updated;
    }

    // =========================================================
    // HISTORY LOAD
    // =========================================================
    const historyUrl =
        `/api/chart/strategy?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(strategyType)}` +
        `&symbol=${encodeURIComponent(symbol)}` +
        `&timeframe=1m&limit=500`;

    fetch(historyUrl)
        .then(r => (r.ok ? r.json() : null))
        .then(d => {
            if (!d?.candles?.length) return;

            const data = d.candles
                .map(c => ({
                    time: Math.floor(Number(c.time) / 1000),
                    open: +c.open,
                    high: +c.high,
                    low: +c.low,
                    close: +c.close
                }))
                .filter(x => Number.isFinite(x.time));

            if (!data.length) return;

            candleSeries.setData(data);

            const last = data[data.length - 1];
            lastCandleTimeSec = last.time;
            lastBarShadow = { ...last };

            updatePriceLine(last.close);
            ensureRightOffset();
            chart.timeScale().scrollToRealTime();
        })
        .catch(e => console.warn("history load error:", e));

    // =========================================================
    // WEBSOCKET
    // =========================================================
    const socket = new SockJS("/ws/strategy");
    const stomp = Stomp.over(socket);
    stomp.debug = () => {};

    stomp.connect(
        {},
        () => {
            const topic = `/topic/strategy/${chatId}/${strategyType}`;
            console.log("📡 SUBSCRIBE:", topic);

            stomp.subscribe(topic, msg => {
                try {
                    const ev = JSON.parse(msg.body);
                    routeEvent(ev);
                } catch (e) {
                    console.warn("bad ws msg:", e);
                }
            });
        },
        () => {
            console.warn("🔴 STOMP DISCONNECTED");
            // авто-реконнект можно добавить, если надо
        }
    );

    // =========================================================
    // EVENT ROUTER
    // =========================================================
    function routeEvent(ev) {
        if (!ev?.type) return;

        switch (String(ev.type).toLowerCase()) {
            case "candle":
                renderCandle(ev);
                break;
            case "price":
                renderPrice(ev);
                break;
            case "trade":
                renderTrade(ev);
                break;
            case "levels":
                LayerRenderer.levels(ev.levels);
                break;
            case "zone":
                // поддерживаем zone как ev.zone или как top/bottom прямо в event
                LayerRenderer.zone(ev.zone || ev);
                break;
            default:
                // остальные типы можно добавить позже
                break;
        }
    }

    // =========================================================
    // RENDERERS
    // =========================================================
    function renderCandle(ev) {
        if (!ev.kline) return;

        const t = toTimeSec(ev.time);
        if (!t) return;

        lastCandleTimeSec = t;

        const bar = {
            time: t,
            open: +ev.kline.open,
            high: +ev.kline.high,
            low: +ev.kline.low,
            close: +ev.kline.close
        };

        applyCandle(bar);
        updatePriceLine(bar.close);

        ensureRightOffset();
        chart.timeScale().scrollToRealTime();
    }

    function renderPrice(ev) {
        const p = toNumber(ev.price);
        const t = toTimeSec(ev.time) ?? lastCandleTimeSec; // если сервер шлёт time — отлично
        if (t && lastCandleTimeSec === null) lastCandleTimeSec = t;

        if (p === null) return;

        // ✅ обновляем последнюю свечу “как Binance”
        applyPriceTick(p);

        // ✅ и обновляем единственный label цены
        updatePriceLine(p);
    }

    function renderTrade(ev) {
        if (!ev.trade) return;

        const t = toTimeSec(ev.time) ?? lastCandleTimeSec;
        LayerRenderer.trade(ev.trade, t);

        // можно ещё обновлять priceLine по цене сделки, если хочешь:
        // if (ev.trade.price) updatePriceLine(ev.trade.price);
    }

    // =========================================================
    // RESIZE
    // =========================================================
    window.addEventListener("resize", () => {
        chart.applyOptions({ width: container.clientWidth });
    });
});

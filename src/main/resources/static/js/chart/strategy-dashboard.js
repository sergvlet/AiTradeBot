"use strict";

import { ChartController } from "/js/chart/chart-controller.js";
import { LayerRenderer }   from "/js/chart/layer-renderer.js";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard (clean architecture)");

    // =====================================================
    // CONTEXT
    // =====================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId       = root.dataset.chatId;
    const strategyType = root.dataset.type;
    const symbol       = root.dataset.symbol;

    console.log("🧩 CONTEXT", { chatId, strategyType, symbol });

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ #strategy-chart not found");
        return;
    }

    // =====================================================
    // INIT CHART
    // =====================================================
    const chartCtrl = new ChartController(container);
    const layers    = new LayerRenderer(chartCtrl.chart, chartCtrl.candles);

    console.log("✅ Chart initialized");

    // =====================================================
    // HISTORY (REST)
    // =====================================================
    fetch(
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(strategyType)}` +
        `&symbol=${encodeURIComponent(symbol)}` +
        `&timeframe=1m&limit=500`
    )
        .then(r => r.ok ? r.json() : null)
        .then(d => {
            if (!d || !Array.isArray(d.candles) || d.candles.length === 0) {
                console.warn("⚠ No candles from REST");

                // =================================================
                // REST SNAPSHOT CLEANUP
                // =================================================
                // ⚠️ REST = snapshot
                // ❗️ЧИСТИМ ТОЛЬКО snapshot-слои
                if (typeof layers.clearLevels === "function") layers.clearLevels();
                if (typeof layers.clearZone === "function") layers.clearZone();
                if (typeof layers.clearTpSl === "function") layers.clearTpSl();
                if (typeof layers.clearTradeZone === "function") layers.clearTradeZone();

                // ❌ НЕ ЧИСТИМ:
                // priceLines / windowZone / atr
                // они живут ТОЛЬКО через WebSocket

                return;
            }

            const candles = d.candles.map(c => ({
                time: Number(c.time), // seconds
                open: +c.open,
                high: +c.high,
                low:  +c.low,
                close:+c.close
            }));

            chartCtrl.setHistory(candles);

            const minPrice = Math.min(...candles.map(c => c.low));
            const maxPrice = Math.max(...candles.map(c => c.high));
            console.log("📊 PRICE RANGE", { minPrice, maxPrice });

            // =================================================
            // STATIC LAYERS FROM REST (SNAPSHOT)
            // =================================================
            // ⚠️ REST — snapshot
            // сначала чистим snapshot-слои
            if (typeof layers.clearLevels === "function") layers.clearLevels();
            if (typeof layers.clearZone === "function") layers.clearZone();
            if (typeof layers.clearTpSl === "function") layers.clearTpSl();
            if (typeof layers.clearTradeZone === "function") layers.clearTradeZone();

            // ❌ НЕ ТРОГАЕМ:
            // clearPriceLines / clearWindowZone / clearAtr

            if (d.layers) {

                // --- LEVELS ---
                if (Array.isArray(d.layers.levels)) {
                    console.log("🟣 REST LEVELS", d.layers.levels);
                    if (d.layers.levels.length > 0) {
                        layers.renderLevels(d.layers.levels);
                    }
                }

                // --- ZONE ---
                if (d.layers.zone && typeof layers.renderZone === "function") {
                    console.log("🟠 REST ZONE", d.layers.zone);
                    layers.renderZone(d.layers.zone);
                }

                // --- TP/SL ---
                if (d.layers.tpSl && typeof layers.renderTpSl === "function") {
                    console.log("📍 REST TP/SL", d.layers.tpSl);
                    layers.renderTpSl(d.layers.tpSl);
                }

                // ⚠️ PRICE LINES / WINDOW / ATR
                // REST НЕ является их источником
            }

            chartCtrl.chart.timeScale().scrollToRealTime();
        })
        .catch(e => console.error("❌ REST history error", e));

    // =====================================================
    // WEBSOCKET
    // =====================================================
    const socket = new SockJS("/ws/strategy");
    const stomp  = Stomp.over(socket);
    stomp.debug = () => {};

    stomp.connect({}, () => {
        const topic = `/topic/strategy/${chatId}/${strategyType}`;
        console.log("📡 SUBSCRIBE", topic);

        stomp.subscribe(topic, msg => {
            let ev;
            try {
                ev = JSON.parse(msg.body);
            } catch (e) {
                console.warn("⚠ Bad WS message", msg.body);
                return;
            }

            if (!ev || !ev.type) return;

            switch (ev.type) {

                case "candle":
                    chartCtrl.onCandle(ev);
                    break;

                case "price":
                    console.log("🔥 PRICE RECEIVED", ev.price);
                    chartCtrl.onPrice(ev);
                    if (typeof layers.onPriceUpdate === "function") {
                        layers.onPriceUpdate(Number(ev.price));
                    }
                    break;

                case "levels":
                    if (Array.isArray(ev.levels)) {
                        if (ev.levels.length === 0) {
                            layers.clearLevels?.();
                        } else {
                            layers.renderLevels(ev.levels);
                        }
                    }
                    break;

                case "price_line":
                    ev.priceLine
                        ? layers.renderPriceLine?.(ev.priceLine)
                        : layers.clearPriceLines?.();
                    break;

                case "window_zone":
                    ev.windowZone
                        ? layers.renderWindowZone?.(ev.windowZone)
                        : layers.clearWindowZone?.();
                    break;

                case "atr":
                    ev.atr
                        ? layers.renderAtr?.(ev.atr)
                        : layers.clearAtr?.();
                    break;

                case "trade_zone":
                    ev.tradeZone
                        ? layers.renderTradeZone?.(ev.tradeZone)
                        : layers.clearTradeZone?.();
                    break;

                case "tp_sl":
                    ev.tpSl
                        ? layers.renderTpSl?.(ev.tpSl)
                        : layers.clearTpSl?.();
                    break;

                case "order":
                    layers.renderOrder?.(ev.order);
                    break;

                case "magnet":
                    layers.onMagnet?.(ev.magnet);
                    break;

                case "trade":
                    layers.renderTrade?.(
                        ev.trade,
                        ev.time ? Math.floor(ev.time / 1000) : undefined
                    );
                    break;

                default:
                    break;
            }
        });

        fetch(`/api/strategy/${chatId}/${strategyType}/replay`, {
            method: "POST"
        }).catch(() => {});
    });

    // =====================================================
    // RESIZE
    // =====================================================
    window.addEventListener("resize", () => {
        chartCtrl.chart.applyOptions({
            width: container.clientWidth
        });
    });
});

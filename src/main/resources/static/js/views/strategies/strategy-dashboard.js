"use strict";

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer }   from "../../chart/layer-renderer.js";

import { ScalpingStrategy }    from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy }   from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard START");

    // =========================================================================
    // CONTEXT
    // =========================================================================

    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = root.dataset.chatId;
    const type   = root.dataset.type;
    const symbol = (root.dataset.symbol || "").trim().toUpperCase();

    console.log("🧩 Context:", { chatId, type, symbol });

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ #strategy-chart not found");
        return;
    }

    // =========================================================================
    // CHART
    // =========================================================================

    const chartCtrl = new ChartController(container);
    chartCtrl.symbol    = symbol;
    chartCtrl.timeframe = "1m";

    const layers = new LayerRenderer(chartCtrl.chart, chartCtrl.candles);
    layers.candlesData = chartCtrl.candlesData;

    // =========================================================================
    // STRATEGY
    // =========================================================================

    const ctx = { chatId, type, symbol };
    let strategy;

    switch (type) {
        case "SCALPING":
            strategy = new ScalpingStrategy({ layers, ctx });
            break;
        case "FIBONACCI":
            strategy = new FibonacciStrategy({ layers, ctx });
            break;
        case "SMART_FUSION":
            strategy = new SmartFusionStrategy({ layers, ctx });
            break;
        default:
            console.error("❌ Unknown strategy type:", type);
            return;
    }

    console.log("🧠 Strategy initialized:", type);

    // =========================================================================
    // REST SNAPSHOT (HISTORY)
    // =========================================================================

    const snapshotUrl =
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(type)}` +
        `&symbol=${encodeURIComponent(symbol)}`;

    fetch(snapshotUrl)
        .then(r => r.json())
        .then(data => {
            if (Array.isArray(data?.candles)) {
                chartCtrl.setHistory(data.candles);
            }

            if (data?.layers) {
                strategy.onEvent?.({ type: "layers", layers: data.layers });

                // ✅ SCALPING: отрисовка window zone
                if (type === "SCALPING" && data.layers.windowZone) {
                    strategy.onEvent?.({
                        type: "window_zone",
                        windowZone: data.layers.windowZone
                    });
                }
            }

        })
        .catch(err => console.error("❌ REST snapshot error", err));

    // =========================================================================
    // WEBSOCKET (STOMP)
    // =========================================================================

    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
        console.error("❌ SockJS / Stomp not loaded");
        return;
    }

    const socket = new SockJS("/ws/strategy/");
    const stomp  = Stomp.over(socket);
    stomp.debug = null;

    stomp.connect({}, () => {
        console.log("✅ STOMP CONNECTED");

        const destinations = [
            `/topic/strategy/${chatId}/${type}/${symbol}`,
            `/topic/strategy/${chatId}/${type}`,
            `/topic/strategy/${chatId}`,
        ];

        destinations.forEach(dest => {
            stomp.subscribe(dest, msg => {
                let ev;
                try { ev = JSON.parse(msg.body); } catch { return; }

                const evSymbol = (ev?.symbol || "").trim().toUpperCase();
                if (evSymbol && evSymbol !== symbol) return;

                // 🔥 ЕДИНСТВЕННЫЙ ВХОД В ГРАФИК
                chartCtrl.onWsMessage(ev);

                // стратегия получает ВСЁ
                strategy.onEvent?.(ev);
            });

            console.log("✅ SUBSCRIBED", dest);
        });

        // replay после подписки
        fetch(`/api/strategy/${chatId}/${type}/replay`, { method: "POST" });
    });

    // =========================================================================
    // RESIZE
    // =========================================================================

    window.addEventListener("resize", () => {
        chartCtrl.chart.applyOptions({ width: container.clientWidth });
        chartCtrl.adjustBarSpacing();
    });

    chartCtrl.adjustBarSpacing();
    console.log("📊 Strategy Dashboard INITIALIZED");
});

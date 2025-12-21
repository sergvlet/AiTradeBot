"use strict";

/**
 * StrategyDashboard (ШАГ 11)
 * -------------------------
 * ОРКЕСТРАТОР.
 *
 * Делает ТОЛЬКО:
 * - создаёт chart-controller
 * - создаёт layer-renderer
 * - создаёт strategy по type
 * - прокидывает ВСЕ события (REST + WS) в strategy.onEvent(ev)
 *
 * НЕ:
 * - рисует
 * - знает логику фич
 * - знает бизнес-логику стратегии
 */

import { ChartController } from "./chart-controller.js";
import { LayerRenderer }   from "./layer-renderer.js";

import { ScalpingStrategy }   from "../strategies/scalping.strategy.js";
import { FibonacciStrategy }  from "../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../strategies/smartfusion.strategy.js";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard (orchestrator)");

    // =====================================================
    // CONTEXT
    // =====================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = root.dataset.chatId;
    const type   = root.dataset.type;
    const symbol = root.dataset.symbol;

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

    // =====================================================
    // INIT STRATEGY
    // =====================================================
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

    // =====================================================
    // HISTORY (REST = SNAPSHOT)
    // =====================================================
    fetch(
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(type)}` +
        `&symbol=${encodeURIComponent(symbol)}` +
        `&timeframe=1m&limit=500`
    )
        .then(r => r.ok ? r.json() : null)
        .then(data => {
            if (!data) return;

            // --- candles ---
            if (Array.isArray(data.candles) && data.candles.length > 0) {
                const candles = data.candles.map(c => ({
                    time: Number(c.time),
                    open: +c.open,
                    high: +c.high,
                    low:  +c.low,
                    close:+c.close
                }));

                chartCtrl.setHistory(candles);
            }

            // --- snapshot layers ---
            if (data.layers) {
                if (Array.isArray(data.layers.levels)) {
                    strategy.onEvent({
                        type: "levels",
                        levels: data.layers.levels
                    });
                }

                if (data.layers.zone) {
                    strategy.onEvent({
                        type: "zone",
                        zone: data.layers.zone
                    });
                }

                if (data.layers.tpSl) {
                    strategy.onEvent({
                        type: "tp_sl",
                        tpSl: data.layers.tpSl
                    });
                }
            }
        })
        .catch(e => console.error("❌ REST error", e));

    // =====================================================
    // WEBSOCKET
    // =====================================================
    const socket = new SockJS("/ws/strategy");
    const stomp  = Stomp.over(socket);
    stomp.debug = () => {};

    stomp.connect({}, () => {
        const topic = `/topic/strategy/${chatId}/${type}`;
        console.log("📡 SUBSCRIBE", topic);

        stomp.subscribe(topic, msg => {
            let ev;
            try {
                ev = JSON.parse(msg.body);
            } catch {
                return;
            }

            if (!ev || !ev.type) return;

            // market events go directly to chart
            if (ev.type === "candle") {
                chartCtrl.onCandle(ev);
                return;
            }

            if (ev.type === "price") {
                chartCtrl.onPrice(ev);
                layers.onPriceUpdate?.(Number(ev.price));
                return;
            }

            // ВСЁ ОСТАЛЬНОЕ — в стратегию
            strategy.onEvent(ev);
        });

        // replay
        fetch(`/api/strategy/${chatId}/${type}/replay`, {
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

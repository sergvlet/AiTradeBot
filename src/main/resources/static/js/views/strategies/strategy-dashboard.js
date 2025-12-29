"use strict";

/**
 * StrategyDashboard
 * -----------------
 * ОРКЕСТРАТОР.
 *
 * Делает ТОЛЬКО:
 * - создаёт chart-controller
 * - создаёт layer-renderer
 * - создаёт strategy по type
 * - прокидывает ВСЕ события (REST + WS)
 *
 * НЕ:
 * - выбирает таймфрейм
 * - знает источник свечей
 * - содержит бизнес-логику
 */

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer }   from "../../chart/layer-renderer.js";

import { ScalpingStrategy }    from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy }   from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard");

    // =====================================================
    // CONTEXT (FROM HTML)
    // =====================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = root.dataset.chatId;
    const type   = root.dataset.type;
    const symbol = root.dataset.symbol;

    if (!chatId || !type || !symbol) {
        console.error("❌ Invalid context:", { chatId, type, symbol });
        return;
    }

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ #strategy-chart not found");
        return;
    }

    console.log("🧩 Context:", { chatId, type, symbol });

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
    // NORMALIZERS (🔥 КРИТИЧНО)
    // =====================================================
    function normalizeCandleEvent(ev) {
        if (!ev) return null;

        let out = ev;

        // unwrap kline (WS)
        if (ev.kline && typeof ev.kline === "object") {
            const k = ev.kline;
            out = {
                ...ev,
                open:   k.open,
                high:   k.high,
                low:    k.low,
                close:  k.close,
                volume: k.volume,
                timeframe: k.timeframe,
                time: ev.time
            };
        }

        // ❗ ОБЯЗАТЕЛЬНО: millis → seconds
        if (out.time != null) {
            out.time = Math.floor(Number(out.time) / 1000);
        }

        return out;
    }

    function normalizeRestCandle(c) {
        if (!c) return null;

        // REST почти всегда приходит в millis
        if (c.time != null) {
            return {
                ...c,
                time: Math.floor(Number(c.time) / 1000)
            };
        }
        return c;
    }

    // =====================================================
    // SNAPSHOT (REST)
    // =====================================================
    const snapshotUrl =
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(type)}` +
        `&symbol=${encodeURIComponent(symbol)}`;

    console.log("📦 REST snapshot:", snapshotUrl);

    fetch(snapshotUrl)
        .then(r => (r.ok ? r.json() : null))
        .then(data => {
            if (!data) return;

            // ===== CANDLES =====
            if (Array.isArray(data.candles) && data.candles.length > 0) {
                const candles = data.candles
                    .map(normalizeRestCandle)
                    .filter(Boolean);

                console.log(`🕯 REST candles: ${candles.length}`);
                chartCtrl.setHistory(candles);
            }

            // ===== STRATEGY LAYERS =====
            if (data.layers) {
                if (Array.isArray(data.layers.levels)) {
                    strategy.onEvent({ type: "levels", levels: data.layers.levels });
                }
                if (data.layers.zone) {
                    strategy.onEvent({ type: "zone", zone: data.layers.zone });
                }
                if (data.layers.tpSl) {
                    strategy.onEvent({ type: "tp_sl", tpSl: data.layers.tpSl });
                }
            }
        })
        .catch(e => console.error("❌ REST snapshot error", e));

    // =====================================================
    // WEBSOCKET (LIVE)
    // =====================================================
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
        console.error("❌ SockJS/Stomp not found");
        return;
    }

    const socket = new SockJS("/ws/strategy");
    const stomp  = Stomp.over(socket);

    const DEBUG_WS = true;
    stomp.debug = DEBUG_WS ? (s) => console.log("🧵 STOMP:", s) : () => {};

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
            if (ev.symbol && ev.symbol !== symbol) return;

            // ===== MARKET EVENTS =====
            if (ev.type === "candle") {
                const norm = normalizeCandleEvent(ev);

                if (!norm || norm.open == null) return;

                if (DEBUG_WS) {
                    console.log("🕯 LIVE candle:", {
                        time: new Date(norm.time * 1000).toLocaleString(),
                        o: norm.open, h: norm.high, l: norm.low, c: norm.close
                    });
                }

                chartCtrl.onCandle(norm);
                return;
            }

            if (ev.type === "price") {
                chartCtrl.onPrice(ev);
                layers.onPriceUpdate?.(Number(ev.price));
                return;
            }

            // ===== STRATEGY EVENTS =====
            strategy.onEvent(ev);
        });

        // 🔁 replay
        fetch(`/api/strategy/${chatId}/${type}/replay`, { method: "POST" });
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

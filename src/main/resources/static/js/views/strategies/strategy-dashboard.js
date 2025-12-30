"use strict";

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer }   from "../../chart/layer-renderer.js";

import { ScalpingStrategy }    from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy }   from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard START");

    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Strategy Dashboard: context root not found");
        return;
    }

    const chatId = root.dataset.chatId;
    const type   = root.dataset.type;
    const symbol = root.dataset.symbol;

    if (!chatId || !type || !symbol) {
        console.error("❌ Strategy Dashboard: invalid context:", { chatId, type, symbol });
        return;
    }

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ Strategy Dashboard: #strategy-chart not found in DOM");
        return;
    }

    console.log("🧩 Context:", { chatId, type, symbol });

    const chartCtrl = new ChartController(container);
    const layers    = new LayerRenderer(chartCtrl.chart, chartCtrl.candles);

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

    // ————————————————————————————————
    // NORMALIZERS
    // ————————————————————————————————

    /**
     * Unified normalizer for WS candle events
     * @param {Object} ev
     * @returns {Object|null}
     */
    function normalizeCandleEvent(ev) {
        if (!ev || typeof ev !== "object") return null;

        let out = { ...ev };

        if (ev.kline && typeof ev.kline === "object") {
            const k = ev.kline;
            out = {
                open:   k.open,
                high:   k.high,
                low:    k.low,
                close:  k.close,
                volume: k.volume,
                time:   ev.time
            };
        }

        if (out.time != null) {
            out.time = Math.floor(Number(out.time) / 1000);
        }

        return out;
    }

    /**
     * Normalizer for REST candles
     * @param {Object} c
     * @returns {Object|null}
     */
    function normalizeRestCandle(c) {
        if (!c || typeof c !== "object") return null;
        if (c.time == null) return null;

        return {
            ...c,
            time: Math.floor(Number(c.time) / 1000)
        };
    }

    // ————————————————————————————————
    // FETCH REST SNAPSHOT
    // ————————————————————————————————

    const snapshotUrl =
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(type)}` +
        `&symbol=${encodeURIComponent(symbol)}`;

    console.log("📦 Fetching REST snapshot:", snapshotUrl);

    fetch(snapshotUrl)
        .then(r => (r.ok ? r.json() : Promise.reject(r)))
        .then(data => {
            if (!data) return;

            if (Array.isArray(data.candles) && data.candles.length > 0) {
                const candles = data.candles
                    .map(normalizeRestCandle)
                    .filter(Boolean);

                console.group("📦 [REST] Candles | Raw → Normalized");
                console.table(candles.map(c => ({
                    raw:    c.time,
                    iso:    new Date(c.time * 1000).toISOString(),
                    open:   c.open,
                    high:   c.high,
                    low:    c.low,
                    close:  c.close,
                    volume: c.volume
                })));
                console.groupEnd();

                chartCtrl.setHistory(candles);
            }

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
        .catch(err => console.error("❌ REST snapshot error", err));

    // ————————————————————————————————
    // WEBSOCKET (LIVE STREAM)
    // ————————————————————————————————

    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
        console.error("❌ Strategy Dashboard: SockJS or Stomp not found");
        return;
    }

    const socket = new SockJS("/ws/strategy");
    const stomp  = Stomp.over(socket);

    const DEBUG_WS = true;
    stomp.debug = DEBUG_WS ? s => console.log("🧵 STOMP:", s) : () => {};

    stomp.connect({}, () => {
        const topic = `/topic/strategy/${chatId}/${type}`;
        console.log("📡 SUBSCRIBING to LIVE topic:", topic);

        stomp.subscribe(topic, msg => {
            let ev;
            try {
                ev = JSON.parse(msg.body);
            } catch (parseError) {
                console.warn("⚠️ WS parse error:", parseError);
                return;
            }

            if (!ev || typeof ev !== "object" || !ev.type) return;
            if (ev.symbol && ev.symbol !== symbol) return;

            // —— MARKET EVENTS ——
            if (ev.type === "candle") {
                const candle = normalizeCandleEvent(ev);
                if (!candle || candle.open == null) return;

                if (DEBUG_WS) {
                    console.group("🕯 [WS] LIVE candle event");
                    console.log("Raw:", ev);
                    console.log("Normalized:", candle);
                    console.log("Date:", new Date(candle.time * 1000).toISOString());
                    console.groupEnd();
                }

                chartCtrl.onCandle(candle);
                return;
            }

            if (ev.type === "price") {
                chartCtrl.onPrice(ev);
                layers.onPriceUpdate?.(Number(ev.price));
                return;
            }

            // —— STRATEGY EVENTS ——
            strategy.onEvent(ev);
        });

        // trigger replay
        fetch(`/api/strategy/${chatId}/${type}/replay`, { method: "POST" });
    });

    // ————————————————————————————————
    // RESIZE + BAR SPACING
    // ————————————————————————————————

    window.addEventListener("resize", () => {
        if (container) {
            chartCtrl.chart.applyOptions({ width: container.clientWidth });
            chartCtrl.adjustBarSpacing();
        }
    });

    chartCtrl.adjustBarSpacing();

    console.log("📊 Strategy Dashboard INITIALIZED");
});

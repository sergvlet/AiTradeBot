"use strict";

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer }   from "../../chart/layer-renderer.js";

import { ScalpingStrategy }    from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy }   from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";

/**
 * Заглушка для неизвестных стратегий.
 */
class GenericStrategy {
    constructor({ layers, ctx }) {
        this.layers = layers;
        this.ctx = ctx;
    }
    onEvent(_) {}
    onCandleHistory(_) {}
}

document.addEventListener("DOMContentLoaded", () => {
    console.log("📊 Strategy Dashboard START");

    // =====================================================
    // CONTEXT
    // =====================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = String(root.dataset.chatId || "").trim();
    const type   = String(root.dataset.type || "").trim();
    const symbol = String(root.dataset.symbol || "").trim().toUpperCase();

    console.log("🧩 Context:", { chatId, type, symbol });

    const container = document.getElementById("strategy-chart");
    if (!container) {
        console.error("❌ #strategy-chart not found");
        return;
    }

    const symbolUpper = symbol;
    const symbolLower = symbol.toLowerCase();

    // =====================================================
    // CHART
    // =====================================================
    const chartCtrl = new ChartController(container);
    chartCtrl.symbol = symbol;

    // дефолт (позже может переписаться из REST)
    chartCtrl.timeframe = "1m";

    const layers = new LayerRenderer(chartCtrl.chart, chartCtrl.candles);
    layers.candlesData = chartCtrl.candlesData;
    chartCtrl.layerRenderer = layers;

    // =====================================================
    // STRATEGY OVERLAY
    // =====================================================
    const ctx = { chatId, type, symbol };
    let strategy;

    switch (type) {
        case "SCALPING":
        case "WINDOW_SCALPING":
            strategy = new ScalpingStrategy({ layers, ctx });
            break;

        case "FIBONACCI_GRID":
        case "FIBONACCI_RETRACE":
            strategy = new FibonacciStrategy({ layers, ctx });
            break;

        case "SMART_FUSION":
        case "HYBRID":
            strategy = new SmartFusionStrategy({ layers, ctx });
            break;

        default:
            console.warn("⚠ Unknown strategy type, fallback to Generic:", type);
            strategy = new GenericStrategy({ layers, ctx });
            break;
    }

    console.log("🧠 Strategy initialized:", type, strategy?.constructor?.name);

    // =====================================================
    // UI LAYERS CACHE
    // =====================================================

    const layerCacheKey = `uiLayers:${chatId}:${type}:${symbolUpper}`;

    let _layerState = null;
    let _persistTimer = null;

    function normalizeLayerState(raw) {
        if (!raw || typeof raw !== "object") return null;

        const L = (raw.layers && typeof raw.layers === "object") ? raw.layers : raw;
        const out = {};

        if (Array.isArray(L.levels)) out.levels = L.levels;
        if ("zone" in L) out.zone = L.zone ?? null;

        if ("tpSl" in L) out.tpSl = L.tpSl ?? null;
        else if ("tp_sl" in L) out.tpSl = L.tp_sl ?? null;

        if ("windowZone" in L) out.windowZone = L.windowZone ?? null;
        else if ("window_zone" in L) out.windowZone = L.window_zone ?? null;

        return out;
    }

    function applyLayerState(raw) {
        const state = normalizeLayerState(raw);
        if (!state) return;

        if ("levels" in state) {
            strategy.onEvent?.({ type: "levels", levels: state.levels || [] });
        }
        if ("zone" in state) {
            strategy.onEvent?.({ type: "zone", zone: state.zone });
        }
        if ("tpSl" in state) {
            strategy.onEvent?.({ type: "tp_sl", tpSl: state.tpSl });
        }
        if ("windowZone" in state) {
            strategy.onEvent?.({ type: "window_zone", windowZone: state.windowZone });
        }
    }

    function loadLayerStateFromLocalStorage() {
        try {
            const s = localStorage.getItem(layerCacheKey);
            if (!s) return null;
            const obj = JSON.parse(s);
            return normalizeLayerState(obj);
        } catch (_) {
            return null;
        }
    }

    function schedulePersistLayerState() {
        if (_persistTimer) return;
        _persistTimer = setTimeout(() => {
            _persistTimer = null;
            try {
                if (!_layerState) return;
                localStorage.setItem(layerCacheKey, JSON.stringify({ v: 1, ts: Date.now(), ..._layerState }));
            } catch (_) {
                // ignore
            }
        }, 250);
    }

    function updateLayerStateFromEvent(ev) {
        if (!ev || typeof ev.type !== "string") return;

        const t = String(ev.type || "").trim();
        if (!t) return;

        if (!_layerState) _layerState = {};

        switch (t) {
            case "levels":
                _layerState.levels = Array.isArray(ev.levels) ? ev.levels : [];
                schedulePersistLayerState();
                break;

            case "zone":
                _layerState.zone = ev.zone ?? null;
                schedulePersistLayerState();
                break;

            case "tp_sl":
                _layerState.tpSl = ev.tpSl ?? null;
                schedulePersistLayerState();
                break;

            case "window_zone":
                _layerState.windowZone = ev.windowZone ?? null;
                schedulePersistLayerState();
                break;

            default:
                break;
        }
    }

    const bootLayers = loadLayerStateFromLocalStorage();
    if (bootLayers) {
        _layerState = bootLayers;
        applyLayerState(bootLayers);
    }

    // =====================================================
    // REST SNAPSHOT
    // =====================================================
    const snapshotUrl =
        `/api/chart/strategy` +
        `?chatId=${encodeURIComponent(chatId)}` +
        `&type=${encodeURIComponent(type)}` +
        `&symbol=${encodeURIComponent(symbol)}`;

    fetch(snapshotUrl)
        .then(r => r.json())
        .then(data => {
            if (data?.timeframe) {
                const tf = String(data.timeframe).trim().toLowerCase();
                if (tf) chartCtrl.timeframe = tf;
            }

            if (Array.isArray(data?.candles)) {
                chartCtrl.setHistory(data.candles);
                strategy.onCandleHistory?.(chartCtrl.candlesData);
            }

            if (data?.layers) {
                strategy.onEvent?.({ type: "layers", layers: data.layers });

                const st = normalizeLayerState(data.layers);
                if (st) {
                    _layerState = st;
                    applyLayerState(st);
                    schedulePersistLayerState();
                }
            }
        })
        .catch(err => console.error("❌ REST snapshot error", err));

    // =====================================================
    // WS DEDUP
    // =====================================================
    const _wsDedup = new Map(); // key -> lastSeenMs

    function getNestedKline(ev) {
        return ev?.kline || ev?.k || ev?.data?.k || null;
    }

    function isCandleEvent(ev) {
        return (
            ev?.type === "candle" ||
            !!ev?.kline ||
            !!ev?.k ||
            !!ev?.data?.k
        );
    }

    function isPriceEvent(ev) {
        return ev?.type === "price" && ev?.price != null;
    }

    function isMarketEvent(ev) {
        return isCandleEvent(ev) || isPriceEvent(ev);
    }

    function eventSymbolUpper(ev) {
        const k = getNestedKline(ev);
        const s = ev?.symbol || k?.symbol || "";
        return String(s).trim().toUpperCase();
    }

    function eventTimeValue(ev) {
        const k = getNestedKline(ev);
        return ev?.time ?? k?.openTime ?? k?.t ?? k?.startTime ?? k?.T ?? "";
    }

    function normValue(v) {
        if (v === null || v === undefined) return "";
        return String(v).trim();
    }

    function candleDedupKey(ev) {
        const k = getNestedKline(ev) || {};
        const sym = eventSymbolUpper(ev);
        const tf  = normValue(ev?.timeframe || k?.timeframe || k?.i || "");
        const st  = normValue(ev?.strategyType || type || "");
        const t   = normValue(eventTimeValue(ev));

        const o = normValue(k.open ?? k.o ?? ev?.open);
        const h = normValue(k.high ?? k.h ?? ev?.high);
        const l = normValue(k.low  ?? k.l ?? ev?.low);
        const c = normValue(k.close ?? k.c ?? ev?.close);

        return `candle|${st}|${sym}|${tf}|${t}|${o}|${h}|${l}|${c}`;
    }

    function priceDedupKey(ev) {
        const sym = eventSymbolUpper(ev);
        const tf  = normValue(ev?.timeframe || "");
        const st  = normValue(ev?.strategyType || type || "");
        const t   = normValue(eventTimeValue(ev));
        const p   = normValue(ev?.price);
        return `price|${st}|${sym}|${tf}|${t}|${p}`;
    }

    function genericDedupKey(ev) {
        const k = getNestedKline(ev);
        const t = normValue(eventTimeValue(ev));
        const sym = eventSymbolUpper(ev);
        const tf  = normValue(ev?.timeframe || "");
        const st  = normValue(ev?.strategyType || type || "");
        const et  = normValue(ev?.type || "");

        const extra =
            normValue(ev?.state) ||
            normValue(ev?.metric) ||
            normValue(ev?.price) ||
            normValue(ev?.signal?.name) ||
            normValue(k?.close ?? k?.c);

        return `${et}|${st}|${sym}|${tf}|${t}|${extra}`;
    }

    function wsDedupMeta(ev) {
        if (isCandleEvent(ev)) {
            return {
                key: candleDedupKey(ev),
                ttlMs: 180
            };
        }

        if (isPriceEvent(ev)) {
            return {
                key: priceDedupKey(ev),
                ttlMs: 120
            };
        }

        return {
            key: genericDedupKey(ev),
            ttlMs: 1200
        };
    }

    function wsSeenRecently(meta) {
        if (!meta?.key) return false;

        const now = Date.now();
        const last = _wsDedup.get(meta.key);
        if (last && (now - last) < meta.ttlMs) return true;

        _wsDedup.set(meta.key, now);

        if (_wsDedup.size > 4000) {
            for (const [k, v] of _wsDedup) {
                if ((now - v) > 15_000) _wsDedup.delete(k);
            }
        }

        return false;
    }

    // =====================================================
    // WEBSOCKET (STOMP)
    // =====================================================
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
        console.error("❌ SockJS / Stomp not loaded");
        return;
    }

    let stomp = null;
    let socket = null;
    let reconnectAttempt = 0;
    let reconnectTimer = null;

    const destinations = [
        `/topic/strategy/${chatId}/${type}/${symbolUpper}`,
        `/topic/strategy/${chatId}/${type}/${symbolLower}`,
        `/topic/strategy/${chatId}/${type}`
    ];

    let wsCount = 0;
    let lastLogAt = 0;

    function cleanupWs() {
        try { if (reconnectTimer) clearTimeout(reconnectTimer); } catch (_) {}
        reconnectTimer = null;

        try {
            if (stomp && stomp.connected) stomp.disconnect(() => {});
        } catch (_) {}

        stomp = null;

        try { if (socket) socket.close(); } catch (_) {}
        socket = null;
    }

    function scheduleReconnect(reason) {
        cleanupWs();

        reconnectAttempt = Math.min(10, reconnectAttempt + 1);
        const delay = Math.min(15_000, 500 * Math.pow(2, reconnectAttempt - 1));

        console.warn(`⚠ WS reconnect scheduled in ${delay}ms (attempt=${reconnectAttempt}) reason=${reason}`);

        reconnectTimer = setTimeout(() => {
            connectWs();
        }, delay);
    }

    function connectWs() {
        cleanupWs();

        socket = new SockJS("/ws/strategy/");
        stomp = Stomp.over(socket);
        stomp.debug = null;

        socket.onclose = () => scheduleReconnect("socket_close");
        socket.onerror = () => scheduleReconnect("socket_error");

        stomp.connect(
            {},
            () => {
                reconnectAttempt = 0;
                console.log("✅ STOMP CONNECTED");

                destinations.forEach(dest => {
                    stomp.subscribe(dest, msg => {
                        let ev;
                        try {
                            ev = JSON.parse(msg.body);
                        } catch {
                            return;
                        }

                        const meta = wsDedupMeta(ev);
                        if (wsSeenRecently(meta)) return;

                        wsCount++;
                        const now = Date.now();
                        if (now - lastLogAt > 3000) {
                            lastLogAt = now;
                            console.log(`📡 WS IN (#${wsCount}) from ${dest}:`, msg.body?.slice(0, 220));
                        }

                        const evSym = eventSymbolUpper(ev);
                        if (evSym && evSym !== symbolUpper) return;

                        if (isMarketEvent(ev)) {
                            chartCtrl.onWsMessage(ev);
                        }

                        strategy.onEvent?.(ev);
                        updateLayerStateFromEvent(ev);

                        if ((type === "SCALPING" || type === "WINDOW_SCALPING") && isCandleEvent(ev)) {
                            strategy.onCandleHistory?.(chartCtrl.candlesData);
                        }
                    });

                    console.log("✅ SUBSCRIBED", dest);
                });

                fetch(`/api/strategy/${chatId}/${type}/replay`, { method: "POST" })
                    .catch(() => {});
            },
            (err) => {
                console.warn("❌ STOMP CONNECT ERROR", err);
                scheduleReconnect("stomp_connect_error");
            }
        );
    }

    connectWs();

    // =====================================================
    // RESIZE
    // =====================================================
    window.addEventListener("resize", () => {
        try {
            chartCtrl.chart.applyOptions({ width: container.clientWidth });
            chartCtrl.adjustBarSpacing();
        } catch (_) {}
    });

    chartCtrl.adjustBarSpacing();
    console.log("📊 Strategy Dashboard INITIALIZED");
});
"use strict";

import { ChartController } from "../../chart/chart-controller.js";
import { LayerRenderer }   from "../../chart/layer-renderer.js";

// ✅ Стратегии-оверлеи (те, что реально существуют у тебя)
import { ScalpingStrategy }    from "../../strategies/scalping.strategy.js";
import { FibonacciStrategy }   from "../../strategies/fibonacci.strategy.js";
import { SmartFusionStrategy } from "../../strategies/smartfusion.strategy.js";

/**
 * ✅ Пустая стратегия-заглушка для всех остальных типов:
 * график работает, WS работает, но специфичных слоёв нет.
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

    // =========================================================================
    // CONTEXT
    // =========================================================================
    const root = document.querySelector("[data-chat-id][data-type][data-symbol]");
    if (!root) {
        console.error("❌ Context root not found");
        return;
    }

    const chatId = root.dataset.chatId;
    const type   = (root.dataset.type || "").trim();
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

    // ✅ ВАЖНО: держим ссылку на один и тот же массив
    layers.candlesData = chartCtrl.candlesData;

    // если нужно
    chartCtrl.layerRenderer = layers;

    // =========================================================================
    // STRATEGY (все типы StrategyType)
    // =========================================================================
    const ctx = { chatId, type, symbol };
    let strategy;

    switch (type) {

        // ===================== III) SCALPING =====================
        case "SCALPING":
        case "WINDOW_SCALPING":
            strategy = new ScalpingStrategy({ layers, ctx });
            break;

        // ===================== VI) GRIDS =====================
        case "FIBONACCI_GRID":
        case "FIBONACCI_RETRACE":
            strategy = new FibonacciStrategy({ layers, ctx });
            break;

        case "GRID":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== VIII) AI =====================
        case "SMART_FUSION":
        case "HYBRID":
            strategy = new SmartFusionStrategy({ layers, ctx });
            break;

        case "RL_AGENT":
        case "ML_CLASSIFICATION":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== I) MOMENTUM / TREND =====================
        case "MOMENTUM":
        case "TREND":
        case "TREND_FOLLOWING":
        case "EMA_CROSSOVER":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== II) MEAN REVERSION / RSI =====================
        case "MEAN_REVERSION":
        case "RSI_OBOS":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== IV) BREAKOUT =====================
        case "BREAKOUT":
        case "VOLATILITY_BREAKOUT":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== V) LEVELS / STRUCTURE =====================
        case "SUPPORT_RESISTANCE":
        case "PRICE_ACTION":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== VII) VOLUME =====================
        case "VOLUME_PROFILE":
        case "VWAP":
        case "ORDER_FLOW":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        // ===================== DCA / GLOBAL =====================
        case "DCA":
        case "GLOBAL":
            strategy = new GenericStrategy({ layers, ctx });
            break;

        default:
            console.warn("⚠ Unknown strategy type, fallback to Generic:", type);
            strategy = new GenericStrategy({ layers, ctx });
            break;
    }

    console.log("🧠 Strategy initialized:", type, strategy?.constructor?.name);

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
            // 1) история → в график
            if (Array.isArray(data?.candles)) {
                chartCtrl.setHistory(data.candles);
                strategy.onCandleHistory?.(chartCtrl.candlesData);
            }

            // 2) слои (если бек прислал)
            if (data?.layers) {
                strategy.onEvent?.({ type: "layers", layers: data.layers });

                if ((type === "SCALPING" || type === "WINDOW_SCALPING") && data.layers.windowZone) {
                    strategy.onEvent?.({
                        type: "window_zone",
                        windowZone: data.layers.windowZone
                    });
                }
            }

            // 3) если бек вернул timeframe — можно применить (опционально)
            if (data?.timeframe) {
                chartCtrl.timeframe = String(data.timeframe).toLowerCase();
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

        const symbolUpper = symbol;               // BTCUSDT
        const symbolLower = symbol.toLowerCase(); // btcusdt

        // ✅ подписываемся на оба варианта (topic может быть регистрозависим)
        const destinations = [
            `/topic/strategy/${chatId}/${type}/${symbolUpper}`,
            `/topic/strategy/${chatId}/${type}/${symbolLower}`,
            `/topic/strategy/${chatId}/${type}`,
            `/topic/strategy/${chatId}`,
        ];

        let wsCount = 0;
        let lastLogAt = 0;

        destinations.forEach(dest => {
            stomp.subscribe(dest, msg => {
                wsCount++;

                // логируем редко (антиспам)
                const now = Date.now();
                if (now - lastLogAt > 3000) {
                    lastLogAt = now;
                    console.log(`📡 WS IN (#${wsCount}) from ${dest}:`, msg.body?.slice(0, 200));
                }

                let ev;
                try { ev = JSON.parse(msg.body); } catch { return; }

                // фильтр по symbol (если есть) — не мешаем другим вкладкам
                const evSymbol = (ev?.symbol || "").trim().toUpperCase();
                if (evSymbol && evSymbol !== symbolUpper) return;

                // 🔥 ЕДИНСТВЕННЫЙ ВХОД В ГРАФИК
                chartCtrl.onWsMessage(ev);

                // стратегия получает ВСЁ
                strategy.onEvent?.(ev);

                // обновление зон по “свечным” сообщениям
                const looksLikeCandle =
                    ev?.type === "candle" ||
                    !!ev?.kline ||
                    !!ev?.k ||
                    !!ev?.data?.k;

                if ((type === "SCALPING" || type === "WINDOW_SCALPING") && looksLikeCandle) {
                    strategy.onCandleHistory?.(chartCtrl.candlesData);
                }
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

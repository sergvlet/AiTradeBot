"use strict";

console.log("🚀 strategy-init.js loaded");

document.addEventListener("DOMContentLoaded", () => {

    const root = document.getElementById("strategy-dashboard");
    if (!root) return;  // если дашборд отсутствует — тихий выход

    // === ПАРАМЕТРЫ СТРАТЕГИИ ===
    const chatId    = Number(root.dataset.chatId || 0);
    const symbol    = root.dataset.symbol    || "BTCUSDT";
    const exchange  = root.dataset.exchange  || "";
    const network   = root.dataset.network   || "";
    const timeframe = root.dataset.timeframe || "1m";
    const type      = root.dataset.type      || "";

    // ============================================================
    // 1) ИНИЦИАЛИЗАЦИЯ ГРАФИКА
    // ============================================================
    if (window.AiStrategyChart) {
        try {
            // Инициализация графика
            window.AiStrategyChart.initChart();

            // История свечей
            window.AiStrategyChart.loadFullChart(chatId, symbol, timeframe);

            // ⛔ Старый WebSocket отключён → STOMP слушает живые данные
            // window.AiStrategyChart.subscribeLive(symbol, timeframe);

            // PNG экспорт
            window.AiStrategyChart.initExportPng?.();

            // Кнопки Start/Stop
            window.AiStrategyChart.initStartStopButtons?.();

        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyChart", e);
        }
    }

    // ============================================================
    // 2) КНОПКИ / СЕЛЕКТОРЫ / УПРАВЛЕНИЕ
    // ============================================================
    if (window.AiStrategyControls) {
        try {
            window.AiStrategyControls.initTimeframeSelector?.(
                chatId, symbol, exchange, network, timeframe
            );

            window.AiStrategyControls.initStartStopButtons?.(chatId, type);

        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyControls", e);
        }
    }

    // ============================================================
    // 3) ТАБЛИЦА СДЕЛОК
    // ============================================================
    if (window.AiStrategyTable?.init) {
        try {
            window.AiStrategyTable.init();
        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyTable", e);
        }
    }

    console.log("✅ strategy-init: initialized");
});

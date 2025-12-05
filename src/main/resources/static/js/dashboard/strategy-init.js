"use strict";

console.log("🚀 strategy-init.js loaded");

document.addEventListener("DOMContentLoaded", () => {

    const root = document.getElementById("strategy-dashboard");
    if (!root) {
        return; // тихий выход
    }

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
            // init
            window.AiStrategyChart.initChart();

            // load full chart (исправленный вызов — БЕЗ 4-го параметра)
            window.AiStrategyChart.loadFullChart(chatId, symbol, timeframe);

            // live updates
            window.AiStrategyChart.subscribeLive(symbol, timeframe);

            // PNG export
            if (window.AiStrategyChart.initExportPng) {
                window.AiStrategyChart.initExportPng();
            }

            // кнопки старт/стоп из AiStrategyChart — если есть
            if (window.AiStrategyChart.initStartStopButtons) {
                window.AiStrategyChart.initStartStopButtons();
            }

        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyChart", e);
        }
    }

    // ============================================================
    // 2) КНОПКИ, СЕЛЕКТОРЫ, УПРАВЛЕНИЕ
    // ============================================================
    if (window.AiStrategyControls) {
        try {
            if (window.AiStrategyControls.initTimeframeSelector) {
                window.AiStrategyControls.initTimeframeSelector(
                    chatId, symbol, exchange, network, timeframe
                );
            }

            if (window.AiStrategyControls.initStartStopButtons) {
                window.AiStrategyControls.initStartStopButtons(chatId, type);
            }

        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyControls", e);
        }
    }

    // ============================================================
    // 3) ТАБЛИЦА СДЕЛОК
    // ============================================================
    if (window.AiStrategyTable && window.AiStrategyTable.init) {
        try {
            window.AiStrategyTable.init();
        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyTable", e);
        }
    }

    console.log("✅ strategy-init: initialized");
});

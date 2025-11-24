"use strict";

console.log("🚀 strategy-init.js loaded");

document.addEventListener("DOMContentLoaded", () => {

    const root = document.getElementById("strategy-dashboard");
    if (!root) {
        // тихий выход — никаких ворнингов
        return;
    }

    // === Все нужные параметры ===
    const chatId    = Number(root.dataset.chatId || 0);
    const symbol    = root.dataset.symbol    || "BTCUSDT";
    const exchange  = root.dataset.exchange  || "";
    const network   = root.dataset.network   || "";
    const timeframe = root.dataset.timeframe || "1m";
    const type      = root.dataset.type      || "";

    // ============================
    // 1) ГРАФИК
    // ============================
    if (window.AiStrategyChart) {
        try {
            window.AiStrategyChart.initChart();
            window.AiStrategyChart.loadFullChart(chatId, symbol, timeframe, { initial: true });
            window.AiStrategyChart.subscribeLive(symbol, timeframe);

            if (window.AiStrategyChart.initExportPng) {
                window.AiStrategyChart.initExportPng();
            }
        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyChart", e);
        }
    }

    // ============================
    // 2) КНОПКИ УПРАВЛЕНИЯ
    // ============================
    if (window.AiStrategyControls) {
        try {
            window.AiStrategyControls.initTimeframeSelector(chatId, symbol, exchange, network, timeframe);
            window.AiStrategyControls.initStartStopButtons(chatId, type);
        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyControls", e);
        }
    }

    // ============================
    // 3) ТАБЛИЦА
    // ============================
    if (window.AiStrategyTable && window.AiStrategyTable.init) {
        try {
            window.AiStrategyTable.init();
        } catch (e) {
            console.error("❌ strategy-init: ошибка в AiStrategyTable", e);
        }
    }

    console.log("✅ strategy-init: initialized");
});

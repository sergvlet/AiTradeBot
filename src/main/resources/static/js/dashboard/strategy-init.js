"use strict";

console.log("🚀 strategy-init.js loaded");

document.addEventListener("DOMContentLoaded", () => {

    const root = document.getElementById("strategy-dashboard");
    if (!root) {
        console.warn("strategy-init: #strategy-dashboard not found");
        return;
    }

    const chatId    = Number(root.dataset.chatId);
    const symbol    = root.dataset.symbol;
    const exchange  = root.dataset.exchange;
    const network   = root.dataset.network;
    const timeframe = root.dataset.timeframe;
    const type      = root.dataset.type;

    // === 1. Chart ===
    window.AiStrategyChart.initChart();
    window.AiStrategyChart.loadFullChart(chatId, symbol, timeframe, { initial: true });
    window.AiStrategyChart.subscribeLive(symbol, timeframe);
    window.AiStrategyChart.initExportPng();

    // === 2. Controls ===
    window.AiStrategyControls.initTimeframeSelector(chatId, symbol, exchange, network, timeframe);
    window.AiStrategyControls.initStartStopButtons(chatId, type);

    // === 3. Table ===
    if (window.AiStrategyTable && window.AiStrategyTable.init) {
        window.AiStrategyTable.init();
    }
});

"use strict";

console.log("⚙ strategy-controls.js loaded");

(function () {

    function initTimeframeSelector(chatId, symbol, exchange, network, timeframe) {
        const sel = document.getElementById("timeframe-select");
        if (!sel) return;

        fetch(`/api/exchange/timeframes?exchange=${exchange}&networkType=${network}`)
            .then(r => r.json())
            .then(arr => {
                sel.innerHTML = "";
                arr.forEach(tf => {
                    const o = document.createElement("option");
                    o.value = tf;
                    o.textContent = tf;
                    if (tf === timeframe) o.selected = true;
                    sel.appendChild(o);
                });

                sel.addEventListener("change", () => {
                    const tf = sel.value;

                    window.AiStrategyChart.loadFullChart(chatId, symbol, tf, { initial: true });
                    window.AiStrategyChart.subscribeLive(symbol, tf);
                });
            });
    }

    function initStartStopButtons(chatId, type) {
        const bStart = document.getElementById("btn-start");
        const bStop  = document.getElementById("btn-stop");

        if (bStart) {
            bStart.addEventListener("click", () => {
                fetch(`/api/strategy/start?chatId=${chatId}&type=${type}`, { method: "POST" });
            });
        }

        if (bStop) {
            bStop.addEventListener("click", () => {
                fetch(`/api/strategy/stop?chatId=${chatId}&type=${type}`, { method: "POST" });
            });
        }
    }

    window.AiStrategyControls = {
        initTimeframeSelector,
        initStartStopButtons
    };

})();

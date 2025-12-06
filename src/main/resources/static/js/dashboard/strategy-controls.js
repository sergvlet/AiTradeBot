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

                    if (window.AiStrategyChart) {
                        window.AiStrategyChart.loadFullChart(chatId, symbol, tf, { initial: true });
                        window.AiStrategyChart.subscribeLive(symbol, tf);
                    }

                    // Сохраняем актуальный tf в data-атрибуте
                    const root = document.getElementById("strategy-dashboard");
                    if (root) {
                        root.dataset.timeframe = tf;
                    }
                });
            });
    }

    /**
     * Привязка кнопок Запустить/Остановить к /api/strategy/toggle
     */
    function initStartStopButtons(chatId, type /* передаём, но можем достать и из data-* */) {

        const root = document.getElementById("strategy-dashboard");
        if (!root) return;

        // подстраховываемся — берём из data-* если что-то не передали
        const safeChatId = chatId || Number(root.dataset.chatId || 0);
        const safeType   = type   || root.dataset.type || "";

        const symbol = root.dataset.symbol || "BTCUSDT";

        const btnStart = document.getElementById("btn-start");
        const btnStop  = document.getElementById("btn-stop");

        const pill   = document.getElementById("strategy-status-pill");
        const dot    = document.getElementById("strategy-status-dot");
        const label  = document.getElementById("strategy-status-label");

        async function callToggle(desiredActive) {
            const tfSelect = document.getElementById("timeframe-select");
            const tf = (tfSelect && tfSelect.value) ||
                root.dataset.timeframe ||
                "15m";

            const url = `/api/strategy/toggle` +
                `?chatId=${encodeURIComponent(safeChatId)}` +
                `&type=${encodeURIComponent(safeType)}` +
                `&symbol=${encodeURIComponent(symbol)}` +
                `&timeframe=${encodeURIComponent(tf)}`;

            const controller = new AbortController();

            try {
                // блокируем обе кнопки
                if (btnStart) btnStart.disabled = true;
                if (btnStop)  btnStop.disabled  = true;

                if (label) {
                    label.textContent = desiredActive ? "Запускаем..." : "Останавливаем...";
                }

                const resp = await fetch(url, {
                    method: "POST",
                    signal: controller.signal
                });

                const data = await resp.json();
                if (!resp.ok || !data.success) {
                    throw new Error(data.message || "Ошибка переключения стратегии");
                }

                const isActive = !!data.active;

                // синхронизируем флаг в chart.js (если нужен)
                if (window.setStrategyRunning) {
                    window.setStrategyRunning(isActive);
                }

                // обновляем data-timeframe если бэк что-то вернул
                if (data.info && data.info.timeframe && root) {
                    root.dataset.timeframe = data.info.timeframe;
                    if (tfSelect) {
                        tfSelect.value = data.info.timeframe;
                    }
                }

                // переключаем видимость кнопок
                if (btnStart && btnStop) {
                    btnStart.classList.toggle("d-none", isActive);
                    btnStop.classList.toggle("d-none", !isActive);
                }

                // статусная плашка
                if (pill) {
                    pill.classList.toggle("bg-success-subtle", isActive);
                    pill.classList.toggle("text-success-emphasis", isActive);
                    pill.classList.toggle("bg-danger-subtle", !isActive);
                    pill.classList.toggle("text-danger-emphasis", !isActive);
                }
                if (dot) {
                    dot.classList.toggle("status-dot-running", isActive);
                    dot.classList.toggle("status-dot-stopped", !isActive);
                }
                if (label) {
                    label.textContent = isActive ? "Работает" : "Остановлена";
                }

                if (window.showToast) {
                    window.showToast(data.message || (isActive
                        ? "Стратегия запущена"
                        : "Стратегия остановлена"), isActive ? "success" : "warning");
                }

            } catch (e) {
                console.error("❌ toggle strategy error", e);
                if (window.showToast) {
                    window.showToast("Ошибка переключения стратегии", "danger");
                }
            } finally {
                if (btnStart) btnStart.disabled = false;
                if (btnStop)  btnStop.disabled  = false;
            }
        }

        if (btnStart) {
            btnStart.addEventListener("click", (e) => {
                e.preventDefault();
                callToggle(true);
            });
        }

        if (btnStop) {
            btnStop.addEventListener("click", (e) => {
                e.preventDefault();
                callToggle(false);
            });
        }
    }

    window.AiStrategyControls = {
        initTimeframeSelector,
        initStartStopButtons
    };

})();

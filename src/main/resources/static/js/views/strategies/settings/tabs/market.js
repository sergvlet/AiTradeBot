"use strict";

window.SettingsTabMarket = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const exchangeSelect = document.getElementById("exchangeSelect");
        const networkSelect = document.getElementById("networkSelect");

        const marketSearch = document.getElementById("marketSearch");
        const marketResults = document.getElementById("marketResults");
        const symbolInput = document.getElementById("symbolInput");

        if (!marketSearch || !marketResults) return;

        let timer = null;

        async function loadMarketSearch(query) {
            if (!query || query.trim().length < 1) {
                marketResults.innerHTML =
                    `<div class="text-center text-secondary p-3">Введите поисковый запрос…</div>`;
                return;
            }

            const ex = exchangeSelect?.value;
            const nt = networkSelect?.value;

            if (!ex || !nt) return;

            const url =
                `/api/market/search` +
                `?exchange=${encodeURIComponent(ex)}` +
                `&network=${encodeURIComponent(nt)}` +
                `&q=${encodeURIComponent(query)}`;

            try {
                marketResults.innerHTML =
                    `<div class="text-center text-info p-3">Поиск…</div>`;

                const res = await fetch(url);
                if (!res.ok) throw new Error(res.status);

                const list = await res.json();

                if (!Array.isArray(list) || list.length === 0) {
                    marketResults.innerHTML =
                        `<div class="text-center text-secondary p-3">Ничего не найдено</div>`;
                    return;
                }

                let html = `
          <table class="table table-dark table-sm table-striped mb-0">
            <thead>
              <tr>
                <th>Пара</th>
                <th>Цена</th>
                <th>24h %</th>
                <th>Объём</th>
                <th></th>
              </tr>
            </thead>
            <tbody>`;

                list.forEach(s => {
                    const pct = Number(s.changePct || 0);
                    const pctColor = pct >= 0 ? "text-success" : "text-danger";

                    html += `
            <tr>
              <td>${s.symbol}</td>
              <td>${Number(s.price).toFixed(6)}</td>
              <td class="${pctColor}">${pct.toFixed(2)}%</td>
              <td>${Number(s.volume).toFixed(2)}</td>
              <td>
                <button class="btn btn-sm btn-primary js-select-symbol"
                        data-symbol="${s.symbol}">
                  Выбрать
                </button>
              </td>
            </tr>`;
                });

                html += `</tbody></table>`;
                marketResults.innerHTML = html;

                marketResults.querySelectorAll(".js-select-symbol").forEach(btn => {
                    btn.addEventListener("click", () => {
                        if (symbolInput) symbolInput.value = btn.dataset.symbol;
                        window.showToast?.(`Выбрана пара: ${btn.dataset.symbol}`, true);
                    });
                });

            } catch (e) {
                console.error("Ошибка поиска монет:", e);
                marketResults.innerHTML =
                    `<div class="text-danger text-center p-3">Ошибка загрузки данных</div>`;
            }
        }

        marketSearch.addEventListener("input", () => {
            clearTimeout(timer);
            timer = setTimeout(() => loadMarketSearch(marketSearch.value.trim()), 250);
        });
    }

    return { init };
})();

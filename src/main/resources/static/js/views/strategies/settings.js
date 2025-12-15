"use strict";

// js/views/strategies/settings.js

document.addEventListener("DOMContentLoaded", () => {

    // -------------------------------------------------------------
    //  БАЗОВЫЕ ЭЛЕМЕНТЫ / ДАННЫЕ СО СТРАНИЦЫ
    // -------------------------------------------------------------

    const root = document.querySelector(".strategy-settings-page");

    // chatId и type берём надёжно
    const chatId =
        root?.dataset.chatId ||
        document.querySelector("input[name='chatId']")?.value ||
        null;

    let type =
        root?.dataset.type ||
        document.querySelector("h2 span:last-child")?.innerText ||
        null;

    if (type) type = type.trim();

    if (!chatId || !type) {
        console.warn("settings.js: chatId или type не определены", { chatId, type });
    }

    // ВАЖНО: контроллер живёт по /strategies/{type}/config
    const baseUrl = `/strategies/${encodeURIComponent(type)}/config`;

    const tabs  = document.querySelectorAll(".tab-btn");
    const panes = document.querySelectorAll(".tab-pane");

    const exSel = document.getElementById("exchangeSelect");
    const ntSel = document.getElementById("networkSelect");

    const commissionInput = document.querySelector("input[name='commissionPct']");
    const refreshBtn = document.getElementById("refreshFeeBtn");

    const marketSearch  = document.getElementById("marketSearch");
    const marketResults = document.getElementById("marketResults");
    const symbolInput   = document.getElementById("symbolInput");


    // -------------------------------------------------------------
    //  ВКЛАДКИ (TAB'Ы) — хранение активной вкладки
    // -------------------------------------------------------------

    const TAB_KEY = "strategy_settings_active_tab";

    function activateTab(tabId) {
        if (!tabId) return;

        tabs.forEach(t => t.classList.remove("active"));
        panes.forEach(p => p.classList.remove("active"));

        const btn  = document.querySelector(`.tab-btn[data-tab='${tabId}']`);
        const pane = document.getElementById(tabId);

        if (btn)  btn.classList.add("active");
        if (pane) pane.classList.add("active");

        localStorage.setItem(TAB_KEY, tabId);
    }

    // восстановить вкладку из localStorage или по умолчанию "network"
    const savedTab = localStorage.getItem(TAB_KEY);
    if (savedTab && document.getElementById(savedTab)) {
        activateTab(savedTab);
    } else {
        activateTab("network");
    }

    tabs.forEach(btn => {
        btn.addEventListener("click", () => {
            const tabId = btn.dataset.tab;
            activateTab(tabId);
        });
    });


    // -------------------------------------------------------------
    //  ПЕРЕЗАГРУЗКА ПРИ СМЕНЕ БИРЖИ / СЕТИ
    //  (чтобы перетащить diagnostics, баланс, таймфреймы)
    // -------------------------------------------------------------

    function reloadWithParams() {
        if (!exSel || !ntSel || !chatId || !type) return;

        const ex  = exSel.value;
        const nt  = ntSel.value;
        const tab = localStorage.getItem(TAB_KEY) || "network";

        const url =
            `${baseUrl}?chatId=${encodeURIComponent(chatId)}` +
            `&exchange=${encodeURIComponent(ex)}` +
            `&network=${encodeURIComponent(nt)}` +
            `&tab=${encodeURIComponent(tab)}`;

        window.location.href = url;
    }

    if (exSel) exSel.addEventListener("change", reloadWithParams);
    if (ntSel) ntSel.addEventListener("change", reloadWithParams);


    // -------------------------------------------------------------
    //  ЗАГРУЗКА РЕАЛЬНОЙ КОМИССИИ (RealFee)
    // -------------------------------------------------------------

    async function loadRealFee() {
        if (!exSel || !ntSel || !chatId || !type) return;

        const exchange = exSel.value;
        const network  = ntSel.value;

        const url =
            `${baseUrl}/real-fee` +
            `?chatId=${encodeURIComponent(chatId)}` +
            `&exchange=${encodeURIComponent(exchange)}` +
            `&network=${encodeURIComponent(network)}`;

        try {
            const res = await fetch(url);
            if (!res.ok) {
                throw new Error(res.status);
            }

            const data = await res.json();

            if (commissionInput && typeof data.fee !== "undefined") {
                commissionInput.value = Number(data.fee).toFixed(3);
            }

            if (data.ok && window.showToast) {
                const vip = data.vipLevel ?? "?";
                const bnb = data.bnb ? "да" : "нет";
                showToast(`Комиссия обновлена (VIP${vip}, BNB: ${bnb})`, true);
            }

        } catch (e) {
            console.error("Ошибка загрузки комиссии:", e);
            if (window.showToast) {
                showToast("Ошибка загрузки комиссии", false);
            }
        }
    }

    // автозагрузка комиссии, если поле есть
    if (commissionInput) {
        loadRealFee();
    }

    // кнопка обновить комиссию
    if (refreshBtn) {
        refreshBtn.addEventListener("click", async () => {

            refreshBtn.disabled = true;
            const oldText = refreshBtn.innerText;
            refreshBtn.innerText = "Обновляем…";

            try {
                await loadRealFee();
            } finally {
                refreshBtn.disabled = false;
                refreshBtn.innerText = oldText;
            }
        });
    }


    // -------------------------------------------------------------
    //  ПОИСК МОНЕТ (MARKET SEARCH)
    // -------------------------------------------------------------

    let searchTimer = null;

    async function loadMarketSearch(query) {
        if (!marketResults || !exSel || !ntSel) return;

        if (!query || query.trim().length < 1) {
            marketResults.innerHTML = `
                <div class="text-center text-secondary p-3">
                    Введите поисковый запрос…
                </div>`;
            return;
        }

        const ex = exSel.value;
        const nt = ntSel.value;

        const url =
            `/api/market/search` +
            `?exchange=${encodeURIComponent(ex)}` +
            `&network=${encodeURIComponent(nt)}` +
            `&q=${encodeURIComponent(query)}`;

        try {
            marketResults.innerHTML = `
                <div class="text-center text-info p-3">
                    Поиск…
                </div>`;

            const res  = await fetch(url);
            if (!res.ok) {
                throw new Error(res.status);
            }

            const list = await res.json();

            if (!Array.isArray(list) || list.length === 0) {
                marketResults.innerHTML = `
                    <div class="text-center text-secondary p-3">
                        Ничего не найдено
                    </div>`;
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
                            <button class="btn btn-sm btn-primary select-symbol"
                                    data-symbol="${s.symbol}">
                                Выбрать
                            </button>
                        </td>
                    </tr>`;
            });

            html += "</tbody></table>";

            marketResults.innerHTML = html;

            // обработка кнопок "Выбрать"
            document.querySelectorAll(".select-symbol").forEach(btn => {
                btn.addEventListener("click", () => {
                    const symbol = btn.dataset.symbol;
                    if (symbolInput) {
                        symbolInput.value = symbol;
                    }
                    if (window.showToast) {
                        showToast(`Выбрана пара: ${symbol}`, true);
                    }
                });
            });

        } catch (e) {
            console.error("Ошибка поиска монет:", e);
            marketResults.innerHTML =
                `<div class="text-danger text-center p-3">
                    Ошибка загрузки данных
                 </div>`;
        }
    }

    if (marketSearch) {
        marketSearch.addEventListener("input", () => {
            const q = marketSearch.value.trim();
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => loadMarketSearch(q), 250);
        });
    }

});

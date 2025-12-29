"use strict";

// js/views/strategies/settings.js

document.addEventListener("DOMContentLoaded", () => {

    // -------------------------------------------------------------
    //  БАЗОВЫЕ ЭЛЕМЕНТЫ / ДАННЫЕ СО СТРАНИЦЫ
    // -------------------------------------------------------------

    const root = document.querySelector(".strategy-settings-page");

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
    //  TAB'Ы
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

    const savedTab = localStorage.getItem(TAB_KEY);
    if (savedTab && document.getElementById(savedTab)) {
        activateTab(savedTab);
    } else {
        activateTab("network");
    }

    tabs.forEach(btn => {
        btn.addEventListener("click", () => {
            activateTab(btn.dataset.tab);
        });
    });

    // -------------------------------------------------------------
    //  RELOAD при смене биржи / сети
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
    //  MARKET SEARCH (без изменений)
    // -------------------------------------------------------------

    let searchTimer = null;

    async function loadMarketSearch(query) {
        if (!marketResults || !exSel || !ntSel) return;

        if (!query || query.trim().length < 1) {
            marketResults.innerHTML =
                `<div class="text-center text-secondary p-3">
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
            marketResults.innerHTML =
                `<div class="text-center text-info p-3">Поиск…</div>`;

            const res  = await fetch(url);
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
                            <button class="btn btn-sm btn-primary select-symbol"
                                    data-symbol="${s.symbol}">
                                Выбрать
                            </button>
                        </td>
                    </tr>`;
            });

            html += "</tbody></table>";
            marketResults.innerHTML = html;

            document.querySelectorAll(".select-symbol").forEach(btn => {
                btn.addEventListener("click", () => {
                    symbolInput && (symbolInput.value = btn.dataset.symbol);
                    window.showToast?.(`Выбрана пара: ${btn.dataset.symbol}`, true);
                });
            });

        } catch (e) {
            console.error("Ошибка поиска монет:", e);
            marketResults.innerHTML =
                `<div class="text-danger text-center p-3">Ошибка загрузки данных</div>`;
        }
    }

    if (marketSearch) {
        marketSearch.addEventListener("input", () => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(
                () => loadMarketSearch(marketSearch.value.trim()),
                250
            );
        });
    }

    // -------------------------------------------------------------
    //  AJAX — СМЕНА АКТИВА СЧЁТА (SAFE + FORM-COMPATIBLE)
    // -------------------------------------------------------------

    const assetSelect = document.getElementById("accountAssetSelect");
    const assetHidden = document.getElementById("accountAssetHidden");

    if (assetSelect && assetHidden) {
        assetSelect.addEventListener("change", async () => {

            const asset = assetSelect.value;
            if (!asset) return;

            // 🔥 синхронизация для FORM submit
            assetHidden.value = asset;

            const ex = exSel?.value;
            const nt = ntSel?.value;

            if (!ex || !nt) {
                console.warn("Не удалось определить exchange / network", { ex, nt });
                return;
            }

            try {
                await fetch(`${baseUrl}/asset`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: new URLSearchParams({
                        chatId: String(chatId),
                        exchange: ex,
                        network: nt,
                        asset
                    })
                });
            } catch (e) {
                console.error("Ошибка смены актива", e);
            }
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const select = document.getElementById('accountAssetSelect');
        const hidden = document.getElementById('accountAssetHidden');

        if (select && hidden) {
            select.addEventListener('change', () => {
                hidden.value = select.value;
                console.log('accountAsset changed ->', select.value);
            });
        }
    });

// -------------------------------------------------------------
//  RUNTIME INDICATORS (READ-ONLY)
// -------------------------------------------------------------

    const cooldownInput = document.getElementById("cooldownIndicator");

    /**
     * Этот хук вызывается из strategy-live.js
     * при получении события type === "signal"
     *
     * event = {
     *   type: "signal",
     *   signal: {
     *     name: "HOLD",
     *     reason: "cooldown 12s"
     *   }
     * }
     */
    window.onStrategyLiveEvent = function (event) {
        if (!cooldownInput) return;
        if (!event || event.type !== "signal") return;

        const sig = event.signal;
        if (!sig || sig.name !== "HOLD" || typeof sig.reason !== "string") return;

        const m = sig.reason.match(/^cooldown\s+(\d+)s$/i);

        if (m) {
            cooldownInput.value = `${m[1]} сек`;
            cooldownInput.classList.remove("text-secondary");
            cooldownInput.classList.add("text-warning");
        } else {
            // если HOLD, но не cooldown — очищаем
            cooldownInput.value = "—";
            cooldownInput.classList.remove("text-warning");
            cooldownInput.classList.add("text-secondary");
        }
    };



});

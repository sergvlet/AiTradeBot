"use strict";

/**
 * Главный файл страницы:
 * - собирает контекст (chatId/type/exchange/network/baseUrl)
 * - табы (переключение + сохранение)
 * - вызывает init вкладок
 */
document.addEventListener("DOMContentLoaded", () => {

    const root = document.querySelector(".strategy-settings-page");

    const chatId = Number(
        root?.dataset.chatId ||
        document.querySelector("input[name='chatId']")?.value ||
        0
    );

    let type = (root?.dataset.type || "").trim();
    const exchange = (root?.dataset.exchange || "").trim();
    const network = (root?.dataset.network || "").trim();

    if (!chatId || !type) {
        console.warn("settings/page.js: chatId или type не определены", { chatId, type });
    }

    const baseUrl = `/strategies/${encodeURIComponent(type)}/config`;

    // кладём контекст в window (чтобы вкладки не дублировали парсинг DOM)
    window.StrategySettingsContext = {
        chatId,
        type,
        exchange,
        network,
        baseUrl
    };

    // ----------------------------
    // Tabs
    // ----------------------------
    const tabButtons = document.querySelectorAll(".tab-btn");
    const tabPanes = document.querySelectorAll(".tab-pane");

    const storageKey = "strategy_settings_active_tab";

    function activateTab(tabId) {
        if (!tabId) return;

        tabButtons.forEach(b => b.classList.remove("active"));
        tabPanes.forEach(p => p.classList.remove("active"));

        const btn = document.querySelector(`.tab-btn[data-tab='${tabId}']`);
        const pane = document.getElementById(tabId);

        if (btn) btn.classList.add("active");
        if (pane) pane.classList.add("active");

        localStorage.setItem(storageKey, tabId);
    }

    const savedTab = localStorage.getItem(storageKey);
    if (savedTab && document.getElementById(savedTab)) {
        activateTab(savedTab);
    } else {
        // безопасно: берём первый таб, если есть
        const first = tabButtons[0]?.dataset?.tab;
        if (first) activateTab(first);
    }

    tabButtons.forEach(btn => {
        btn.addEventListener("click", () => {
            activateTab(btn.dataset.tab);
        });
    });

    // кнопки "перейти на вкладку" (например из Risk в General)
    document.querySelectorAll("[data-open-tab]").forEach(el => {
        el.addEventListener("click", () => activateTab(el.getAttribute("data-open-tab")));
    });

    // ----------------------------
    // Init вкладок
    // ----------------------------
    window.SettingsTabNetwork?.init?.();
    window.SettingsTabGeneral?.init?.();
    window.SettingsTabRisk?.init?.();
    window.SettingsTabTrade?.init?.();
    window.SettingsTabAdvanced?.init?.(); // пустышка под будущее
});

"use strict";

/**
 * Главный файл страницы:
 * - собирает контекст (chatId/type/exchange/network/baseUrl)
 * - табы (переключение + сохранение)
 * - вызывает init вкладок (строго один раз на вкладку)
 */
(function () {

    // ✅ защита от двойного подключения (например, если скрипт подключили дважды)
    if (window.__StrategySettingsPageInited) return;
    window.__StrategySettingsPageInited = true;

    // ✅ CSS.escape может отсутствовать в старых браузерах — делаем безопасный fallback
    function cssEscapeSafe(v) {
        const s = String(v ?? "");
        if (window.CSS && typeof window.CSS.escape === "function") return window.CSS.escape(s);
        // минимальный escape для селектора атрибута
        return s.replace(/["\\]/g, "\\$&");
    }

    function boot() {
        const root = document.querySelector(".strategy-settings-page");
        if (!root) {
            console.warn("settings/page.js: .strategy-settings-page не найден");
            return;
        }

        const chatId = Number(
            root.dataset.chatId ||
            document.querySelector("input[name='chatId']")?.value ||
            0
        );

        const type = String(
            root.dataset.type ||
            document.querySelector("input[name='type']")?.value ||
            ""
        ).trim();

        const exchange = String(
            root.dataset.exchange ||
            document.querySelector("input[name='exchange']")?.value ||
            ""
        ).trim();

        const network = String(
            root.dataset.network ||
            document.querySelector("input[name='network']")?.value ||
            ""
        ).trim();

        if (!chatId || !type) {
            console.warn("settings/page.js: chatId или type не определены", { chatId, type });
        }

        const baseUrl = `/strategies/${encodeURIComponent(type)}/config`;

        // ✅ кладём контекст в window (чтобы вкладки не дублировали парсинг DOM)
        window.StrategySettingsContext = { chatId, type, exchange, network, baseUrl };

        // ----------------------------
        // Tabs
        // ----------------------------
        const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
        const tabPanes   = Array.from(document.querySelectorAll(".tab-pane"));

        // ✅ раздельно по стратегии (чтобы разные стратегии не мешали друг другу)
        const storageKey = `strategy_settings_active_tab:${type}`;

        // ✅ init каждого таба — строго один раз
        const initedTabs = new Set();

        function getPane(tabId) {
            return tabId ? document.getElementById(tabId) : null;
        }

        function setActive(btn, isActive) {
            if (!btn) return;
            btn.classList.toggle("active", !!isActive);

            // если у тебя aria связки — поддержим
            btn.setAttribute("aria-selected", isActive ? "true" : "false");
        }

        function setPaneActive(pane, isActive) {
            if (!pane) return;

            // ✅ совместимость с bootstrap (fade/show/active)
            pane.classList.toggle("active", !!isActive);
            pane.classList.toggle("show", !!isActive);

            pane.setAttribute("aria-hidden", isActive ? "false" : "true");
        }

        function initTabOnce(tabId) {
            if (!tabId) return;
            if (initedTabs.has(tabId)) return;
            initedTabs.add(tabId);

            const map = {
                "tab-network":  () => window.SettingsTabNetwork?.init?.(),
                "tab-general":  () => window.SettingsTabGeneral?.init?.(),
                "tab-risk":     () => window.SettingsTabRisk?.init?.(),
                "tab-trade":    () => window.SettingsTabTrade?.init?.(),
                "tab-advanced": () => window.SettingsTabAdvanced?.init?.()
            };

            try {
                map[tabId]?.();
            } catch (e) {
                console.error(`settings/page.js: init failed for ${tabId}`, e);
            }
        }

        function activateTab(tabId) {
            if (!tabId) return;

            const pane = getPane(tabId);
            if (!pane) return;

            // снять активность со всех
            tabButtons.forEach(b => setActive(b, false));
            tabPanes.forEach(p => setPaneActive(p, false));

            // включить нужное
            const sel = `.tab-btn[data-tab="${cssEscapeSafe(tabId)}"]`;
            const btn = document.querySelector(sel);

            setActive(btn, true);
            setPaneActive(pane, true);

            // ✅ init только активной вкладки
            initTabOnce(tabId);

            localStorage.setItem(storageKey, tabId);
        }

        // стартовая вкладка
        const savedTab = localStorage.getItem(storageKey);
        if (savedTab && getPane(savedTab)) {
            activateTab(savedTab);
        } else {
            const first = tabButtons[0]?.dataset?.tab;
            if (first) activateTab(first);
        }

        // клики по табам
        tabButtons.forEach(btn => {
            btn.addEventListener("click", () => {
                const tabId = btn.dataset.tab;
                activateTab(tabId);
            });
        });

        // кнопки "перейти на вкладку"
        document.querySelectorAll("[data-open-tab]").forEach(el => {
            el.addEventListener("click", () => {
                const tabId = el.getAttribute("data-open-tab");
                activateTab(tabId);
            });
        });

        // ❌ ВАЖНО: прогрев всех табов УБРАН — иначе будут лишние запросы и AbortError
        // Если захочешь “прогрев” — добавим отдельной опцией/кнопкой.
    }

    // ✅ если скрипт подключили после DOMContentLoaded — всё равно стартуем
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot, { once: true });
    } else {
        boot();
    }

})();

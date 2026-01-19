"use strict";

/**
 * Главный файл страницы:
 * - собирает контекст (chatId/type/exchange/network/baseUrl)
 * - табы (переключение + сохранение)
 * - вызывает init вкладок (строго один раз на вкладку)
 * - ✅ синхронизирует AdvancedControlMode между вкладками (MANUAL/HYBRID/AI)
 *
 * FIX: SettingsTabAdvanced может называться иначе — делаем алиас + безопасный вызов.
 */
(function () {

    if (window.__StrategySettingsPageInited) return;
    window.__StrategySettingsPageInited = true;

    function cssEscapeSafe(v) {
        const s = String(v ?? "");
        if (window.CSS && typeof window.CSS.escape === "function") return window.CSS.escape(s);
        return s.replace(/["\\]/g, "\\$&");
    }

    function safeLsGet(key) {
        try { return window.localStorage.getItem(key); } catch (_) { return null; }
    }
    function safeLsSet(key, val) {
        try { window.localStorage.setItem(key, val); } catch (_) {}
    }

    // ✅ Алиасы: если где-то осталось другое имя advanced-вкладки
    function resolveAdvancedTab() {
        // приоритет: новое имя
        if (window.SettingsTabAdvanced) return window.SettingsTabAdvanced;
        // возможные старые/другие имена
        if (window.SettingsAdvancedTab) return window.SettingsAdvancedTab;
        if (window.SettingsTabAdv) return window.SettingsTabAdv;
        if (window.AdvancedTab) return window.AdvancedTab;

        // если скрипт advanced подключён как IIFE без экспорта — вернём null
        return null;
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

        const baseUrl = `/strategies/${encodeURIComponent(type)}/config`;

        window.StrategySettingsContext = { chatId, type, exchange, network, baseUrl };

        // =====================================================
        // CONTROL MODE SYNC
        // =====================================================
        const controlModeSelect = document.getElementById("advancedControlMode");

        function setCtxControlMode(mode) {
            const m = String(mode || "").trim().toUpperCase();
            if (!m) return;
            window.StrategySettingsContext.advancedControlMode = m;
        }

        function emitControlModeChanged(mode) {
            const m = String(mode || "").trim().toUpperCase();
            if (!m) return;
            setCtxControlMode(m);
            window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode: m } }));
        }

        if (controlModeSelect) {
            const cur = String(controlModeSelect.value || "").trim().toUpperCase();
            if (cur) setCtxControlMode(cur);

            controlModeSelect.addEventListener("change", () => {
                const next = String(controlModeSelect.value || "").trim().toUpperCase() || "MANUAL";
                controlModeSelect.dataset.prevValue = next;
                emitControlModeChanged(next);
            });
        }

        window.addEventListener("strategy:controlModeChanged", (e) => {
            const m = String(e?.detail?.mode || "").trim().toUpperCase();
            if (!m) return;

            setCtxControlMode(m);

            if (controlModeSelect) {
                const cur = String(controlModeSelect.value || "").trim().toUpperCase();
                if (cur !== m) {
                    controlModeSelect.value = m;
                    controlModeSelect.dataset.prevValue = m;
                }
            }
        });

        // ----------------------------
        // Tabs
        // ----------------------------
        const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
        const tabPanes = Array.from(document.querySelectorAll(".tab-pane"));

        const storageKey = `strategy_settings_active_tab:${type}:${exchange || "X"}:${network || "X"}`;
        const initedTabs = new Set();

        function getPane(tabId) {
            return tabId ? document.getElementById(tabId) : null;
        }

        function setActive(btn, isActive) {
            if (!btn) return;
            btn.classList.toggle("active", !!isActive);
            btn.setAttribute("aria-selected", isActive ? "true" : "false");
        }

        function setPaneActive(pane, isActive) {
            if (!pane) return;
            pane.classList.toggle("active", !!isActive);
            pane.classList.toggle("show", !!isActive);
            pane.setAttribute("aria-hidden", isActive ? "false" : "true");
        }

        function initTabOnce(tabId) {
            if (!tabId) return;
            if (initedTabs.has(tabId)) return;
            initedTabs.add(tabId);

            const advancedTab = resolveAdvancedTab();

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

            tabButtons.forEach(b => setActive(b, false));
            tabPanes.forEach(p => setPaneActive(p, false));

            const sel = `.tab-btn[data-tab="${cssEscapeSafe(tabId)}"]`;
            const btn = document.querySelector(sel);

            setActive(btn, true);
            setPaneActive(pane, true);

            initTabOnce(tabId);

            safeLsSet(storageKey, tabId);

            // прокидываем актуальный режим при открытии вкладки
            if (controlModeSelect) {
                const m = String(controlModeSelect.value || "").trim().toUpperCase();
                if (m) emitControlModeChanged(m);
            } else if (window.StrategySettingsContext?.advancedControlMode) {
                emitControlModeChanged(window.StrategySettingsContext.advancedControlMode);
            }
        }

        const savedTab = safeLsGet(storageKey);
        if (savedTab && getPane(savedTab)) {
            activateTab(savedTab);
        } else {
            const first = tabButtons[0]?.dataset?.tab;
            if (first) activateTab(first);
        }

        tabButtons.forEach(btn => {
            btn.addEventListener("click", () => {
                const tabId = btn.dataset.tab;
                activateTab(tabId);
            });
        });

        document.querySelectorAll("[data-open-tab]").forEach(el => {
            el.addEventListener("click", () => {
                const tabId = el.getAttribute("data-open-tab");
                activateTab(tabId);
            });
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot, { once: true });
    } else {
        boot();
    }

})();

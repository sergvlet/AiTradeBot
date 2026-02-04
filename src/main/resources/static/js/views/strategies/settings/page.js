"use strict";

/**
 * Strategy Settings Page Bootstrap
 * - tabs persistence (per chatId/type/exchange/network)
 * - lazy init per-tab scripts
 * - safe + no spam
 */
(function () {

    function $(sel) { return document.querySelector(sel); }
    function $all(sel) { return Array.from(document.querySelectorAll(sel)); }

    function getRoot() {
        return $(".strategy-settings-page");
    }

    function getCtx() {
        const root = getRoot();
        if (!root) return null;

        const chatId = root.getAttribute("data-chat-id") || "";
        const type = root.getAttribute("data-type") || "";
        const exchange = root.getAttribute("data-exchange") || "";
        const network = root.getAttribute("data-network") || "";

        return {
            chatId: String(chatId),
            type: String(type),
            exchange: String(exchange),
            network: String(network),
            baseUrl: window.location.pathname
        };
    }

    function storageKey(ctx) {
        const a = (ctx?.chatId || "0");
        const b = (ctx?.type || "NA");
        const c = (ctx?.exchange || "NA");
        const d = (ctx?.network || "NA");
        return `strategy_settings_active_tab::${a}::${b}::${c}::${d}`;
    }

    function normalizeTabName(name) {
        const allowed = new Set(["network", "control", "trade", "risk", "advanced"]);
        if (!name) return "network";
        const n = String(name).trim().toLowerCase();
        return allowed.has(n) ? n : "network";
    }

    function setActiveTab(tabName) {
        const buttons = $all(".tab-btn");
        const panes = $all(".tab-pane");

        buttons.forEach(btn => {
            const isActive = (btn.dataset.tab === "tab-" + tabName);
            btn.classList.toggle("active", isActive);
            btn.setAttribute("aria-selected", isActive ? "true" : "false");
        });

        panes.forEach(p => {
            const on = (p.id === "tab-" + tabName);
            p.classList.toggle("active", on);

            // ✅ на всякий случай (если где-то включены bootstrap-классы)
            p.classList.toggle("show", on);
        });

        try { window.scrollTo({ top: 0, behavior: "smooth" }); } catch (_) {}
    }

    function initTabOnce(tabName) {
        const key = "__init_settings_tab_" + tabName;
        if (window[key]) return;
        window[key] = true;

        if (tabName === "network") {
            window.SettingsTabNetwork?.init?.();
            return;
        }

        if (tabName === "control") {
            // ✅ если у тебя контрольный таб реализован как SettingsTabGeneral — поддержим
            window.SettingsTabControl?.init?.();
            window.SettingsTabGeneral?.init?.();
            return;
        }

        if (tabName === "trade") {
            window.SettingsTabTrade?.init?.();
            window.SettingsTabMarket?.init?.(); // совместимость, если где-то осталось старое имя
            return;
        }

        if (tabName === "risk") {
            window.SettingsTabRisk?.init?.();
            return;
        }

        if (tabName === "advanced") {
            window.SettingsTabAdvanced?.init?.();
            return;
        }
    }

    function boot() {
        const ctx = getCtx();
        if (!ctx) return;

        // expose ctx for other scripts
        window.StrategySettingsContext = ctx;

        const buttons = $all(".tab-btn");
        if (!buttons.length) return;

        buttons.forEach(btn => {
            btn.setAttribute("role", "tab");
            btn.setAttribute("tabindex", "0");

            btn.addEventListener("click", () => {
                const tabId = btn.dataset.tab || "";
                const tabName = normalizeTabName(tabId.replace("tab-", ""));
                setActiveTab(tabName);
                initTabOnce(tabName);
                try { localStorage.setItem(storageKey(ctx), tabName); } catch (_) {}
            });

            btn.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    btn.click();
                }
            });
        });

        // initial tab: query > localStorage > default
        const url = new URL(window.location.href);
        const fromQuery = normalizeTabName(url.searchParams.get("tab"));

        let saved = "network";
        try { saved = normalizeTabName(localStorage.getItem(storageKey(ctx))); } catch (_) {}

        const initial = fromQuery || saved || "network";
        setActiveTab(initial);
        initTabOnce(initial);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();

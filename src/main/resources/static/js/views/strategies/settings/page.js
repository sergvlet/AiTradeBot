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

    const initedTabs = new Set();

    function getRoot() {
        return $(".strategy-settings-page");
    }

    function getCtx() {
        const root = getRoot();
        if (!root) return null;

        const chatId   = root.getAttribute("data-chat-id") || "";
        const type     = root.getAttribute("data-type") || "";
        const exchange = root.getAttribute("data-exchange") || "";
        const network  = root.getAttribute("data-network") || "";

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
            p.classList.toggle("show", on);
        });

        try { window.scrollTo({ top: 0, behavior: "smooth" }); } catch (_) {}
    }

    function resolveAdvancedTab() {
        return window.SettingsTabAdvanced || window.SettingsTabAi || window.SettingsTabStatus || null;
    }

    function initTabOnce(tabId) {
        if (!tabId) return;
        if (initedTabs.has(tabId)) return;
        initedTabs.add(tabId);

        const advancedTab = resolveAdvancedTab();

        const map = {
            "tab-network":  () => window.SettingsTabNetwork?.init?.(),
            "tab-control":  () => window.SettingsTabGeneral?.init?.(),
            "tab-risk":     () => window.SettingsTabRisk?.init?.(),
            "tab-trade":    () => window.SettingsTabTrade?.init?.(),
            "tab-advanced": () => advancedTab?.init?.()
        };

        console.log("[settings/page] initTabOnce:", tabId, "->", Object.keys(map).includes(tabId) ? "OK" : "NO_HANDLER");

        try {
            map[tabId]?.();
        } catch (e) {
            console.error(`settings/page.js: init failed for ${tabId}`, e);
        }
    }

    function boot() {
        const ctx = getCtx();
        if (!ctx) {
            console.warn("[settings/page] root ctx not found");
            return;
        }

        window.StrategySettingsContext = ctx;
        console.log("[settings/page] boot ctx:", ctx);

        const buttons = $all(".tab-btn");
        console.log("[settings/page] tab buttons:", buttons.map(b => b.dataset.tab));

        if (!buttons.length) return;

        buttons.forEach(btn => {
            btn.setAttribute("role", "tab");
            btn.setAttribute("tabindex", "0");

            btn.addEventListener("click", () => {
                const tabId = btn.dataset.tab || ""; // "tab-risk"
                const tabName = normalizeTabName(tabId.replace("tab-", "")); // "risk"

                console.log("[settings/page] click:", tabId);

                setActiveTab(tabName);
                initTabOnce(tabId);

                try { localStorage.setItem(storageKey(ctx), tabName); } catch (_) {}
            });

            btn.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    btn.click();
                }
            });
        });

        const url = new URL(window.location.href);
        const fromQuery = normalizeTabName(url.searchParams.get("tab"));

        let saved = "network";
        try { saved = normalizeTabName(localStorage.getItem(storageKey(ctx))); } catch (_) {}

        const initial = fromQuery || saved || "network";

        setActiveTab(initial);
        initTabOnce("tab-" + initial);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();

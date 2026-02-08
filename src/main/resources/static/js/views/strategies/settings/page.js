"use strict";

// =====================================================
// 🚌 Global Bus + Store (единое состояние страницы)
// =====================================================
(function initStrategySettingsBusAndStore() {
    // Bus
    if (!window.StrategySettingsBus) {
        window.StrategySettingsBus = {
            emit(name, detail) {
                try {
                    window.dispatchEvent(new CustomEvent(name, { detail }));
                } catch (e) {
                    // если CustomEvent недоступен/сломался — просто игнор
                }
            },
            on(name, handler) {
                if (!name || typeof handler !== "function") return () => {};
                window.addEventListener(name, handler);
                return () => window.removeEventListener(name, handler);
            }
        };
    }

    // Store
    if (!window.StrategySettingsStore) {
        let _state = null;
        const listeners = new Set();

        function set(next) {
            _state = next || null;
            // 1) event
            window.StrategySettingsBus.emit("strategy:state", _state);
            // 2) subscribers
            listeners.forEach(fn => {
                try { fn(_state); } catch (_) {}
            });
        }

        function get() {
            return _state;
        }

        function subscribe(fn) {
            if (typeof fn !== "function") return () => {};
            listeners.add(fn);
            // push current immediately
            try { fn(_state); } catch (_) {}
            return () => listeners.delete(fn);
        }

        window.StrategySettingsStore = { set, get, subscribe };
    }
})();

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

    // =====================================================
    // ✅ COMPAT: api.js ожидает window.SettingsPageStore
    // =====================================================
    function initCompatSettingsPageStore() {
        if (window.SettingsPageStore) return;

        // store + bus уже созданы в initStrategySettingsBusAndStore()
        const Store = window.StrategySettingsStore;
        const Bus = window.StrategySettingsBus;

        // защита: если по какой-то причине не поднялись
        if (!Store || !Bus) {
            console.warn("⚠ StrategySettingsStore/Bus not found — compat store disabled");
            return;
        }

        let hardRefreshInProgress = false;
        let hardRefreshTimer = null;

        function getContext() {
            return window.StrategySettingsContext || null;
        }

        function buildStateUrl(ctx) {
            if (!ctx) return null;
            const q = new URLSearchParams();
            q.set("chatId", String(ctx.chatId));
            if (ctx.exchange) q.set("exchange", String(ctx.exchange));
            if (ctx.network) q.set("network", String(ctx.network));
            return `/strategies/${encodeURIComponent(ctx.type)}/config/state?${q.toString()}`;
        }

        async function hardRefreshNow() {
            const ctx = getContext();
            const url = buildStateUrl(ctx);
            if (!url) return null;
            if (hardRefreshInProgress) return null;
            hardRefreshInProgress = true;
            try {
                // используем SettingsApi если есть
                let st = null;
                if (window.SettingsApi && typeof window.SettingsApi.getJson === "function") {
                    st = await window.SettingsApi.getJson(url);
                } else {
                    const r = await fetch(url, { headers: { "Accept": "application/json" } });
                    if (!r.ok) throw new Error(`state fetch failed: ${r.status}`);
                    st = await r.json();
                }
                if (st) {
                    // важно: не запускаем повторный hard refresh из этого применения
                    window.SettingsPageStore.setStateFromServerState(st, { skipHardRefresh: true });
                }
                return st;
            } catch (e) {
                console.warn("⚠ hardRefreshNow failed:", e);
                return null;
            } finally {
                hardRefreshInProgress = false;
            }
        }

        function scheduleHardRefresh(delayMs) {
            clearTimeout(hardRefreshTimer);
            hardRefreshTimer = setTimeout(() => { hardRefreshNow(); }, Math.max(0, delayMs || 0));
        }

        function setStateFromServerState(serverState, opts) {
            // кладём в общий store — это триггерит подписчиков
            try {
                Store.set(serverState);
                Bus.emit("ui:state", serverState);
            } catch (e) {
                console.warn("⚠ failed to set store state:", e);
            }

            // ✅ после POST часто нужно дочитать свежий баланс/снапшот
            if (!opts || !opts.skipHardRefresh) {
                scheduleHardRefresh(80);
            }
        }

        window.SettingsPageStore = {
            getContext,
            setStateFromServerState,
            hardRefreshNow,
        };
    }

    // =====================================================
    // ✅ DOM AUTO-UPDATE (балансы / риск / кнопка active)
    // =====================================================
    function initDomAutoUpdate() {
        const Store = window.StrategySettingsStore;
        if (!Store || typeof Store.onChange !== "function") return;

        function byId(id) { return document.getElementById(id); }

        function text(el, v) {
            if (!el) return;
            el.textContent = (v === null || v === undefined || v === "") ? "—" : String(v);
        }

        function asNum(v) {
            if (v === null || v === undefined || v === "") return null;
            if (typeof v === "number") return v;
            const s = String(v).replace(",", ".");
            const n = Number(s);
            return Number.isFinite(n) ? n : null;
        }

        function fmtMoney(v, scale) {
            if (v === null || v === undefined || v === "") return "—";
            const n = asNum(v);
            if (n === null) return String(v);
            const sc = (typeof scale === "number" ? scale : 8);
            return n.toFixed(sc).replace(/\.0+$/, "").replace(/(\.\d*?)0+$/, "$1");
        }

        function applyStateToDom(state) {
            if (!state) return;

            // 1) badges / active
            const activeBadge = byId("strategyActiveBadge");
            const toggleBtn = byId("strategyToggleBtn");

            if (activeBadge) {
                const isOn = !!state.active;
                activeBadge.textContent = isOn ? "RUNNING" : "STOPPED";
                activeBadge.classList.toggle("bg-success", isOn);
                activeBadge.classList.toggle("bg-secondary", !isOn);
            }

            if (toggleBtn) {
                const isOn = !!state.active;
                toggleBtn.textContent = isOn ? "Остановить" : "Запустить";
                toggleBtn.classList.toggle("btn-danger", isOn);
                toggleBtn.classList.toggle("btn-success", !isOn);
            }

            // 2) hidden inputs: exchange/network чтобы формы сохраняли актуальное
            if (state.exchange) {
                document.querySelectorAll("input[name='exchange']").forEach(i => i.value = state.exchange);
            }
            if (state.network) {
                document.querySelectorAll("input[name='network']").forEach(i => i.value = state.network);
            }

            // 3) балансы (trade tab)
            const bal = state.selectedBalance || null;
            if (bal) {
                const asset = bal.asset || state.accountAsset || "";
                text(byId("selectedAssetText"), asset);
                text(byId("assetFreeView"), fmtMoney(bal.free, 8));
                text(byId("assetLockedView"), fmtMoney(bal.locked, 8));

                // total: если пришёл с бэка — используем, иначе считаем
                let total = bal.total;
                if (total === undefined || total === null) {
                    const f = asNum(bal.free);
                    const l = asNum(bal.locked);
                    if (f !== null && l !== null) total = f + l;
                }
                text(byId("assetTotalView"), fmtMoney(total, 8));
            }

            // 4) риск (available balance)
            if (bal) {
                const asset = bal.asset || state.accountAsset || "";
                text(byId("riskAssetText"), asset);
                text(byId("riskFreeBalanceText"), fmtMoney(bal.free, 8));
                const freeVal = byId("riskFreeBalanceValue");
                if (freeVal) freeVal.value = (bal.free !== null && bal.free !== undefined) ? String(bal.free) : "";
                const sel = byId("riskSelectedAsset");
                if (sel) sel.value = asset;
            }

            // 5) accountAsset hidden/select (если есть)
            if (state.accountAsset) {
                const h = byId("accountAssetHidden");
                if (h) h.value = state.accountAsset;
                const s = byId("accountAssetSelect");
                if (s && s.value !== state.accountAsset) s.value = state.accountAsset;
            }
        }

        // подписка
        Store.onChange((st) => {
            try { applyStateToDom(st); } catch (e) { console.warn("⚠ applyStateToDom failed:", e); }
        });

        // кнопка ручного refresh (trade tab)
        const btn = document.getElementById("assetRefreshBtn");
        if (btn && window.SettingsPageStore && typeof window.SettingsPageStore.hardRefreshNow === "function") {
            btn.addEventListener("click", (e) => {
                e.preventDefault();
                window.SettingsPageStore.hardRefreshNow();
            });
        }

        // active toggle: делаем AJAX + state refresh, без перезагрузки страницы
        const toggleForm = document.getElementById("strategyToggleForm");
        if (toggleForm) {
            toggleForm.addEventListener("submit", async (e) => {
                e.preventDefault();
                try {
                    await fetch(toggleForm.action, {
                        method: "POST",
                        headers: {
                            "X-Requested-With": "fetch",
                            "Accept": "application/json"
                        },
                        body: new FormData(toggleForm)
                    });
                } catch (err) {
                    console.warn("⚠ toggle failed:", err);
                }
                if (window.SettingsPageStore && typeof window.SettingsPageStore.hardRefreshNow === "function") {
                    await window.SettingsPageStore.hardRefreshNow();
                }
            });
        }
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

        // ✅ включаем единый стор обновления UI (балансы/риск/бейдж Active)
        try { initCompatSettingsPageStore(); } catch (e) {
            console.warn("[settings/page] initCompatSettingsPageStore failed:", e);
        }

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

"use strict";

// =====================================================
// 🚌 Global Bus + Store (единое состояние страницы)
// =====================================================
(function initStrategySettingsBusAndStore() {
    // Bus
    if (!window.StrategySettingsBus) {
        window.StrategySettingsBus = {
            emit(name, detail) {
                try { window.dispatchEvent(new CustomEvent(name, { detail })); } catch (e) {}
            },
            on(name, handler) {
                if (!name || typeof handler !== "function") return () => {};
                window.addEventListener(name, handler);
                return () => window.removeEventListener(name, handler);
            }
        };
    }

    // Store (✅ совместим: setState/getState/onChange)
    if (!window.StrategySettingsStore) {
        let _state = null;
        const listeners = new Set();

        function set(next) {
            _state = next || null;
            // ✅ только подписчики (DOM event эмитим через Bus в compat/store ниже)
            listeners.forEach(fn => { try { fn(_state); } catch (_) {} });
        }

        function get() {
            return _state;
        }

        function subscribe(fn) {
            if (typeof fn !== "function") return () => {};
            listeners.add(fn);
            try { fn(_state); } catch (_) {}
            return () => listeners.delete(fn);
        }

        window.StrategySettingsStore = {
            // canonical
            set, get, subscribe,

            // compat
            setState: set,
            getState: get,
            onChange: subscribe,
        };
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
    // ✅ CONFIRM MODAL (без window.confirm + фикс залипающего backdrop)
    // =====================================================
    function cleanupBackdrops() {
        try {
            document.querySelectorAll(".modal-backdrop").forEach(b => b.remove());
            document.body.classList.remove("modal-open");
            document.body.style.removeProperty("padding-right");
        } catch (_) {}
    }

    function resolveConfirmModalEls() {
        // ✅ берём только НАСТОЯЩУЮ модалку (class="modal")
        const modal =
            document.querySelector("#confirmModal.modal")
            || document.querySelector("#generalConfirmModal.modal")
            || null;

        const title =
            document.getElementById("confirmModalTitle")
            || document.getElementById("generalConfirmTitle")
            || null;

        const body =
            document.getElementById("confirmModalBody")
            || document.getElementById("generalConfirmText")
            || null;

        const ok =
            document.getElementById("confirmModalOk")
            || document.getElementById("generalConfirmOk")
            || null;

        const cancel =
            document.getElementById("confirmModalCancel")
            || document.getElementById("generalConfirmCancel")
            || null;

        return { modal, title, body, ok, cancel };
    }

    function showConfirmModal({ title, text }) {
        return new Promise((resolve) => {
            const els = resolveConfirmModalEls();
            if (!els.modal || !els.ok) {
                // ✅ если модалки нет — НЕ создаём backdrop, просто безопасный fallback
                resolve(window.confirm(`${title ? title + "\n\n" : ""}${text || "Сохранить изменения?"}`));
                return;
            }

            // перед показом чистим "залипший" бекдроп (если вдруг был баг ранее)
            cleanupBackdrops();

            if (els.title && title) els.title.textContent = String(title);
            if (els.body) els.body.textContent = String(text || "Сохранить изменения?");

            let done = false;

            const finish = (ok) => {
                if (done) return;
                done = true;
                try {
                    els.ok.removeEventListener("click", onOk);
                    els.cancel?.removeEventListener("click", onCancel);
                    els.modal.removeEventListener("hidden.bs.modal", onHidden);
                } catch (_) {}
                resolve(!!ok);
            };

            const onOk = () => {
                try { bsHide(); } catch (_) {}
                finish(true);
            };

            const onCancel = () => {
                try { bsHide(); } catch (_) {}
                finish(false);
            };

            const onHidden = () => {
                // если закрыли крестиком/ESC
                finish(false);
            };

            function bsShow() {
                if (window.bootstrap?.Modal) {
                    const inst = window.bootstrap.Modal.getOrCreateInstance(els.modal, {
                        backdrop: "static",
                        keyboard: true
                    });
                    els.modal.addEventListener("hidden.bs.modal", onHidden, { once: true });
                    inst.show();
                    return inst;
                }

                // === fallback если вдруг нет bootstrap js ===
                els.modal.style.display = "block";
                els.modal.classList.add("show");
                els.modal.removeAttribute("aria-hidden");
                els.modal.setAttribute("aria-modal", "true");
                document.body.classList.add("modal-open");

                const bd = document.createElement("div");
                bd.className = "modal-backdrop fade show";
                document.body.appendChild(bd);

                const esc = (e) => {
                    if (e.key === "Escape") {
                        document.removeEventListener("keydown", esc, true);
                        bsHide();
                        finish(false);
                    }
                };
                document.addEventListener("keydown", esc, true);
                return null;
            }

            function bsHide() {
                if (window.bootstrap?.Modal) {
                    const inst = window.bootstrap.Modal.getInstance(els.modal);
                    if (inst) inst.hide();
                    else cleanupBackdrops();
                    return;
                }
                // fallback hide
                els.modal.classList.remove("show");
                els.modal.style.display = "none";
                cleanupBackdrops();
            }

            els.ok.addEventListener("click", onOk);
            els.cancel?.addEventListener("click", onCancel);

            try { bsShow(); }
            catch (e) {
                // если bootstrap сломался — чистим бекдроп и fallback
                cleanupBackdrops();
                finish(window.confirm(`${title ? title + "\n\n" : ""}${text || "Сохранить изменения?"}`));
            }
        });
    }

    // ✅ перехват [data-confirm] на CHANGE, чтобы до автосейва спрашивать модалкой
    function bindDataConfirm(root) {
        const doc = root || document;
        const selector = "[data-confirm='true'],[data-confirm='1'],[data-confirm='yes'],[data-confirm='on']";

        const tracked = new WeakMap();

        function readValue(el) {
            if (!el) return null;
            const t = (el.type || "").toLowerCase();
            if (t === "checkbox" || t === "radio") return !!el.checked;
            return String(el.value ?? "");
        }

        // init snapshot
        doc.querySelectorAll(selector).forEach(el => tracked.set(el, readValue(el)));

        // capture BEFORE autosave handlers
        doc.addEventListener("change", async (e) => {
            const el = e.target;
            if (!el || !tracked.has(el)) return;

            // пропуск "второго" события, которое мы сами генерим после OK
            if (el.dataset._confirmSkipOnce === "1") {
                delete el.dataset._confirmSkipOnce;
                tracked.set(el, readValue(el));
                return;
            }

            const prev = tracked.get(el);
            const next = readValue(el);
            if (prev === next) return;

            // ✅ стопаем всё, пока пользователь не ответит
            e.preventDefault();
            e.stopImmediatePropagation();

            const title = el.getAttribute("data-confirm-title") || "Подтверждение";
            const text  = el.getAttribute("data-confirm-text")  || "Сохранить изменения?";

            const ok = await showConfirmModal({ title, text });

            if (ok) {
                tracked.set(el, next);
                el.dataset._confirmSkipOnce = "1";
                // запускаем нормальный change, чтобы автосейв/логика вкладок отработали
                el.dispatchEvent(new Event("change", { bubbles: true }));
            } else {
                // откат значения без вызова автосейва
                const t = (el.type || "").toLowerCase();
                if (t === "checkbox" || t === "radio") el.checked = !!prev;
                else el.value = String(prev ?? "");
            }
        }, true);
    }

    // =====================================================
    // ✅ COMPAT: api.js ожидает window.SettingsPageStore
    // =====================================================
    function initCompatSettingsPageStore() {
        if (window.SettingsPageStore) return;

        const Store = window.StrategySettingsStore;
        const Bus   = window.StrategySettingsBus;

        if (!Store || !Bus) {
            console.warn("⚠ StrategySettingsStore/Bus not found — compat store disabled");
            return;
        }

        let hardRefreshInProgress = false;
        let lastHardRefreshAt = 0;
        const MIN_HARD_REFRESH_GAP_MS = 4000;

        function getContext() {
            return window.StrategySettingsContext || null;
        }

        function buildStateUrl(ctx, opts) {
            if (!ctx) return null;
            const q = new URLSearchParams();
            q.set("chatId", String(ctx.chatId));
            if (ctx.exchange) q.set("exchange", String(ctx.exchange));
            if (ctx.network)  q.set("network", String(ctx.network));
            if (!opts || opts.lite !== false) q.set("lite", "true");
            if (opts && opts.withBalance) q.set("balance", "true");
            q.set("_ts", String(Date.now()));
            return `/strategies/${encodeURIComponent(String(ctx.type))}/config/state?${q.toString()}`;
        }

        function scheduleBackgroundRefresh() {
            // Фоновый polling отключён намеренно.
            // После POST state и так подтягивается через SettingsApi.refreshUiStateIfConfig(),
            // а дополнительный таймер только создаёт лишние GET /config/state.
        }

        function setStateFromServerState(serverState) {
            try {
                if (typeof Store.setState === "function") Store.setState(serverState);
                else Store.set(serverState);

                Bus.emit("strategy:state", serverState);
                Bus.emit("ui:state", serverState);
            } catch (e) {
                console.warn("⚠ failed to set store state:", e);
            }
        }

        async function hardRefreshNow(opts) {
            const nowMs = Date.now();
            if (nowMs - lastHardRefreshAt < MIN_HARD_REFRESH_GAP_MS) {
                return null;
            }

            const options = opts || {};
            const ctx = getContext();
            const url = buildStateUrl(ctx, options);
            if (!url) return null;
            if (hardRefreshInProgress) return null;

            hardRefreshInProgress = true;
            lastHardRefreshAt = nowMs;
            try {
                let st = null;

                if (window.SettingsApi && typeof window.SettingsApi.getJson === "function") {
                    st = await window.SettingsApi.getJson(url);
                } else {
                    const r = await fetch(url, { headers: { "Accept": "application/json" }, cache: "no-store" });
                    if (!r.ok) throw new Error(`state fetch failed: ${r.status}`);
                    st = await r.json();
                }

                if (st) setStateFromServerState(st);
                return st;
            } catch (e) {
                console.warn("⚠ hardRefreshNow failed:", e);
                return null;
            } finally {
                hardRefreshInProgress = false;
            }
        }

        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "visible") {
                hardRefreshNow({ lite: true });
            }
        });

        window.SettingsPageStore = {
            getContext,
            setStateFromServerState,
            hardRefreshNow,
            scheduleBackgroundRefresh
        };
    }

    // =====================================================
    // ✅ DOM AUTO-UPDATE (балансы / риск / exchange/network)
    // =====================================================
    function initDomAutoUpdate() {
        const Store = window.StrategySettingsStore;
        if (!Store || (typeof Store.onChange !== "function" && typeof Store.subscribe !== "function")) return;

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

        function normNet(v) {
            if (v === null || v === undefined) return "";
            if (typeof v === "object" && v.name) return String(v.name);
            return String(v);
        }
        function normTf(v) {
            if (v === null || v === undefined) return "";
            const raw = String(v).trim();
            if (!raw) return "";

            if (raw === "M" || raw === "1M") return "1mo";
            if (raw === "W" || raw === "1W") return "1w";
            if (raw === "D" || raw === "1D") return "1d";
            if (raw === "3D") return "3d";

            const lower = raw.toLowerCase();
            switch (lower) {
                case "1":
                case "1m":
                    return "1m";
                case "3":
                case "3m":
                    return "3m";
                case "5":
                case "5m":
                    return "5m";
                case "15":
                case "15m":
                    return "15m";
                case "30":
                case "30m":
                    return "30m";
                case "60":
                case "1h":
                    return "1h";
                case "120":
                case "2h":
                    return "2h";
                case "240":
                case "4h":
                    return "4h";
                case "360":
                case "6h":
                    return "6h";
                case "480":
                case "8h":
                    return "8h";
                case "720":
                case "12h":
                    return "12h";
                case "d":
                case "1d":
                    return "1d";
                case "3d":
                    return "3d";
                case "w":
                case "1w":
                    return "1w";
                case "1mo":
                case "1mon":
                case "1month":
                    return "1mo";
                default:
                    return lower;
            }
        }

        function syncTimeframeOptions(state) {
            const select = byId("tradeTimeframeSelect");
            const readonly = byId("tradeTimeframeReadonly");
            if (!select) return;

            const list = Array.isArray(state?.availableTimeframes)
                ? state.availableTimeframes.map(normTf).filter(Boolean)
                : [];

            const desired = normTf(state?.timeframe || select.value);
            const nextValue = list.includes(desired)
                ? desired
                : (list[0] || desired || "");

            const currentOptions = Array.from(select.options || []).map(o => normTf(o.value));
            const sameOptions =
                currentOptions.length === list.length &&
                currentOptions.every((value, index) => value === list[index]);

            if (list.length && !sameOptions && document.activeElement !== select) {
                select.innerHTML = "";
                list.forEach(tf => {
                    const option = document.createElement("option");
                    option.value = tf;
                    option.textContent = tf;
                    select.appendChild(option);
                });
            }

            if (nextValue && document.activeElement !== select) {
                select.value = nextValue;
            }

            text(readonly, nextValue || "—");

            const root = getRoot();
            if (root && nextValue) {
                root.setAttribute("data-timeframe", nextValue);
            }

            if (window.StrategySettingsContext) {
                window.StrategySettingsContext.timeframe = nextValue || "";
            }
        }

        function applyStateToDom(state) {
            if (!state) return;

            const root = getRoot();
            if (root) {
                if (state.chatId)   root.setAttribute("data-chat-id", String(state.chatId));
                if (state.type)     root.setAttribute("data-type", String(state.type));
                if (state.exchange) root.setAttribute("data-exchange", String(state.exchange));
                if (state.network)  root.setAttribute("data-network", normNet(state.network));
            }

            if (state.exchange) {
                document.querySelectorAll("input[name='exchange']").forEach(i => i.value = String(state.exchange));
                const kEx = byId("keysExchange");
                if (kEx) kEx.value = String(state.exchange).toUpperCase();
            }
            if (state.network) {
                const nn = normNet(state.network);
                document.querySelectorAll("input[name='network']").forEach(i => i.value = nn);
                const kNet = byId("keysNetwork");
                if (kNet) kNet.value = String(nn).toUpperCase();
            }

            const bal = state.selectedBalance || state.balance || null;
            if (bal) {
                const asset = (bal.asset || state.accountAsset || "").toString().toUpperCase();

                text(byId("assetFreeView"),   fmtMoney(bal.free, 8));
                text(byId("assetLockedView"), fmtMoney(bal.locked, 8));

                let total = bal.total;
                if (total === undefined || total === null) {
                    const f = asNum(bal.free);
                    const l = asNum(bal.locked);
                    if (f !== null && l !== null) total = f + l;
                }
                text(byId("assetTotalView"), fmtMoney(total, 8));

                text(byId("riskAssetText"), asset || "—");
                text(byId("riskFreeBalanceText"), fmtMoney(bal.free, 8));

                const freeVal = byId("riskFreeBalanceValue");
                if (freeVal) freeVal.value = (bal.free !== null && bal.free !== undefined) ? String(bal.free) : "";

                const sel = byId("riskSelectedAsset");
                if (sel) sel.value = asset || "";
            }

            if (state.accountAsset) {
                const aa = String(state.accountAsset).toUpperCase();

                const h = byId("accountAssetHidden");
                if (h) h.value = aa;

                const s = byId("accountAssetSelect");
                if (s && s.value !== aa) s.value = aa;

                const hint = byId("assetInHint");
                if (hint) hint.textContent = aa;
            }

            syncTimeframeOptions(state);
        }

        const sub = (typeof Store.onChange === "function") ? Store.onChange : Store.subscribe;
        sub((st) => {
            try { applyStateToDom(st); } catch (e) { console.warn("⚠ applyStateToDom failed:", e); }
        });

        const btn = byId("assetRefreshBtn");
        if (btn && window.SettingsPageStore && typeof window.SettingsPageStore.hardRefreshNow === "function") {
            btn.addEventListener("click", (e) => {
                e.preventDefault();
                window.SettingsPageStore.hardRefreshNow({ withBalance: true, lite: false });
            });
        }

        const toggleForm =
            document.getElementById("strategyToggleForm")
            || document.querySelector("form[action*='/toggle']");

        if (toggleForm) {
            toggleForm.addEventListener("submit", async (e) => {
                e.preventDefault();
                try {
                    await fetch(toggleForm.action, {
                        method: "POST",
                        headers: { "X-Requested-With": "fetch", "Accept": "application/json" },
                        body: new FormData(toggleForm)
                    });
                } catch (err) {
                    console.warn("⚠ toggle failed:", err);
                }
                if (window.SettingsPageStore && typeof window.SettingsPageStore.hardRefreshNow === "function") {
                    await window.SettingsPageStore.hardRefreshNow({ withBalance: true, lite: false });
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

        try { map[tabId]?.(); }
        catch (e) { console.error(`settings/page.js: init failed for ${tabId}`, e); }
    }

    function boot() {
        const ctx = getCtx();
        if (!ctx) {
            console.warn("[settings/page] root ctx not found");
            return;
        }

        window.StrategySettingsContext = ctx;

        // ✅ compat store
        try { initCompatSettingsPageStore(); } catch (e) {}

        // ✅ авто-обновление DOM
        try { initDomAutoUpdate(); } catch (e) {}

        // ✅ подтверждения через модалку (без window.confirm)
        try { bindDataConfirm(document); } catch (e) {
            console.warn("[settings/page] bindDataConfirm failed:", e);
        }

        const buttons = $all(".tab-btn");
        if (!buttons.length) return;

        buttons.forEach(btn => {
            btn.setAttribute("role", "tab");
            btn.setAttribute("tabindex", "0");

            btn.addEventListener("click", () => {
                const tabId = btn.dataset.tab || "";
                const tabName = normalizeTabName(tabId.replace("tab-", ""));

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

        // ✅ свежий state сразу, без фонового polling
        try { window.SettingsPageStore?.hardRefreshNow?.({ lite: true }); } catch (e) {}
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();



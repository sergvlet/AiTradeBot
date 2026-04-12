"use strict";

/**
 * Trade tab (market.js)
 *
 * FIX:
 * - ctx всегда берётся из актуального DOM/state, а не из старого window.StrategySettingsContext
 * - после смены exchange/network вкладка сама перезагружает symbols/limits
 * - publishState публикует только валидный UiState
 * - initialSymbol не берётся из label/placeholder
 */
window.SettingsTabTrade = (function () {

    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function normalizeUpper(s) {
        return isBlank(s) ? "" : String(s).trim().toUpperCase();
    }

    function normalizeTf(s) {
        return isBlank(s) ? "" : String(s).trim();
    }

    function looksLikeUiState(obj) {
        return !!(obj && typeof obj === "object"
            && ("chatId" in obj) && ("type" in obj) && ("exchange" in obj) && ("network" in obj));
    }

    function getRoot() {
        return document.querySelector(".strategy-settings-page[data-chat-id][data-type]");
    }

    function normalizeNetworkValue(v) {
        if (v === null || v === undefined) return "";
        if (typeof v === "object" && v.name) return String(v.name).trim().toUpperCase();
        return String(v).trim().toUpperCase();
    }

    function syncCtxFromRoot() {
        const root = getRoot();
        const ctx = window.StrategySettingsContext || {};

        if (root) {
            const chatId = root.dataset.chatId || "";
            const type = root.dataset.type || "";
            const exchange = root.dataset.exchange || "";
            const network = root.dataset.network || "";

            if (!isBlank(chatId)) ctx.chatId = String(chatId);
            if (!isBlank(type)) ctx.type = String(type);
            if (!isBlank(exchange)) ctx.exchange = normalizeUpper(exchange);
            if (!isBlank(network)) ctx.network = normalizeNetworkValue(network);
        }

        if (!ctx.baseUrl) ctx.baseUrl = window.location.pathname;

        window.StrategySettingsContext = ctx;
        return ctx;
    }

    function syncCtxFromState(state) {
        if (!looksLikeUiState(state)) return window.StrategySettingsContext || null;

        const ctx = window.StrategySettingsContext || {};

        if (!isBlank(state.chatId)) ctx.chatId = String(state.chatId);
        if (!isBlank(state.type)) ctx.type = String(state.type);
        if (!isBlank(state.exchange)) ctx.exchange = normalizeUpper(state.exchange);
        if (!isBlank(state.network)) ctx.network = normalizeNetworkValue(state.network);
        if (!isBlank(state.accountAsset)) ctx.accountAsset = normalizeUpper(state.accountAsset);
        if (!ctx.baseUrl) ctx.baseUrl = window.location.pathname;

        window.StrategySettingsContext = ctx;

        const root = getRoot();
        if (root) {
            if (!isBlank(ctx.chatId)) root.dataset.chatId = String(ctx.chatId);
            if (!isBlank(ctx.type)) root.dataset.type = String(ctx.type);
            if (!isBlank(ctx.exchange)) root.dataset.exchange = String(ctx.exchange);
            if (!isBlank(ctx.network)) root.dataset.network = String(ctx.network);
        }

        return ctx;
    }

    function ensureCtx() {
        return syncCtxFromRoot();
    }

    function getCtx() {
        return ensureCtx();
    }

    function getCtxKey(ctx) {
        if (!ctx) return "";
        return [
            String(ctx.chatId || ""),
            String(ctx.type || ""),
            String(ctx.exchange || ""),
            String(ctx.network || "")
        ].join("|");
    }

    function publishState(state) {
        if (!looksLikeUiState(state)) return;

        syncCtxFromState(state);

        try {
            if (window.StrategySettingsStore && typeof window.StrategySettingsStore.setState === "function") {
                window.StrategySettingsStore.setState(state);
            } else if (window.StrategySettingsStore && typeof window.StrategySettingsStore.set === "function") {
                window.StrategySettingsStore.set(state);
            }
        } catch (e) {}

        try {
            window.dispatchEvent(new CustomEvent("strategy:state", { detail: state }));
        } catch (e) {}
    }

    function dispatchAccountAssetChanged(asset, source) {
        const a = normalizeUpper(asset || "");
        if (!a) return;
        try {
            window.dispatchEvent(new CustomEvent("strategy:accountAssetChanged", {
                detail: { asset: a, source: source || "trade" }
            }));
        } catch (e) {}
    }

    function ctxQuery() {
        const ctx = getCtx();
        if (!ctx) return "";
        const q = new URLSearchParams();
        if (ctx.chatId) q.set("chatId", String(ctx.chatId));
        if (ctx.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx.network) q.set("network", String(ctx.network));
        q.set("_ts", String(Date.now()));
        return q.toString();
    }

    function marketQuery(accountAsset) {
        const ctx = getCtx();
        const q = new URLSearchParams();
        if (ctx?.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx?.network) q.set("network", String(ctx.network));
        if (!isBlank(accountAsset)) q.set("accountAsset", normalizeUpper(accountAsset));
        return q.toString();
    }

    function setUiValue(el, val) {
        if (!el) return;
        const tag = (el.tagName || "").toUpperCase();
        const v = (val === null || val === undefined || val === "") ? "" : String(val);

        if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
            el.value = v;
        } else {
            el.textContent = (v === "" ? "—" : v);
        }
    }

    function fmtNum(v, digits) {
        if (v === null || v === undefined) return null;
        const n = Number(v);
        if (!Number.isFinite(n)) return null;
        const d = Number.isFinite(digits) ? digits : 8;
        return n.toFixed(d).replace(/\.?0+$/, "");
    }

    function nowMode() {
        const el = byId("advancedControlMode");
        if (el && el.value) return normalizeUpper(el.value);
        if (window.__StrategyControlMode) return normalizeUpper(window.__StrategyControlMode);
        const badge = byId("tradeModeBadge");
        if (badge && badge.textContent) return normalizeUpper(badge.textContent);
        return "MANUAL";
    }

    function getAccountAsset() {
        const sel = byId("accountAssetSelect");
        if (sel && sel.value) return normalizeUpper(sel.value);

        const hid = byId("accountAssetHidden");
        if (hid && hid.value) return normalizeUpper(hid.value);

        const ctx = getCtx();
        if (ctx?.accountAsset) return normalizeUpper(ctx.accountAsset);

        if (sel && sel.options && sel.options.length > 0) {
            return normalizeUpper(sel.options[0].value);
        }
        return "";
    }

    function setAccountAssetUi(asset) {
        const a = normalizeUpper(asset);
        const sel = byId("accountAssetSelect");
        const hid = byId("accountAssetHidden");

        if (sel) {
            sel.value = a || "";
            if (a && sel.value !== a && sel.options && sel.options.length > 0) {
                sel.value = sel.options[0].value;
            }
            if (!sel.value && sel.options && sel.options.length > 0) {
                sel.value = sel.options[0].value;
            }
        }

        const finalA = normalizeUpper(sel ? sel.value : a);

        if (hid) hid.value = finalA || "";

        const hint = byId("assetInHint");
        if (hint) hint.textContent = finalA || "—";

        const ctx = getCtx();
        if (ctx) {
            ctx.accountAsset = finalA || "";
            window.StrategySettingsContext = ctx;
        }
    }

    function rebuildAssetsIfNeeded(assetsUpper) {
        const sel = byId("accountAssetSelect");
        if (!sel || !Array.isArray(assetsUpper) || assetsUpper.length === 0) return;

        const want = assetsUpper.map(normalizeUpper).filter(Boolean);
        if (want.length === 0) return;

        const have = Array.from(sel.options || []).map(o => normalizeUpper(o.value));
        const same = have.length === want.length && want.every(a => have.includes(a));
        if (same) return;

        sel.innerHTML = "";
        want.forEach(a => {
            const opt = document.createElement("option");
            opt.value = a;
            opt.textContent = a;
            sel.appendChild(opt);
        });
    }

    function applyUiState(state) {
        if (!looksLikeUiState(state)) return;

        syncCtxFromState(state);

        if (state.advancedControlMode) {
            window.__StrategyControlMode = normalizeUpper(state.advancedControlMode);
        }

        if (Array.isArray(state.availableAssets)) {
            rebuildAssetsIfNeeded(state.availableAssets);
        }

        const bal = state.selectedBalance || state.balance || null;
        const asset =
            normalizeUpper(
                state.accountAsset ||
                (bal && (bal.asset || bal.currency || bal.code)) ||
                ""
            );

        if (asset) setAccountAssetUi(asset);

        if (bal) {
            const free = (bal.free ?? null);
            const locked = (bal.locked ?? null);
            const total = (Number(free) || 0) + (Number(locked) || 0);

            setUiValue(byId("assetFreeView"), fmtNum(free, 8));
            setUiValue(byId("assetLockedView"), fmtNum(locked, 8));
            setUiValue(byId("assetTotalView"), fmtNum(total, 8));
        }

        if (!isBlank(state.symbol)) {
            setSymbolUi(state.symbol);
        }

        if (!isBlank(state.timeframe)) {
            const tfSelect = byId("tradeTimeframeSelect");
            const tfReadonly = byId("tradeTimeframeReadonly");
            if (tfSelect) tfSelect.value = String(state.timeframe);
            if (tfReadonly) setUiValue(tfReadonly, String(state.timeframe));
        }

        if (state.cachedCandlesLimit !== null && state.cachedCandlesLimit !== undefined) {
            const candlesInput = byId("tradeCachedCandlesLimit");
            if (candlesInput) candlesInput.value = String(state.cachedCandlesLimit);
        }

        setModeUi();
    }

    function setModeUi() {
        const mode = nowMode();

        const badge = byId("tradeModeBadge");
        if (badge) {
            badge.textContent = mode;
            badge.className =
                "badge " +
                (mode === "AI" ? "bg-warning text-dark"
                    : (mode === "HYBRID" ? "bg-info text-dark" : "bg-secondary"));
        }

        const tfSelect = byId("tradeTimeframeSelect");
        const tfReadonly = byId("tradeTimeframeReadonly");
        const tfNote = byId("tradeAiTimeframeNote");
        const candlesInput = byId("tradeCachedCandlesLimit");

        if (mode === "AI") {
            if (tfSelect) tfSelect.classList.add("d-none");
            if (tfReadonly) tfReadonly.classList.remove("d-none");
            if (tfNote) tfNote.classList.remove("d-none");

            const curTf = tfSelect ? tfSelect.value : "";
            setUiValue(tfReadonly, curTf || "—");

            if (candlesInput) candlesInput.disabled = true;
        } else {
            if (tfSelect) tfSelect.classList.remove("d-none");
            if (tfReadonly) tfReadonly.classList.add("d-none");
            if (tfNote) tfNote.classList.add("d-none");

            if (candlesInput) candlesInput.disabled = false;
        }
    }

    function setLimitsUiEmpty() {
        setUiValue(byId("exMinNotional"), "—");
        setUiValue(byId("exMinNotionalScope"), "—");
        setUiValue(byId("exStepSize"), "—");
        setUiValue(byId("exStepSizeScope"), "—");
        setUiValue(byId("exTickSize"), "—");
        setUiValue(byId("exTickSizeScope"), "—");
    }

    function applyLimitsFromDescriptor(d) {
        if (!d) { setLimitsUiEmpty(); return; }

        const root = d.filters ? d.filters : d;

        const minNotional = root.minNotional ?? root.min_notional ?? root.minQuote ?? null;
        const stepSize = root.stepSize ?? root.qtyStep ?? root.lotStepSize ?? null;
        const tickSize = root.tickSize ?? root.priceStep ?? null;

        setUiValue(byId("exMinNotional"), minNotional !== null ? String(minNotional) : "—");
        setUiValue(byId("exMinNotionalScope"), root.minNotionalScope || d.scope || "—");

        setUiValue(byId("exStepSize"), stepSize !== null ? String(stepSize) : "—");
        setUiValue(byId("exStepSizeScope"), root.stepSizeScope || d.scope || "—");

        setUiValue(byId("exTickSize"), tickSize !== null ? String(tickSize) : "—");
        setUiValue(byId("exTickSizeScope"), root.tickSizeScope || d.scope || "—");
    }

    async function loadLimits(symbol) {
        const ctx = getCtx();
        if (!ctx?.exchange || !ctx?.network) return;

        const sym = normalizeUpper(symbol);
        const asset = getAccountAsset();

        if (!sym || !asset) {
            setLimitsUiEmpty();
            return;
        }

        const url =
            `/api/market/symbol-info?${marketQuery(asset)}&symbol=${encodeURIComponent(sym)}`;

        try {
            const d = await window.SettingsApi.getJson(url);
            applyLimitsFromDescriptor(d);
        } catch (e) {
            console.error("[trade] loadLimits failed:", e);
            setLimitsUiEmpty();
        }
    }

    function setSymbolUi(symbol) {
        const label = byId("symbolLabel");
        const hidden = byId("symbolHidden");
        const sym = normalizeUpper(symbol);
        if (label) label.textContent = sym || "Выберите торговую пару";
        if (hidden) hidden.value = sym || "";
    }

    function buildSymbolItem(symbol, price, changePct) {
        const li = document.createElement("li");
        const a = document.createElement("a");
        a.className = "dropdown-item d-flex justify-content-between align-items-center";
        a.href = "#";

        const left = document.createElement("span");
        left.className = "fw-semibold";
        left.textContent = symbol;

        const right = document.createElement("span");
        right.className = "text-muted small";
        const p = fmtNum(price, 6);
        const c = fmtNum(changePct, 2);
        right.textContent = (p ? p : "—") + (c ? (" • " + c + "%") : "");

        a.appendChild(left);
        a.appendChild(right);
        li.appendChild(a);
        return { li, a };
    }

    function getActiveSymbolMode() {
        const host = byId("tradeSymbolModes");
        if (!host) return "POPULAR";
        const active = host.querySelector("[data-symbol-mode].active");
        return active ? (active.getAttribute("data-symbol-mode") || "POPULAR") : "POPULAR";
    }

    function setActiveSymbolMode(mode) {
        const host = byId("tradeSymbolModes");
        if (!host) return;
        host.querySelectorAll("[data-symbol-mode]").forEach(btn => {
            btn.classList.toggle("active", (btn.getAttribute("data-symbol-mode") === mode));
        });
    }

    async function fetchSymbols(mode) {
        const ctx = getCtx();
        if (!ctx?.exchange || !ctx?.network) return [];

        const asset = getAccountAsset();
        if (!asset) return [];

        const url =
            `/api/market/symbols?${marketQuery(asset)}&mode=${encodeURIComponent(mode || "POPULAR")}`;

        try {
            const d = await window.SettingsApi.getJson(url);
            if (Array.isArray(d)) return d;
            if (Array.isArray(d.items)) return d.items;
            return [];
        } catch (e) {
            console.error("[trade] fetchSymbols failed:", e);
            return [];
        }
    }

    async function saveTradeSettings() {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return null;

        const asset = getAccountAsset();
        const symbol = normalizeUpper(byId("symbolHidden")?.value || "");
        const tf = normalizeTf(byId("tradeTimeframeSelect")?.value || "");
        const candles = (byId("tradeCachedCandlesLimit")?.value || "").trim();

        const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`;

        const payload = {
            saveScope: "trade",
            tab: "trade",
            exchange: ctx.exchange,
            network: ctx.network,
            accountAsset: asset,
            symbol: symbol,
            timeframe: tf,
            cachedCandlesLimit: candles
        };

        return await window.SettingsApi.postForm(url, payload);
    }

    let started = false;

    function init() {
        if (started) return;

        const api = window.SettingsApi;
        if (!api?.getJson || !api?.postForm) {
            console.warn("[trade] SettingsApi not ready -> skip init (will retry)");
            return;
        }

        const ctx = ensureCtx();
        if (!ctx?.chatId || !ctx?.type) {
            console.warn("[trade] ctx not ready -> skip init (will retry)");
            return;
        }

        const list = byId("symbolList");
        const symbolHidden = byId("symbolHidden");
        const tfSelect = byId("tradeTimeframeSelect");
        const candlesInput = byId("tradeCachedCandlesLimit");
        const assetSelect = byId("accountAssetSelect");

        if (!assetSelect || !symbolHidden || !tfSelect || !candlesInput) {
            console.warn("[trade] required DOM not ready -> skip init (will retry)");
            return;
        }

        started = true;

        const saveState = byId("tradeSaveState");
        const saveMeta = byId("tradeSaveMeta");

        function setSave(text, kind) {
            if (saveState) {
                saveState.textContent = text || "Готово";
                saveState.className =
                    "badge " +
                    (kind === "ok" ? "bg-success"
                        : kind === "err" ? "bg-danger"
                            : kind === "info" ? "bg-info text-dark"
                                : "bg-secondary");
            }
            if (saveMeta) saveMeta.textContent = "";
        }

        let timer = null;
        let inFlight = false;
        let contextReloadInFlight = false;
        let lastCtxKey = getCtxKey(getCtx());

        async function doSave() {
            if (inFlight) return;

            inFlight = true;
            setSave("Сохраняю…", "info");

            try {
                const state = await saveTradeSettings();

                if (looksLikeUiState(state)) {
                    applyUiState(state);
                    publishState(state);
                    dispatchAccountAssetChanged(state.accountAsset || getAccountAsset(), "trade_save_ok");
                    lastCtxKey = getCtxKey(getCtx());
                }

                setSave("Сохранено", "ok");
            } catch (e) {
                setSave("Ошибка", "err");
                console.error("[trade] save failed:", e);
            } finally {
                inFlight = false;
                setTimeout(() => setSave("Готово", "idle"), 900);
            }
        }

        function scheduleSave(ms) {
            clearTimeout(timer);
            timer = setTimeout(doSave, (ms ?? 250));
        }

        async function reloadSymbols() {
            if (!list) return;

            const asset = getAccountAsset();
            setAccountAssetUi(asset);

            const finalAsset = getAccountAsset();
            if (!finalAsset) {
                list.innerHTML = `<li><span class="dropdown-item text-muted">Выберите актив</span></li>`;
                return;
            }

            list.innerHTML = `<li><span class="dropdown-item text-muted">Загрузка…</span></li>`;

            const mode = getActiveSymbolMode();
            const items = await fetchSymbols(mode);

            if (!items.length) {
                list.innerHTML = `<li><span class="dropdown-item text-muted">Нет данных</span></li>`;
                return;
            }

            list.innerHTML = "";
            items.forEach(it => {
                const sym = normalizeUpper(it.symbol || it.s || "");
                const price = it.lastPrice ?? it.price ?? it.p ?? null;
                const change = it.changePct ?? it.c ?? null;
                if (!sym) return;

                const { li, a } = buildSymbolItem(sym, price, change);
                a.addEventListener("click", async (e) => {
                    e.preventDefault();
                    setSymbolUi(sym);
                    scheduleSave(120);
                    await loadLimits(sym);
                });

                list.appendChild(li);
            });
        }

        async function reloadAfterContextChange(state) {
            if (contextReloadInFlight) return;
            contextReloadInFlight = true;

            try {
                if (looksLikeUiState(state)) {
                    applyUiState(state);
                }

                await reloadSymbols();

                const currentSymbol = normalizeUpper(symbolHidden?.value || "");
                if (currentSymbol) {
                    await loadLimits(currentSymbol);
                } else {
                    setLimitsUiEmpty();
                }
            } catch (e) {
                console.error("[trade] reloadAfterContextChange failed:", e);
            } finally {
                contextReloadInFlight = false;
            }
        }

        const modeHost = byId("tradeSymbolModes");
        if (modeHost) {
            modeHost.querySelectorAll("[data-symbol-mode]").forEach(btn => {
                btn.addEventListener("click", async () => {
                    const m = btn.getAttribute("data-symbol-mode") || "POPULAR";
                    setActiveSymbolMode(m);
                    await reloadSymbols();
                });
            });
        }

        assetSelect.addEventListener("change", async () => {
            const asset = normalizeUpper(assetSelect.value || "");
            setAccountAssetUi(asset);

            dispatchAccountAssetChanged(asset, "trade_asset_change");

            setSymbolUi("");
            setLimitsUiEmpty();

            scheduleSave(10);
            await reloadSymbols();
        });

        tfSelect.addEventListener("change", () => scheduleSave(180));

        if (candlesInput) {
            let t = null;
            candlesInput.addEventListener("input", () => {
                if (nowMode() === "AI") return;
                if (t) clearTimeout(t);
                t = setTimeout(() => scheduleSave(250), 450);
            });
            candlesInput.addEventListener("change", () => {
                if (nowMode() === "AI") return;
                scheduleSave(180);
            });
        }

        window.addEventListener("strategy:controlModeChanged", () => setModeUi());

        setModeUi();
        setLimitsUiEmpty();
        setAccountAssetUi(getAccountAsset());

        const initialSymbol = normalizeUpper(symbolHidden?.value || "");
        if (initialSymbol) {
            loadLimits(initialSymbol).catch(() => {});
        }

        reloadSymbols().catch(() => {});

        const onState = async (state) => {
            try {
                if (!looksLikeUiState(state)) return;

                const before = lastCtxKey;

                applyUiState(state);

                const after = getCtxKey(getCtx());
                if (before !== after) {
                    lastCtxKey = after;
                    await reloadAfterContextChange(state);
                } else {
                    lastCtxKey = after;
                }
            } catch (e) {
                console.error("[trade] onState failed:", e);
            }
        };

        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            window.StrategySettingsStore.subscribe(onState);
        }
        window.addEventListener("strategy:state", (ev) => onState(ev?.detail));
    }

    return { init };
})();

window.SettingsTabMarket = window.SettingsTabTrade;
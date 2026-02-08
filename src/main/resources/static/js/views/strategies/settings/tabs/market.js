"use strict";

/**
 * Trade tab (market.js)
 *
 * ✅ ВАЖНО:
 * - после saveScope=trade мы ЯВНО публикуем strategy:state (Store + DOM event)
 * - при смене актива кидаем strategy:accountAssetChanged (Risk ловит и обновляет баланс/preview без F5)
 * - ctxQuery() содержит _ts чтобы не получать кэшированный state
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

    // ✅ гарантируем StrategySettingsContext из data-* корня страницы
    function ensureCtx() {
        if (window.StrategySettingsContext && window.StrategySettingsContext.chatId) return window.StrategySettingsContext;

        const root = document.querySelector(".strategy-settings-page[data-chat-id][data-type]");
        if (!root) return window.StrategySettingsContext || null;

        const ctx = window.StrategySettingsContext || {};
        ctx.chatId = ctx.chatId || root.dataset.chatId;
        ctx.type = ctx.type || root.dataset.type;
        ctx.exchange = ctx.exchange || root.dataset.exchange;
        ctx.network = ctx.network || root.dataset.network;

        window.StrategySettingsContext = ctx;
        return ctx;
    }

    function getCtx() {
        return ensureCtx();
    }

    // ✅ publish helpers (чтобы Risk/General гарантированно получали state)
    function publishState(state) {
        if (!state) return;

        try {
            if (window.StrategySettingsStore && typeof window.StrategySettingsStore.setState === "function") {
                window.StrategySettingsStore.setState(state);
            }
        } catch (e) {}

        try {
            window.dispatchEvent(new CustomEvent("strategy:state", { detail: state }));
        } catch (e) {}
    }

    function dispatchAccountAssetChanged(asset, source) {
        try {
            window.dispatchEvent(new CustomEvent("strategy:accountAssetChanged", {
                detail: { asset: normalizeUpper(asset || ""), source: source || "trade" }
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
        // ✅ cache-buster
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

        // ⚠️ В input нельзя пихать "—"
        const v = (val === null || val === undefined || val === "") ? "" : String(val);

        if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") el.value = v;
        else el.textContent = (v === "" ? "—" : v);
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

        // если пусто — берём первый option
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
        if (ctx) ctx.accountAsset = finalA || "";
    }

    // =====================================================
    // UI STATE (auto-refresh между вкладками)
    // =====================================================
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
        if (!state) return;

        // список активов может поменяться
        if (Array.isArray(state.availableAssets)) {
            rebuildAssetsIfNeeded(state.availableAssets);
        }

        // выбранный актив
        const bal = state.selectedBalance || state.balance || null;
        const asset =
            normalizeUpper(
                state.accountAsset ||
                (bal && (bal.asset || bal.currency || bal.code)) ||
                ""
            );

        if (asset) setAccountAssetUi(asset);

        // баланс (обновляем общие поля на странице)
        if (bal) {
            const free = (bal.free ?? null);
            const locked = (bal.locked ?? null);

            const total = (Number(free) || 0) + (Number(locked) || 0);

            setUiValue(byId("assetFreeView"), fmtNum(free, 8));
            setUiValue(byId("assetLockedView"), fmtNum(locked, 8));
            setUiValue(byId("assetTotalView"), fmtNum(total, 8));
        }
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

    // -----------------------------
    // Limits UI
    // -----------------------------
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

        if (!sym || !asset) { setLimitsUiEmpty(); return; }

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

    // -----------------------------
    // Symbol UI
    // -----------------------------
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

    // -----------------------------
    // Save
    // -----------------------------
    async function saveTradeSettings() {
        const ctx = getCtx();
        if (!ctx?.type) return null;

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

        // ✅ не надеемся на SettingsApi: сами разошлем state
        return await window.SettingsApi.postForm(url, payload);
    }

    // -----------------------------
    // Init
    // -----------------------------
    let started = false;

    function init() {
        if (started) return;
        started = true;

        ensureCtx();

        const list = byId("symbolList");
        const symbolHidden = byId("symbolHidden");
        const tfSelect = byId("tradeTimeframeSelect");
        const candlesInput = byId("tradeCachedCandlesLimit");
        const assetSelect = byId("accountAssetSelect");

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

        async function doSave() {
            if (inFlight) return;

            inFlight = true;
            setSave("Сохраняю…", "info");
            try {
                const state = await saveTradeSettings();

                if (state) {
                    applyUiState(state);

                    // ✅ ключевое: раздать другим вкладкам
                    publishState(state);

                    // ✅ и отдельное событие про смену актива (на случай если state не поймали)
                    dispatchAccountAssetChanged(state.accountAsset || getAccountAsset(), "trade_save_ok");
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
            timer = setTimeout(doSave, ms || 250);
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

        // mode buttons
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

        // accountAsset change
        if (assetSelect) {
            assetSelect.addEventListener("change", async () => {
                const asset = normalizeUpper(assetSelect.value || "");
                setAccountAssetUi(asset);

                // ✅ Событие сразу (Risk может пересчитать UI и дернуть refresh)
                dispatchAccountAssetChanged(asset, "trade_asset_change");

                setSymbolUi("");
                setLimitsUiEmpty();

                // ✅ Сразу сохраняем, чтобы сервер вернул state с балансом нового актива
                scheduleSave(10);

                await reloadSymbols();
            });
        }

        // timeframe
        if (tfSelect) {
            tfSelect.addEventListener("change", () => scheduleSave(180));
        }

        // candles
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

        // control mode changed
        window.addEventListener("strategy:controlModeChanged", () => setModeUi());

        // first load
        setModeUi();
        setLimitsUiEmpty();

        setAccountAssetUi(getAccountAsset());

        const initialSymbol = normalizeUpper(symbolHidden?.value || byId("symbolLabel")?.textContent || "");
        if (initialSymbol) loadLimits(initialSymbol).catch(() => {});

        reloadSymbols().catch(() => {});

        // =====================================================
        // авто-обновление: другие вкладки меняют state
        // =====================================================
        const onState = (state) => {
            try { applyUiState(state); } catch (e) {}
        };

        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            window.StrategySettingsStore.subscribe(onState);
        }
        window.addEventListener("strategy:state", (ev) => onState(ev?.detail));
    }

    return { init };
})();

window.SettingsTabMarket = window.SettingsTabTrade;

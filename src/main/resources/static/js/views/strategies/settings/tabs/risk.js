"use strict";

window.SettingsTabRisk = (function () {

    let started = false;

    // текущее значение выбранного актива
    let currentAsset = "USDT";

    // анти-спам для refresh
    let refreshInFlight = false;
    let lastRefreshAt = 0;

    // debounce для автосейва если мы поджимаем FIX
    let saveTimer = null;

    const DEBUG = false;
    function dbg(...a) { if (DEBUG) console.log("[risk]", ...a); }

    function getCtx() { return window.StrategySettingsContext || null; }
    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }
    function normalizeUpper(s) {
        return isBlank(s) ? "" : String(s).trim().toUpperCase();
    }
    function normalizeMode(v) {
        const m = String(v || "ALL").trim().toUpperCase();
        return ["ALL", "FIX", "PCT"].includes(m) ? m : "ALL";
    }
    function toNum(v) {
        if (v === null || v === undefined) return null;
        const s = String(v).trim().replace(",", ".");
        if (!s) return null;
        const n = Number(s);
        return Number.isFinite(n) ? n : null;
    }
    function fmt(n) {
        if (n === null || n === undefined || !Number.isFinite(n)) return "—";
        return (Math.round(n * 100) / 100).toFixed(2);
    }
    function fmtNum(v, digits) {
        if (v === null || v === undefined) return null;
        const n = Number(v);
        if (!Number.isFinite(n)) return null;
        const d = Number.isFinite(digits) ? digits : 8;
        return n.toFixed(d).replace(/\.?0+$/, "");
    }

    // --- ui state publish (только валидный) ---
    function looksLikeUiState(obj) {
        return !!(obj && typeof obj === "object"
            && ("chatId" in obj) && ("type" in obj) && ("exchange" in obj) && ("network" in obj));
    }

    function ensureStrategyStore() {
        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            return window.StrategySettingsStore;
        }
        const listeners = new Set();
        const store = {
            _state: null,
            getState() { return this._state; },
            setState(state) {
                this._state = state;
                listeners.forEach(fn => { try { fn(state); } catch (e) {} });
                try { window.dispatchEvent(new CustomEvent("strategy:state", { detail: state })); } catch (e) {}
            },
            subscribe(fn) {
                if (typeof fn !== "function") return () => {};
                listeners.add(fn);
                if (this._state) { try { fn(this._state); } catch (e) {} }
                return () => listeners.delete(fn);
            }
        };
        window.StrategySettingsStore = store;
        return store;
    }

    function publishState(state) {
        if (!looksLikeUiState(state)) return;
        try { ensureStrategyStore().setState(state); } catch (e) {}
        try { window.dispatchEvent(new CustomEvent("strategy:state", { detail: state })); } catch (e) {}
    }

    function badge(text) {
        const el = byId("riskSaveMeta");
        if (el) el.textContent = text || "";
    }

    function setState(txt, ok) {
        const el = byId("riskSaveState");
        if (!el) return;

        el.textContent = txt;

        el.classList.remove("bg-secondary", "bg-success", "bg-danger", "bg-info", "text-dark");
        if (ok === true) el.classList.add("bg-success");
        else if (ok === false) el.classList.add("bg-danger");
        else { el.classList.add("bg-secondary"); }
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

    // --- confirm tracker sync ---
    function syncConfirmPrev(el) {
        if (!el) return;
        if (String(el.getAttribute("data-confirm") || "").toLowerCase() !== "true") return;

        const now = (el.type === "checkbox") ? String(!!el.checked) : String(el.value ?? "");
        el.setAttribute("data-prev", now);
    }

    // если FormChangeTracker не привязан — data-prev не будет, тогда считаем change подтверждённым
    function isConfirmedChange(el) {
        if (!el) return true;
        const prev = el.getAttribute("data-prev");
        if (prev === null || prev === undefined) return true;
        const now = (el.type === "checkbox") ? String(!!el.checked) : String(el.value ?? "");
        return prev === now;
    }

    function applyModeUi(mode, asset) {
        const valueGroup = byId("riskValueGroup");
        const valueLabel = byId("riskValueLabel");
        const valueHint  = byId("riskValueHint");
        const unit       = byId("riskValueUnit");
        const quick      = byId("riskQuickButtons");
        const help       = byId("riskModeHelp");

        const isAll = mode === "ALL";
        if (valueGroup) valueGroup.classList.toggle("d-none", isAll);
        if (valueLabel) valueLabel.classList.toggle("d-none", isAll);
        if (valueHint)  valueHint.classList.toggle("d-none", isAll);
        if (quick)      quick.classList.toggle("d-none", isAll);

        if (help) {
            help.textContent =
                isAll ? "ALL — стратегия может использовать весь доступный баланс выбранного актива." :
                    (mode === "FIX" ? "FIX — использует не больше указанной суммы (но не больше доступного баланса)." :
                        "PCT — использует процент от доступного баланса (например 25%).");
        }

        if (mode === "FIX") {
            if (unit) unit.textContent = asset;
            if (valueHint) valueHint.textContent = "Укажи сумму в " + asset + " (будет ограничена доступным балансом).";
        } else if (mode === "PCT") {
            if (unit) unit.textContent = "%";
            if (valueHint) valueHint.textContent = "Укажи процент от доступного баланса (1–100).";
        }
    }

    function calcPreview() {
        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");

        const freeValueEl = byId("riskFreeBalanceValue");
        const assetEl = byId("riskSelectedAsset");
        const effEl   = byId("riskEffectiveAmount");

        currentAsset = (assetEl && assetEl.value)
            ? normalizeUpper(assetEl.value)
            : (currentAsset || "USDT");

        const free  = toNum(freeValueEl ? freeValueEl.value : null);
        const mode  = normalizeMode(modeSel ? modeSel.value : "ALL");
        const raw   = toNum(valInp ? valInp.value : null);

        let eff = null;

        if (mode === "ALL") {
            eff = free;
        } else if (mode === "FIX") {
            const v = (raw !== null && raw > 0) ? raw : null;
            eff = v === null ? null : (free !== null ? Math.min(free, v) : v);
        } else { // PCT
            const p = (raw !== null && raw > 0) ? Math.min(raw, 100) : null;
            eff = (p === null || free === null) ? null : (free * (p / 100.0));
        }

        if (effEl) effEl.textContent = (eff !== null ? (fmt(eff) + " " + currentAsset) : "—");

        const badgeEl = byId("riskModeBadge");
        if (badgeEl) badgeEl.textContent = mode;
    }

    function clampRiskIfNeeded() {
        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");
        const freeValueEl = byId("riskFreeBalanceValue");

        if (!modeSel || !valInp || !freeValueEl) return false;

        const mode = normalizeMode(modeSel.value);
        const free = toNum(freeValueEl.value);
        const raw  = toNum(valInp.value);

        let changed = false;

        if (mode === "FIX" && free !== null && raw !== null && raw > free) {
            valInp.value = String(free);
            changed = true;
        }
        if (mode === "PCT" && raw !== null && raw > 100) {
            valInp.value = "100";
            changed = true;
        }

        return changed;
    }

    function scheduleSaveRisk() {
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(() => {
            saveTimer = null;
            saveRisk().catch(() => {});
        }, 250);
    }

    // =====================================================
    // ✅ подтянуть свежий UI-state с сервера (через SettingsApi)
    // =====================================================
    async function refreshUiStateFromServer(reason) {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;
        if (!window.SettingsApi?.getJson) return;

        const now = Date.now();
        if (refreshInFlight) return;
        if (now - lastRefreshAt < 200) return;
        lastRefreshAt = now;

        refreshInFlight = true;
        try {
            const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config/state?${ctxQuery()}`;
            dbg("refresh:", reason, url);

            const state = await window.SettingsApi.getJson(url);
            if (looksLikeUiState(state)) publishState(state);

        } catch (e) {
            console.warn("[risk] refreshUiStateFromServer failed:", e);
        } finally {
            refreshInFlight = false;
        }
    }

    // =====================================================
    // ✅ save (через SettingsApi.postForm => CSRF + login/403 детект)
    // =====================================================
    async function saveRisk() {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;
        if (!window.SettingsApi?.postForm) return;

        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");

        const payload = {
            saveScope: "risk",
            tab: "risk",
            exchange: ctx.exchange || "",
            network: ctx.network || "",
            capitalMode: normalizeMode(modeSel?.value || "ALL"),
            capitalValue: String(valInp?.value ?? "").trim(),
            accountAsset: normalizeUpper(byId("riskSelectedAsset")?.value || currentAsset || "")
        };

        setState("Сохраняю…", null);

        try {
            const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`;
            const state = await window.SettingsApi.postForm(url, payload);

            setState("Сохранено", true);
            badge(new Date().toLocaleTimeString());

            if (looksLikeUiState(state)) {
                publishState(state);
            } else {
                // если сервер вернул {ok:true} — добираем state отдельно
                refreshUiStateFromServer("post_ok_no_state").catch(() => {});
            }
        } catch (e) {
            setState("Ошибка", false);
            badge(String(e?.message || "ошибка"));
            console.error("[risk] save failed:", e);
        }
    }

    function pickModeFromState(state) {
        return normalizeMode(
            state?.capitalMode ||
            state?.riskCapitalMode ||
            state?.strategy?.capitalMode ||
            state?.settings?.capitalMode ||
            "ALL"
        );
    }

    function pickValueFromState(state) {
        const v =
            state?.capitalValue ??
            state?.riskCapitalValue ??
            state?.strategy?.capitalValue ??
            state?.settings?.capitalValue ??
            null;
        return (v === null || v === undefined) ? "" : String(v);
    }

    function applyUiState(state) {
        if (!looksLikeUiState(state)) return;

        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");

        const freeValueEl = byId("riskFreeBalanceValue");
        const freeTextEl  = byId("riskFreeBalanceText");
        const assetEl     = byId("riskSelectedAsset");
        const assetTextEl = byId("riskAssetText");

        const bal = state.selectedBalance || state.balance || null;

        // ✅ asset: сначала accountAsset, потом selectedBalance.asset
        const nextAsset = normalizeUpper(state.accountAsset || (bal && (bal.asset || bal.currency || bal.code)));
        if (nextAsset) {
            const changed = nextAsset !== currentAsset;
            currentAsset = nextAsset;

            if (assetEl) assetEl.value = nextAsset;
            if (assetTextEl) assetTextEl.textContent = nextAsset;

            // держим ctx в актуале (чтобы другие вкладки брали одно и то же)
            const ctx = getCtx();
            if (ctx) ctx.accountAsset = nextAsset;

            if (changed) dbg("asset changed ->", nextAsset);
        }

        // ✅ free balance
        if (bal && bal.free !== undefined && bal.free !== null) {
            if (freeValueEl) freeValueEl.value = String(bal.free);
            if (freeTextEl) freeTextEl.textContent = fmtNum(bal.free, 8) || "—";
        }

        // ✅ mode/value из state (важно: если AI/тюнер меняет)
        const modeFromState = pickModeFromState(state);
        const valFromState  = pickValueFromState(state);

        if (modeSel && modeSel.value !== modeFromState) modeSel.value = modeFromState;
        if (valInp && String(valInp.value || "") !== String(valFromState || "")) valInp.value = String(valFromState || "");

        // синхронизируем data-prev, чтобы confirm не срабатывал на “серверных” изменениях
        syncConfirmPrev(modeSel);
        syncConfirmPrev(valInp);

        applyModeUi(modeFromState, currentAsset || "USDT");

        const clamped = clampRiskIfNeeded();
        calcPreview();

        // если free уменьшился и FIX стал выше free — поджимаем и сохраняем один раз
        if (clamped) scheduleSaveRisk();
    }

    // =====================================================
    // ✅ ловим смену accountAsset (основной путь — событие из Trade)
    // =====================================================
    function attachAccountAssetListeners() {
        // 1) from Trade (правильный путь)
        window.addEventListener("strategy:accountAssetChanged", (ev) => {
            const next = normalizeUpper(ev?.detail?.asset || "");
            if (!next) return;

            dbg("strategy:accountAssetChanged ->", next);

            currentAsset = next;

            const assetEl = byId("riskSelectedAsset");
            const assetTextEl = byId("riskAssetText");
            if (assetEl) assetEl.value = next;
            if (assetTextEl) assetTextEl.textContent = next;

            calcPreview();
            setTimeout(() => refreshUiStateFromServer("accountAsset_event"), 60);
        });

        // 2) fallback: если кто-то поменял select в DOM без события
        const selectors = [
            '[name="accountAsset"]',
            '#accountAsset',
            '#accountAssetSelect',
            '[data-role="accountAsset"]'
        ];
        function isAccountAssetEl(el) {
            if (!el || !el.matches) return false;
            return selectors.some(sel => { try { return el.matches(sel); } catch (e) { return false; } });
        }
        document.addEventListener("change", (e) => {
            const t = e.target;
            if (!isAccountAssetEl(t)) return;

            const next = normalizeUpper(t.value);
            if (!next) return;

            dbg("accountAsset changed in DOM ->", next);

            currentAsset = next;

            const assetEl = byId("riskSelectedAsset");
            const assetTextEl = byId("riskAssetText");
            if (assetEl) assetEl.value = next;
            if (assetTextEl) assetTextEl.textContent = next;

            calcPreview();
            setTimeout(() => refreshUiStateFromServer("accountAsset_dom_change"), 80);
        });
    }

    function init() {
        if (started) return;

        // не стартуем пока нет ctx/api/dom
        const ctx = getCtx();
        if (!ctx?.chatId || !ctx?.type) {
            dbg("ctx not ready -> skip init (retry)");
            return;
        }
        if (!window.SettingsApi?.postForm || !window.SettingsApi?.getJson) {
            dbg("SettingsApi not ready -> skip init (retry)");
            return;
        }

        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");
        const form    = byId("riskForm");
        if (!form || !modeSel || !valInp) {
            dbg("risk DOM not ready -> skip init (retry)");
            return;
        }

        started = true;
        dbg("init");

        const initModeEl  = byId("riskInitMode");
        const initValueEl = byId("riskInitValue");
        const assetEl = byId("riskSelectedAsset");

        currentAsset = (assetEl && assetEl.value) ? normalizeUpper(assetEl.value) : (currentAsset || "USDT");

        const initMode = normalizeMode(initModeEl ? initModeEl.value : "ALL");
        const initVal  = (initValueEl && initValueEl.value) ? String(initValueEl.value).trim() : "";

        modeSel.value = initMode;
        valInp.value  = initVal;

        // синк для confirm
        syncConfirmPrev(modeSel);
        syncConfirmPrev(valInp);

        applyModeUi(initMode, currentAsset);
        calcPreview();

        // подписка на общий state
        const onState = (state) => {
            try { applyUiState(state); } catch (e) {
                console.warn("[risk] applyUiState failed:", e);
            }
        };

        ensureStrategyStore().subscribe(onState);
        window.addEventListener("strategy:state", (ev) => onState(ev && ev.detail));

        attachAccountAssetListeners();

        // первичный refresh
        setTimeout(() => refreshUiStateFromServer("init"), 0);

        // =====================================================
        // events (ВАЖНО: save только на confirmed-change или когда confirm не привязан)
        // =====================================================

        // mode: мгновенно обновляем UI на change, а сохраняем после подтверждения
        modeSel.addEventListener("change", () => {
            const m = normalizeMode(modeSel.value);
            if (m === "ALL") valInp.value = "";
            applyModeUi(m, currentAsset);
            calcPreview();

            // если confirm tracker не подключён — change считаем подтверждённым
            setTimeout(() => {
                if (isConfirmedChange(modeSel)) saveRisk().catch(() => {});
            }, 0);
        });
        modeSel.addEventListener("confirmed-change", async () => {
            const m = normalizeMode(modeSel.value);
            if (m === "ALL") valInp.value = "";
            syncConfirmPrev(modeSel);
            syncConfirmPrev(valInp);
            applyModeUi(m, currentAsset);
            calcPreview();
            await saveRisk();
        });

        // value: превью на input, сохранение — после подтверждения
        valInp.addEventListener("input", () => calcPreview());
        valInp.addEventListener("change", () => {
            clampRiskIfNeeded();
            calcPreview();

            setTimeout(() => {
                if (isConfirmedChange(valInp)) saveRisk().catch(() => {});
            }, 0);
        });
        valInp.addEventListener("confirmed-change", async () => {
            clampRiskIfNeeded();
            syncConfirmPrev(valInp);
            calcPreview();
            await saveRisk();
        });

        // quick buttons
        document.querySelectorAll("[data-risk-set]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const m = normalizeMode(btn.getAttribute("data-risk-mode") || "FIX");
                const v = btn.getAttribute("data-risk-set") || "";

                modeSel.value = m;
                applyModeUi(m, currentAsset);

                valInp.value = v;

                // sync confirm prev, чтобы после клика не требовало подтверждение "с нуля"
                syncConfirmPrev(modeSel);
                syncConfirmPrev(valInp);

                clampRiskIfNeeded();
                calcPreview();
                await saveRisk();
            });
        });

        console.log("[risk] init OK");
    }

    // =====================================================
    // ✅ АВТО-СТАРТ: если табы ленивые — ждём DOM
    // =====================================================
    function autoBoot() {
        // уже есть элементы риска — стартуем
        if (byId("riskForm") || byId("riskModeSelect") || byId("riskValueInput")) {
            init();
            return;
        }

        const mo = new MutationObserver(() => {
            if (byId("riskForm") || byId("riskModeSelect") || byId("riskValueInput")) {
                mo.disconnect();
                init();
            }
        });
        mo.observe(document.documentElement, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", autoBoot);
    } else {
        autoBoot();
    }

    return { init, applyUiState };
})();

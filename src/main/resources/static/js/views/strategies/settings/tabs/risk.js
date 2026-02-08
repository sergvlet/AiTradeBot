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

    // debug (включи true если надо увидеть что реально происходит)
    const DEBUG = false;
    function dbg(...a) { if (DEBUG) console.log("[risk]", ...a); }

    function getCtx() { return window.StrategySettingsContext || null; }
    function byId(id) { return document.getElementById(id); }

    function badge(text) {
        const el = byId("riskSaveMeta");
        if (el) el.textContent = text || "";
    }

    function setState(txt, ok) {
        const el = byId("riskSaveState");
        if (!el) return;

        el.textContent = txt;

        el.classList.remove("bg-secondary", "bg-success", "bg-danger");
        if (ok === true) el.classList.add("bg-success");
        else if (ok === false) el.classList.add("bg-danger");
        else el.classList.add("bg-secondary");
    }

    // ✅ ВАЖНО: публикуем state ВСЕГДА и в Store, и как DOM event (чтобы ничего не “молчало”)
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

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function normalizeUpper(s) {
        return isBlank(s) ? "" : String(s).trim().toUpperCase();
    }

    function ctxQuery() {
        const ctx = getCtx();
        if (!ctx) return "";
        const q = new URLSearchParams();
        if (ctx.chatId) q.set("chatId", String(ctx.chatId));
        if (ctx.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx.network) q.set("network", String(ctx.network));
        // cache-buster
        q.set("_ts", String(Date.now()));
        return q.toString();
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

        const freeValueEl = byId("riskFreeBalanceValue");   // input/hidden (число)
        const assetEl = byId("riskSelectedAsset");          // input/hidden (asset)
        const effEl   = byId("riskEffectiveAmount");        // span/text

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
    // ✅ подтянуть свежий UI-state с сервера
    // =====================================================
    async function refreshUiStateFromServer(reason) {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;

        const now = Date.now();
        if (refreshInFlight) return;
        if (now - lastRefreshAt < 200) return;
        lastRefreshAt = now;

        refreshInFlight = true;
        try {
            const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config/state?${ctxQuery()}`;
            dbg("refresh:", reason, url);

            const res = await fetch(url, { method: "GET", cache: "no-store" });
            if (!res.ok) return;

            const state = await res.json().catch(() => null);
            if (state) publishState(state);

        } catch (e) {
            console.warn("[risk] refreshUiStateFromServer failed:", e);
        } finally {
            refreshInFlight = false;
        }
    }

    async function saveRisk() {
        const ctx = getCtx();
        if (!ctx) return;

        const form = byId("riskForm");
        if (!form) return;

        const fd = new FormData(form);
        fd.set("chatId", String(ctx.chatId));
        fd.set("saveScope", "risk");
        fd.set("tab", "risk");

        if (ctx.exchange) fd.set("exchange", String(ctx.exchange));
        if (ctx.network) fd.set("network", String(ctx.network));

        setState("Сохраняю…", null);

        const res = await fetch(`/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`, {
            method: "POST",
            headers: { "X-Requested-With": "fetch" },
            body: new URLSearchParams(fd)
        });

        if (res.ok) {
            setState("Сохранено", true);
            badge(new Date().toLocaleTimeString());

            const state = await res.json().catch(() => null);
            publishState(state);
        } else {
            setState("Ошибка", false);
            badge("HTTP " + res.status);
        }
    }

    function applyUiState(state) {
        if (!state) return;

        const modeSel = byId("riskModeSelect");
        const freeValueEl = byId("riskFreeBalanceValue");
        const freeTextEl  = byId("riskFreeBalanceText");
        const assetEl     = byId("riskSelectedAsset");
        const assetTextEl = byId("riskAssetText");

        const bal = state.selectedBalance || null;

        // ✅ asset: сначала accountAsset, потом selectedBalance.asset
        const nextAsset = normalizeUpper(state.accountAsset || (bal && bal.asset));
        if (nextAsset) {
            const changed = nextAsset !== currentAsset;
            currentAsset = nextAsset;

            if (assetEl) assetEl.value = nextAsset;
            if (assetTextEl) assetTextEl.textContent = nextAsset;

            if (changed) dbg("asset changed ->", nextAsset);
        }

        // ✅ free balance
        if (bal && bal.free !== undefined && bal.free !== null) {
            if (freeValueEl) freeValueEl.value = String(bal.free);
            if (freeTextEl) freeTextEl.textContent = fmtNum(bal.free, 8) || "—";
        }

        const m = normalizeMode(modeSel ? (modeSel.value || "ALL") : "ALL");
        applyModeUi(m, currentAsset || "USDT");

        const clamped = clampRiskIfNeeded();
        calcPreview();

        if (clamped) scheduleSaveRisk();
    }

    // =====================================================
    // ✅ ловим смену accountAsset максимально широко
    // =====================================================
    function attachAccountAssetListeners() {
        const selectors = [
            '[name="accountAsset"]',
            '#accountAsset',
            '#accountAssetSelect',
            '[data-role="accountAsset"]'
        ];

        function isAccountAssetEl(el) {
            if (!el || !el.matches) return false;
            return selectors.some(sel => {
                try { return el.matches(sel); } catch (e) { return false; }
            });
        }

        document.addEventListener("change", (e) => {
            const t = e.target;
            if (!isAccountAssetEl(t)) return;

            const next = normalizeUpper(t.value);
            dbg("accountAsset changed in DOM ->", next);

            // визуально обновим asset сразу
            const assetEl = byId("riskSelectedAsset");
            const assetTextEl = byId("riskAssetText");
            if (next) {
                currentAsset = next;
                if (assetEl) assetEl.value = next;
                if (assetTextEl) assetTextEl.textContent = next;
                calcPreview();
            }

            // и подтянем реальный баланс из state (после того как trade-tab, возможно, сохранит настройки)
            setTimeout(() => refreshUiStateFromServer("accountAsset_dom_change"), 80);
        });
    }

    function init() {
        if (started) return;
        started = true;

        dbg("init");

        const initModeEl  = byId("riskInitMode");
        const initValueEl = byId("riskInitValue");
        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");
        const assetEl = byId("riskSelectedAsset");

        currentAsset = (assetEl && assetEl.value) ? normalizeUpper(assetEl.value) : (currentAsset || "USDT");

        const initMode = normalizeMode(initModeEl ? initModeEl.value : "ALL");
        const initVal  = (initValueEl && initValueEl.value) ? String(initValueEl.value).trim() : "";

        if (modeSel) modeSel.value = initMode;
        if (valInp)  valInp.value = initVal;

        applyModeUi(initMode, currentAsset);
        calcPreview();

        // подписка на общий state
        const onState = (state) => {
            try { applyUiState(state); } catch (e) {
                console.warn("[risk] applyUiState failed:", e);
            }
        };

        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            window.StrategySettingsStore.subscribe(onState);
        }
        window.addEventListener("strategy:state", (ev) => onState(ev && ev.detail));

        attachAccountAssetListeners();

        // первичный refresh
        setTimeout(() => refreshUiStateFromServer("init"), 0);

        // events
        modeSel?.addEventListener("change", async () => {
            const m = normalizeMode(modeSel.value);
            if (m === "ALL" && valInp) valInp.value = "";
            applyModeUi(m, currentAsset);
            calcPreview();
            await saveRisk();
        });

        valInp?.addEventListener("input", () => calcPreview());
        valInp?.addEventListener("change", async () => {
            clampRiskIfNeeded();
            calcPreview();
            await saveRisk();
        });

        document.querySelectorAll("[data-risk-set]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const m = normalizeMode(btn.getAttribute("data-risk-mode") || "FIX");
                const v = btn.getAttribute("data-risk-set") || "";

                if (modeSel) modeSel.value = m;
                applyModeUi(m, currentAsset);

                if (valInp) valInp.value = v;

                clampRiskIfNeeded();
                calcPreview();
                await saveRisk();
            });
        });

        console.log("[risk] init OK");
    }

    // =====================================================
    // ✅ АВТО-СТАРТ: если вкладки ленивые — всё равно стартуем когда DOM готов
    // =====================================================
    function autoBoot() {
        // если элементы риска уже есть — стартуем
        if (byId("riskForm") || byId("riskModeSelect") || byId("riskValueInput")) {
            init();
            return;
        }

        // иначе ждём появления (если табы рендерятся лениво)
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

    // наружу
    return { init, applyUiState };
})();

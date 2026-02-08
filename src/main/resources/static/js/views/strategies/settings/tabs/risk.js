"use strict";

window.SettingsTabRisk = (function () {

    let started = false;

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
        el.classList.toggle("bg-secondary", !ok);
        el.classList.toggle("bg-success", !!ok);
        el.classList.toggle("bg-danger", ok === false);
    }

    function normalizeMode(v) {
        const m = String(v || "ALL").trim().toUpperCase();
        return ["ALL","FIX","PCT"].includes(m) ? m : "ALL";
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
        const freeEl  = byId("riskFreeBalanceValue");
        const assetEl = byId("riskSelectedAsset");
        const effEl   = byId("riskEffectiveAmount");

        const asset = (assetEl && assetEl.value) ? assetEl.value : "USDT";
        const free  = toNum(freeEl ? freeEl.value : null);
        const mode  = normalizeMode(modeSel ? modeSel.value : "ALL");
        const raw   = toNum(valInp ? valInp.value : null);

        let eff = null;

        if (mode === "ALL") {
            eff = free;
        } else if (mode === "FIX") {
            const v = (raw !== null && raw > 0) ? raw : null;
            eff = v === null ? null : (free !== null ? Math.min(free, v) : v);
        } else {
            const p = (raw !== null && raw > 0) ? Math.min(raw, 100) : null;
            eff = (p === null || free === null) ? null : (free * (p / 100.0));
        }

        if (effEl) effEl.textContent = (eff !== null ? (fmt(eff) + " " + asset) : "—");

        const badgeEl = byId("riskModeBadge");
        if (badgeEl) badgeEl.textContent = mode;
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

        setState("Сохраняю…", null);

        const res = await fetch(`/strategies/${ctx.type}/config`, {
            method: "POST",
            headers: { "X-Requested-With": "fetch" },
            body: new URLSearchParams(fd)
        });

        if (res.ok) {
            setState("Сохранено", true);
            badge(new Date().toLocaleTimeString());
        } else {
            setState("Ошибка", false);
            badge("HTTP " + res.status);
        }
    }

    function init() {
        if (started) return;
        started = true;

        const modeSel = byId("riskModeSelect");
        const valInp  = byId("riskValueInput");
        const initModeEl  = byId("riskInitMode");
        const initValueEl = byId("riskInitValue");
        const assetEl = byId("riskSelectedAsset");

        const asset = (assetEl && assetEl.value) ? assetEl.value : "USDT";

        const initMode = normalizeMode(initModeEl ? initModeEl.value : "ALL");
        const initVal  = (initValueEl && initValueEl.value) ? String(initValueEl.value).trim() : "";

        if (modeSel) modeSel.value = initMode;
        if (valInp)  valInp.value = initVal;

        applyModeUi(initMode, asset);
        calcPreview();

        // events
        modeSel?.addEventListener("change", async () => {
            const m = normalizeMode(modeSel.value);
            if (m === "ALL" && valInp) valInp.value = "";
            applyModeUi(m, asset);
            calcPreview();
            await saveRisk();
        });

        valInp?.addEventListener("input", () => {
            calcPreview();
        });

        valInp?.addEventListener("change", async () => {
            calcPreview();
            await saveRisk();
        });

        document.querySelectorAll("[data-risk-set]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const m = normalizeMode(btn.getAttribute("data-risk-mode") || "FIX");
                const v = btn.getAttribute("data-risk-set") || "";

                if (modeSel) modeSel.value = m;
                applyModeUi(m, asset);

                if (valInp) valInp.value = v;

                calcPreview();
                await saveRisk();
            });
        });

        console.log("[risk] init OK");
    }

    return { init };
})();

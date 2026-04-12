"use strict";

window.SettingsTabRisk = (function () {

    let started = false;
    let currentAsset = "USDT";

    let refreshInFlight = false;
    let lastRefreshAt = 0;
    let saveInFlight = false;
    let saveTimer = null;

    const DEBUG = false;
    function dbg(...a) { if (DEBUG) console.log("[risk]", ...a); }

    function byId(id) { return document.getElementById(id); }

    function getCtx() {
        return window.StrategySettingsContext || null;
    }

    function isBlank(v) {
        return v === null || v === undefined || String(v).trim() === "";
    }

    function normalizeUpper(v) {
        return isBlank(v) ? "" : String(v).trim().toUpperCase();
    }

    function normalizeMode(v) {
        const x = normalizeUpper(v || "ALL");
        return ["ALL", "FIX", "PCT"].includes(x) ? x : "ALL";
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

    function looksLikeUiState(obj) {
        return !!(
            obj &&
            typeof obj === "object" &&
            ("chatId" in obj) &&
            ("type" in obj) &&
            ("exchange" in obj) &&
            ("network" in obj)
        );
    }

    function ensureStrategyStore() {
        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            return window.StrategySettingsStore;
        }

        let state = null;
        const listeners = new Set();

        const store = {
            set(next) {
                state = next || null;
                listeners.forEach(fn => {
                    try { fn(state); } catch (_) {}
                });
            },
            setState(next) {
                this.set(next);
            },
            get() {
                return state;
            },
            getState() {
                return state;
            },
            subscribe(fn) {
                if (typeof fn !== "function") return () => {};
                listeners.add(fn);
                try { fn(state); } catch (_) {}
                return () => listeners.delete(fn);
            },
            onChange(fn) {
                return this.subscribe(fn);
            }
        };

        window.StrategySettingsStore = store;
        return store;
    }

    function publishState(state) {
        if (!looksLikeUiState(state)) return;

        try {
            ensureStrategyStore().setState(state);
        } catch (_) {}

        try {
            window.dispatchEvent(new CustomEvent("strategy:state", { detail: state }));
        } catch (_) {}
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

    function setSaveState(text, ok) {
        const badge = byId("riskSaveState");
        const meta = byId("riskSaveMeta");

        if (badge) {
            badge.textContent = text || "Готово";
            badge.className =
                "badge " +
                (ok === true ? "bg-success" :
                    ok === false ? "bg-danger" :
                        "bg-secondary");
        }

        if (meta && text === "Сохранено") {
            meta.textContent = new Date().toLocaleTimeString();
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

    function applyModeUi(mode, asset) {
        const label = byId("riskValueLabel");
        const input = byId("riskValueInput");
        const unit = byId("riskValueUnit");
        const hint = byId("riskValueHint");
        const help = byId("riskModeHelp");
        const badge = byId("riskModeBadge");

        const m = normalizeMode(mode);
        const a = normalizeUpper(asset || currentAsset || "USDT");

        if (badge) badge.textContent = m;

        if (m === "ALL") {
            if (label) label.textContent = "Значение";
            if (unit) unit.textContent = a || "USDT";
            if (hint) hint.textContent = "Значение не требуется — будет использован весь доступный баланс.";
            if (help) help.textContent = "ALL — стратегия может использовать весь доступный баланс выбранного актива.";

            if (input) {
                input.value = "";
                input.disabled = true;
                input.readOnly = true;
                input.placeholder = "";
            }
            return;
        }

        if (m === "FIX") {
            if (label) label.textContent = "Сумма";
            if (unit) unit.textContent = a || "USDT";
            if (hint) hint.textContent = "Фиксированная сумма на один вход.";
            if (help) help.textContent = "FIX — стратегия использует фиксированную сумму выбранного актива.";

            if (input) {
                input.disabled = false;
                input.readOnly = false;
                input.placeholder = "Напр. 40";
            }
            return;
        }

        if (label) label.textContent = "Процент";
        if (unit) unit.textContent = "%";
        if (hint) hint.textContent = "Процент от доступного баланса.";
        if (help) help.textContent = "PCT — стратегия использует долю от доступного баланса.";

        if (input) {
            input.disabled = false;
            input.readOnly = false;
            input.placeholder = "Напр. 25";
        }
    }

    function calcPreview() {
        const modeSel = byId("riskModeSelect");
        const valInp = byId("riskValueInput");
        const freeValueEl = byId("riskFreeBalanceValue");
        const effEl = byId("riskEffectiveAmount");

        const mode = normalizeMode(modeSel?.value || "ALL");
        const free = toNum(freeValueEl?.value);
        const raw = toNum(valInp?.value);

        let eff = null;

        if (mode === "ALL") {
            eff = free;
        } else if (mode === "FIX") {
            eff = raw;
        } else if (mode === "PCT") {
            eff = (free !== null && raw !== null) ? (free * (raw / 100.0)) : null;
        }

        if (effEl) {
            effEl.textContent = (eff !== null)
                ? (fmt(eff) + " " + (currentAsset || "USDT"))
                : "—";
        }
    }

    function clampRiskIfNeeded() {
        const modeSel = byId("riskModeSelect");
        const valInp = byId("riskValueInput");
        const freeValueEl = byId("riskFreeBalanceValue");

        if (!modeSel || !valInp || !freeValueEl) return false;

        const mode = normalizeMode(modeSel.value);
        const free = toNum(freeValueEl.value);
        const raw = toNum(valInp.value);

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

    async function refreshUiStateFromServer(reason) {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;
        if (!window.SettingsApi?.getJson) return;

        const now = Date.now();
        if (refreshInFlight) return;
        if (now - lastRefreshAt < 250) return;
        lastRefreshAt = now;

        refreshInFlight = true;
        try {
            const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config/state?${ctxQuery()}`;
            dbg("refresh:", reason, url);

            const state = await window.SettingsApi.getJson(url);
            if (looksLikeUiState(state)) {
                publishState(state);
            }
        } catch (e) {
            console.warn("[risk] refreshUiStateFromServer failed:", e);
        } finally {
            refreshInFlight = false;
        }
    }

    async function saveRisk() {
        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;
        if (!window.SettingsApi?.postForm) return;
        if (saveInFlight) return;

        const modeSel = byId("riskModeSelect");
        const valInp = byId("riskValueInput");

        const payload = {
            chatId: String(ctx.chatId || ""),
            saveScope: "risk",
            tab: "risk",
            exchange: ctx.exchange || "",
            network: ctx.network || "",
            capitalMode: normalizeMode(modeSel?.value || "ALL"),
            capitalValue: String(valInp?.value ?? "").trim(),
            accountAsset: normalizeUpper(byId("riskSelectedAsset")?.value || currentAsset || "")
        };

        saveInFlight = true;
        setSaveState("Сохраняю…", null);

        try {
            const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`;
            const state = await window.SettingsApi.postForm(url, payload);

            setSaveState("Сохранено", true);

            if (looksLikeUiState(state)) {
                publishState(state);
            } else {
                await refreshUiStateFromServer("risk_save_ok");
            }
        } catch (e) {
            setSaveState("Ошибка", false);
            console.error("[risk] save failed:", e);
        } finally {
            saveInFlight = false;
        }
    }

    function scheduleSaveRisk() {
        clearTimeout(saveTimer);
        saveTimer = setTimeout(() => {
            saveTimer = null;
            saveRisk().catch(() => {});
        }, 250);
    }

    function applyUiState(state) {
        if (!looksLikeUiState(state)) return;

        const modeSel = byId("riskModeSelect");
        const valInp = byId("riskValueInput");

        const freeValueEl = byId("riskFreeBalanceValue");
        const freeTextEl = byId("riskFreeBalanceText");
        const assetEl = byId("riskSelectedAsset");
        const assetTextEl = byId("riskAssetText");

        const bal = state.selectedBalance || state.balance || null;

        const nextAsset = normalizeUpper(
            state.accountAsset || (bal && (bal.asset || bal.currency || bal.code))
        );

        if (nextAsset) {
            currentAsset = nextAsset;

            if (assetEl) assetEl.value = nextAsset;
            if (assetTextEl) assetTextEl.textContent = nextAsset;

            const ctx = getCtx();
            if (ctx) ctx.accountAsset = nextAsset;
        }

        if (bal && bal.free !== undefined && bal.free !== null) {
            if (freeValueEl) freeValueEl.value = String(bal.free);
            if (freeTextEl) freeTextEl.textContent = fmtNum(bal.free, 8) || "—";
        }

        const modeFromState = pickModeFromState(state);
        const valFromState = pickValueFromState(state);

        if (modeSel && modeSel.value !== modeFromState) {
            modeSel.value = modeFromState;
        }

        if (valInp && String(valInp.value || "") !== String(valFromState || "")) {
            valInp.value = String(valFromState || "");
        }

        applyModeUi(modeFromState, currentAsset || "USDT");
        clampRiskIfNeeded();
        calcPreview();
    }

    function attachAccountAssetListeners() {
        let assetRefreshTimer = null;

        window.addEventListener("strategy:accountAssetChanged", (ev) => {
            const next = normalizeUpper(ev?.detail?.asset || "");
            if (!next) return;

            currentAsset = next;

            const assetEl = byId("riskSelectedAsset");
            const assetTextEl = byId("riskAssetText");

            if (assetEl) assetEl.value = next;
            if (assetTextEl) assetTextEl.textContent = next;

            calcPreview();

            clearTimeout(assetRefreshTimer);
            assetRefreshTimer = setTimeout(() => {
                refreshUiStateFromServer("accountAsset_event").catch(() => {});
            }, 100);
        });
    }

    function init() {
        if (started) return;

        const ctx = getCtx();
        if (!ctx?.chatId || !ctx?.type) {
            dbg("ctx not ready -> skip init");
            return;
        }

        if (!window.SettingsApi?.postForm || !window.SettingsApi?.getJson) {
            dbg("SettingsApi not ready -> skip init");
            return;
        }

        const form = byId("riskForm");
        const modeSel = byId("riskModeSelect");
        const valInp = byId("riskValueInput");

        if (!form || !modeSel || !valInp) {
            dbg("risk DOM not ready -> skip init");
            return;
        }

        started = true;

        const initModeEl = byId("riskInitMode");
        const initValueEl = byId("riskInitValue");
        const assetEl = byId("riskSelectedAsset");

        currentAsset = normalizeUpper(assetEl?.value || "USDT");

        const initMode = normalizeMode(initModeEl?.value || "ALL");
        const initVal = String(initValueEl?.value || "").trim();

        modeSel.value = initMode;
        valInp.value = initVal;

        applyModeUi(initMode, currentAsset);
        clampRiskIfNeeded();
        calcPreview();

        ensureStrategyStore().subscribe((state) => {
            try {
                applyUiState(state);
            } catch (e) {
                console.warn("[risk] applyUiState failed:", e);
            }
        });

        attachAccountAssetListeners();

        modeSel.addEventListener("change", () => {
            const m = normalizeMode(modeSel.value);

            const freeValueEl = byId("riskFreeBalanceValue");
            const free = toNum(freeValueEl?.value);
            const currentRaw = String(valInp?.value || "").trim();

            if (m === "ALL") {
                valInp.value = "";
            } else if (m === "FIX") {
                if (!currentRaw) {
                    if (free !== null && free > 0) {
                        valInp.value = String(Math.min(free, 20));
                    } else {
                        valInp.value = "20";
                    }
                }
            } else if (m === "PCT") {
                if (!currentRaw) {
                    valInp.value = "25";
                }
            }

            clampRiskIfNeeded();
            applyModeUi(m, currentAsset);
            calcPreview();
            saveRisk().catch(() => {});
        });

        valInp.addEventListener("input", () => {
            clampRiskIfNeeded();
            calcPreview();
        });

        valInp.addEventListener("change", () => {
            clampRiskIfNeeded();
            calcPreview();
            scheduleSaveRisk();
        });

        document.querySelectorAll("[data-risk-set]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const m = normalizeMode(btn.getAttribute("data-risk-mode") || "FIX");
                const v = btn.getAttribute("data-risk-set") || "";

                modeSel.value = m;
                valInp.value = v;

                clampRiskIfNeeded();
                applyModeUi(m, currentAsset);
                calcPreview();

                await saveRisk();
            });
        });

        refreshUiStateFromServer("risk_init").catch(() => {});
    }

    return { init, applyUiState };
})();
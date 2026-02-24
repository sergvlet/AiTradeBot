"use strict";

window.SettingsTabGeneral = (function () {

    let started = false;

    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function normalizeMode(v) {
        const m = String(v || "MANUAL").trim().toUpperCase();
        if (m === "MANUAL" || m === "HYBRID" || m === "AI") return m;
        return "MANUAL";
    }

    function nowHHmm() {
        const d = new Date();
        const hh = String(d.getHours()).padStart(2, "0");
        const mm = String(d.getMinutes()).padStart(2, "0");
        return `${hh}:${mm}`;
    }

    // =====================================================
    // Context / URLs
    // =====================================================
    function getCtx() {
        return window.StrategySettingsContext || null;
    }

    function ctxQueryParams(ctx) {
        const q = new URLSearchParams();
        if (ctx?.chatId) q.set("chatId", String(ctx.chatId));
        if (!isBlank(ctx?.exchange)) q.set("exchange", String(ctx.exchange));
        if (!isBlank(ctx?.network)) q.set("network", String(ctx.network));
        return q;
    }

    function buildConfigUrl(ctx, tabName) {
        const base = `/strategies/${encodeURIComponent(String(ctx.type || ""))}/config`;
        const q = ctxQueryParams(ctx);
        if (!isBlank(tabName)) q.set("tab", String(tabName));
        const qs = q.toString();
        return qs ? (base + "?" + qs) : base;
    }

    function buildStateUrl(ctx, diagnostics) {
        const base = `/strategies/${encodeURIComponent(String(ctx.type || ""))}/config/state`;
        const q = new URLSearchParams();
        q.set("chatId", String(ctx.chatId || ""));
        if (!isBlank(ctx?.exchange)) q.set("exchange", String(ctx.exchange));
        if (!isBlank(ctx?.network)) q.set("network", String(ctx.network));
        q.set("diagnostics", diagnostics ? "true" : "false");
        return base + "?" + q.toString();
    }

    // =====================================================
    // Confirm modal (Bootstrap) fallback -> window.confirm
    // =====================================================
    function showConfirm(title, text) {
        if (!window.bootstrap || !window.bootstrap.Modal) {
            const ok = window.confirm(text || "Подтвердить?");
            return Promise.resolve(ok);
        }

        return new Promise((resolve) => {
            const modalEl = byId("confirmModal");
            const titleEl = byId("confirmModalTitle");
            const bodyEl  = byId("confirmModalBody");
            const okBtn   = byId("confirmModalOk");

            if (!modalEl || !titleEl || !bodyEl || !okBtn) {
                const ok = window.confirm(text || "Подтвердить?");
                resolve(ok);
                return;
            }

            titleEl.textContent = title || "Подтверждение";
            bodyEl.textContent  = text || "Подтвердить действие?";

            const modal = new window.bootstrap.Modal(modalEl, { backdrop: "static", keyboard: false });

            let done = false;

            const cleanup = () => {
                okBtn.removeEventListener("click", onOk);
                modalEl.removeEventListener("hidden.bs.modal", onHide);
            };

            const onOk = () => {
                if (done) return;
                done = true;
                cleanup();
                try { modal.hide(); } catch (_) {}
                resolve(true);
            };

            const onHide = () => {
                if (done) return;
                done = true;
                cleanup();
                resolve(false);
            };

            okBtn.addEventListener("click", onOk);
            modalEl.addEventListener("hidden.bs.modal", onHide);

            try { modal.show(); } catch (_) { resolve(window.confirm(text || "Подтвердить?")); }
        });
    }

    // =====================================================
    // UI helpers
    // =====================================================
    function setBadge(el, kind, text) {
        if (!el) return;

        el.textContent = text || "";
        el.classList.remove(
            "bg-success", "bg-warning", "bg-secondary", "bg-danger", "bg-info",
            "text-dark"
        );

        if (kind === "ok") el.classList.add("bg-success");
        else if (kind === "warn") { el.classList.add("bg-warning"); el.classList.add("text-dark"); }
        else if (kind === "err") el.classList.add("bg-danger");
        else if (kind === "info") { el.classList.add("bg-info"); el.classList.add("text-dark"); }
        else el.classList.add("bg-secondary");
    }

    function setProgress(on) {
        const progress = byId("controlModeProgress");
        if (!progress) return;
        progress.classList.toggle("d-none", !on);
    }

    function setModeHint(mode) {
        const hint = byId("controlModeHint");
        if (!hint) return;

        if (mode === "MANUAL") {
            hint.innerHTML =
                "<b>MANUAL:</b> бот не меняет параметры." +
                "<br><b>HYBRID:</b> бот предлагает/записывает параметры, ты можешь править." +
                "<br><b>AI:</b> бот управляет постоянно; часть полей может стать read-only.";
            return;
        }

        if (mode === "HYBRID") {
            hint.innerHTML =
                "<b>HYBRID:</b> ты меняешь поля, бот может предлагать и перезаписывать параметры после тюнинга/бэктеста." +
                "<br>Изменения важных параметров подтверждаются.";
            return;
        }

        if (mode === "AI") {
            hint.innerHTML =
                "<b>AI:</b> система управляет параметрами автоматически." +
                "<br>Часть полей может стать read-only, а значения могут меняться в рантайме.";
        }
    }

    function dispatchMode(mode) {
        try {
            window.__StrategyControlMode = mode;
            window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode } }));
        } catch (_) {}
    }

    function dispatchUiState(state) {
        try {
            window.dispatchEvent(new CustomEvent("strategy:uiStateChanged", { detail: { state } }));
        } catch (_) {}
    }

    // =====================================================
    // ✅ “железно” применяем режим к UI (без ожидания сервера)
    // =====================================================
    function applyControlModeUi(mode) {
        const m = normalizeMode(mode);

        // общие ID (если есть на странице)
        const autoTuneCb = byId("autoTuneEnabled");
        const mlGateCb   = byId("mlGateEnabled");
        const gateMin    = byId("gateMinProb");

        // MANUAL: запретить/сбросить
        if (m === "MANUAL") {
            if (autoTuneCb) { autoTuneCb.checked = false; autoTuneCb.disabled = true; }
            if (mlGateCb)   { mlGateCb.checked = false;   mlGateCb.disabled = true; }
            if (gateMin)    { gateMin.value = ""; gateMin.disabled = true; }
            return;
        }

        // HYBRID: разрешаем ручное (но система может менять после тюнинга)
        if (m === "HYBRID") {
            if (autoTuneCb) autoTuneCb.disabled = false;
            if (mlGateCb)   mlGateCb.disabled = false;
            if (gateMin)    gateMin.disabled = false;
            return;
        }

        // AI: autotune обязателен (UI), ml-gate по желанию
        if (m === "AI") {
            if (autoTuneCb) { autoTuneCb.checked = true; autoTuneCb.disabled = true; }
            if (mlGateCb)   mlGateCb.disabled = false;
            if (gateMin)    gateMin.disabled = false;
        }
    }

    // =====================================================
    // State sync
    // =====================================================
    function isObj(x) { return x && typeof x === "object"; }

    function applyStateToCtx(ctx, st) {
        if (!ctx || !isObj(st)) return;

        if (!isBlank(st.exchange)) ctx.exchange = String(st.exchange);
        if (st.network) ctx.network = String(st.network);

        if (st.advancedControlMode) ctx.advancedControlMode = normalizeMode(st.advancedControlMode);
        if (!isBlank(st.runPhase)) ctx.runPhase = String(st.runPhase);

        if (typeof st.autoTuneEnabled === "boolean") ctx.autoTuneEnabled = st.autoTuneEnabled;
        if (typeof st.mlGateEnabled === "boolean") ctx.mlGateEnabled = st.mlGateEnabled;

        if (!isBlank(st.symbol)) ctx.symbol = String(st.symbol);
        if (!isBlank(st.timeframe)) ctx.timeframe = String(st.timeframe);
        if (typeof st.cachedCandlesLimit === "number") ctx.cachedCandlesLimit = st.cachedCandlesLimit;
    }

    async function fetchUiState(ctx) {
        const api = window.SettingsApi;
        const url = buildStateUrl(ctx, false);

        try {
            if (api?.getJson) return await api.getJson(url);

            const resp = await fetch(url, {
                method: "GET",
                credentials: "same-origin",
                headers: { "Accept": "application/json" }
            });
            if (!resp.ok) return null;
            return await resp.json().catch(() => null);
        } catch (e) {
            console.warn("[general] fetch state failed:", e);
            return null;
        }
    }

    // =====================================================
    // MAIN INIT
    // =====================================================
    function init() {
        if (started) return;

        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) {
            console.warn("[general] ctx not ready -> skip init (will retry on tab click)");
            return;
        }

        const api = window.SettingsApi;
        if (!api?.postForm) {
            console.error("[general] SettingsApi.postForm not found");
            return;
        }

        const form = byId("controlForm") || byId("generalForm");
        if (!form) {
            console.warn("[general] controlForm/generalForm not found -> skip init (will retry)");
            return;
        }

        const modeSelect = byId("advancedControlMode");
        if (!modeSelect) {
            console.warn("[general] #advancedControlMode not found -> skip init (will retry)");
            return;
        }

        started = true;

        const saveState = byId("controlSaveState") || byId("generalSaveState");
        const saveMeta  = byId("controlSaveMeta")  || byId("generalSaveMeta");

        const initialMode = normalizeMode(modeSelect.value || ctx.advancedControlMode || "MANUAL");
        ctx.advancedControlMode = initialMode;
        modeSelect.dataset.prevValue = initialMode;

        setModeHint(initialMode);
        applyControlModeUi(initialMode);
        setBadge(saveState, "info", "Готово");
        if (saveMeta) saveMeta.textContent = "";
        dispatchMode(initialMode);

        function setSavedUi(extra) {
            setBadge(saveState, "ok", "Сохранено ✓");
            if (saveMeta) saveMeta.textContent = extra || nowHHmm();
        }

        function setSavingUi() {
            setBadge(saveState, "info", "Сохранение…");
            if (saveMeta) saveMeta.textContent = nowHHmm();
        }

        function setErrorUi(msg) {
            setBadge(saveState, "err", "Ошибка");
            if (saveMeta) saveMeta.textContent = msg || "проверь сервер";
        }

        async function saveModeToServer(mode) {
            const url = buildConfigUrl(ctx, "control");
            return await api.postForm(url, {
                saveScope: "general",
                tab: "control",
                exchange: ctx.exchange || "",
                network: ctx.network || "",
                advancedControlMode: String(mode)
            });
        }

        async function tryApplyMode(mode) {
            const url = "/strategies/apply";
            const payload = {
                chatId: Number(ctx.chatId),
                type: String(ctx.type),
                exchange: String(ctx.exchange || ""),
                network: String(ctx.network || "TESTNET"),
                advancedControlMode: String(mode),
                reason: "ui-change"
            };

            try {
                if (api?.postJson) return await api.postJson(url, payload);

                const token  = document.querySelector('meta[name="_csrf"]')?.getAttribute("content") || "";
                const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || "";
                const headers = { "Content-Type": "application/json", "Accept": "application/json" };
                if (token && header) headers[header] = token;

                const resp = await fetch(url, {
                    method: "POST",
                    credentials: "same-origin",
                    headers,
                    body: JSON.stringify(payload)
                });

                if (!resp.ok) return {};
                return await resp.json().catch(() => ({}));
            } catch (e) {
                console.warn("[general] apply failed:", e);
                return {};
            }
        }

        function revertToPrev(prev) {
            modeSelect.value = prev;
            ctx.advancedControlMode = prev;
            modeSelect.dataset.prevValue = prev;
            setModeHint(prev);
            applyControlModeUi(prev);
            dispatchMode(prev);
        }

        let inFlight = false;

        modeSelect.addEventListener("change", async () => {
            if (inFlight) return;

            const prev = normalizeMode(modeSelect.dataset.prevValue || "MANUAL");
            const next = normalizeMode(modeSelect.value || "MANUAL");
            if (next === prev) return;

            // подтверждение
            if (next === "HYBRID") {
                const ok = await showConfirm(
                    "Подтверждение",
                    "Включить HYBRID режим? Бот сможет применять/перезаписывать параметры после тюнинга/бэктеста."
                );
                if (!ok) { revertToPrev(prev); return; }
            }

            if (next === "AI") {
                const ok = await showConfirm(
                    "Внимание",
                    "В режиме AI система может менять параметры автоматически и блокировать ручное редактирование. Включить AI?"
                );
                if (!ok) { revertToPrev(prev); return; }
            }

            // ✅ UI сразу
            ctx.advancedControlMode = next;
            setModeHint(next);
            applyControlModeUi(next);
            dispatchMode(next);

            inFlight = true;
            setSavingUi();
            setProgress(next === "HYBRID" || next === "AI");

            try {
                const saved = await saveModeToServer(next);

                // подтянуть реальный state
                let state = (isObj(saved) && (saved.type || saved.advancedControlMode)) ? saved : null;
                if (!state) state = await fetchUiState(ctx);

                if (state) {
                    applyStateToCtx(ctx, state);

                    const realMode = normalizeMode(state.advancedControlMode || next);
                    modeSelect.value = realMode;
                    modeSelect.dataset.prevValue = realMode;

                    setModeHint(realMode);
                    applyControlModeUi(realMode);
                    dispatchMode(realMode);
                    dispatchUiState(state);
                } else {
                    modeSelect.dataset.prevValue = next;
                }

                // apply только для HYBRID/AI
                if (next === "HYBRID" || next === "AI") {
                    const r = await tryApplyMode(next);
                    const applied = (r && r.applied === true);
                    const reason = (r && r.reason) ? String(r.reason) : "";
                    setSavedUi(nowHHmm() + (applied ? " • применено" : (reason ? (" • " + reason) : "")));

                    const st2 = await fetchUiState(ctx);
                    if (st2) {
                        applyStateToCtx(ctx, st2);
                        // важное: если сервер “зажал” правила, UI должен подстроиться
                        const m2 = normalizeMode(st2.advancedControlMode || next);
                        modeSelect.value = m2;
                        modeSelect.dataset.prevValue = m2;
                        applyControlModeUi(m2);
                        dispatchUiState(st2);
                    }
                } else {
                    setSavedUi(nowHHmm());
                }

                setProgress(false);

            } catch (e) {
                console.error("[general] save control mode failed:", e);
                revertToPrev(prev);
                setProgress(false);
                setErrorUi(String(e?.message || "ошибка"));
            } finally {
                inFlight = false;
            }
        });
    }

    return { init };
})();

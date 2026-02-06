"use strict";

window.SettingsTabGeneral = (function () {

    let started = false;

    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function normalizeMode(v) {
        const m = String(v || "MANUAL").trim().toUpperCase();
        return m ? m : "MANUAL";
    }

    function nowHHmm() {
        const d = new Date();
        const hh = String(d.getHours()).padStart(2, "0");
        const mm = String(d.getMinutes()).padStart(2, "0");
        return `${hh}:${mm}`;
    }

    // =====================================================
    // ✅ CONTEXT (сам восстанавливается из data-атрибутов)
    // =====================================================
    function ensureCtx() {
        if (window.StrategySettingsContext && window.StrategySettingsContext.chatId) {
            return window.StrategySettingsContext;
        }

        const root = document.querySelector(".strategy-settings-page[data-chat-id][data-type]");
        if (!root) return window.StrategySettingsContext || null;

        const ctx = window.StrategySettingsContext || {};
        ctx.chatId = ctx.chatId || root.getAttribute("data-chat-id");
        ctx.type = ctx.type || root.getAttribute("data-type");
        ctx.exchange = ctx.exchange || root.getAttribute("data-exchange");
        ctx.network = ctx.network || root.getAttribute("data-network");
        ctx.baseUrl = ctx.baseUrl || window.location.pathname;

        window.StrategySettingsContext = ctx;
        return ctx;
    }

    function getCtx() {
        return ensureCtx();
    }

    // =====================================================
    // URLs
    // =====================================================
    function ctxQueryString(ctx) {
        const q = new URLSearchParams();
        if (ctx?.chatId) q.set("chatId", String(ctx.chatId));
        if (ctx?.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx?.network) q.set("network", String(ctx.network));
        return q.toString();
    }

    function buildConfigUrl(ctx, tabName) {
        const type = String(ctx?.type || "").trim();
        const qs = ctxQueryString(ctx);
        const q = new URLSearchParams(qs);
        if (tabName) q.set("tab", String(tabName));
        return `/strategies/${encodeURIComponent(type)}/config?` + q.toString();
    }

    // =====================================================
    // Confirm modal
    // =====================================================
    function showConfirm(title, text) {
        return new Promise((resolve) => {
            const modalEl = byId("generalConfirmModal");
            const titleEl = byId("generalConfirmTitle");
            const textEl  = byId("generalConfirmText");
            const okBtn   = byId("generalConfirmOk");

            if (!modalEl || !window.bootstrap?.Modal || !okBtn) {
                resolve(true);
                return;
            }

            if (titleEl) titleEl.textContent = title || "Подтверждение";
            if (textEl)  textEl.textContent  = text  || "Сохранить изменения?";

            const modal = window.bootstrap.Modal.getOrCreateInstance(modalEl, {
                backdrop: "static",
                keyboard: false
            });

            let done = false;

            const cleanup = () => {
                okBtn.removeEventListener("click", onOk);
                modalEl.removeEventListener("hidden.bs.modal", onHide);
            };

            const onOk = () => {
                if (done) return;
                done = true;
                cleanup();
                modal.hide();
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
            modal.show();
        });
    }

    // =====================================================
    // UI helpers
    // =====================================================
    function setBadge(el, kind, text) {
        if (!el) return;
        el.textContent = text || "";
        el.classList.remove("bg-success", "bg-warning", "bg-secondary", "bg-danger", "text-dark");

        if (kind === "ok") el.classList.add("bg-success");
        else if (kind === "warn") { el.classList.add("bg-warning"); el.classList.add("text-dark"); }
        else if (kind === "err") el.classList.add("bg-danger");
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

    // =====================================================
    // MAIN INIT
    // =====================================================
    function init() {
        // ⛔ не “убиваем” модуль навсегда, если ctx еще не готов
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

        // ✅ теперь можно фиксировать started
        started = true;
        console.log("[general] init OK, binding listeners…", ctx);

        const saveState = byId("controlSaveState") || byId("generalSaveState");
        const saveMeta  = byId("controlSaveMeta")  || byId("generalSaveMeta");

        const initialMode = normalizeMode(modeSelect.value || ctx.advancedControlMode || "MANUAL");
        ctx.advancedControlMode = initialMode;
        modeSelect.dataset.prevValue = initialMode;

        setModeHint(initialMode);
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
            await api.postForm(url, {
                saveScope: "general",
                tab: "control",
                exchange: ctx.exchange || "",
                network: ctx.network || "",
                advancedControlMode: mode
            });
        }

        // ✅ apply: твой контроллер = POST /strategies/apply
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
                if (api?.postJson) {
                    return await api.postJson(url, payload);
                }
                // fallback (на всякий)
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

        let inFlight = false;

        modeSelect.addEventListener("change", async () => {
            if (inFlight) return;

            const prev = normalizeMode(modeSelect.dataset.prevValue || "MANUAL");
            const next = normalizeMode(modeSelect.value || "MANUAL");
            if (next === prev) return;

            ctx.advancedControlMode = next;
            setModeHint(next);
            dispatchMode(next);

            if (next === "HYBRID") {
                const ok = await showConfirm(
                    "Подтверждение",
                    "Включить HYBRID режим? Бот сможет применять/перезаписывать параметры после тюнинга/бэктеста."
                );
                if (!ok) {
                    modeSelect.value = prev;
                    ctx.advancedControlMode = prev;
                    modeSelect.dataset.prevValue = prev;
                    setModeHint(prev);
                    dispatchMode(prev);
                    return;
                }
            }

            if (next === "AI") {
                const ok = await showConfirm(
                    "Внимание",
                    "В режиме AI система может менять параметры автоматически и блокировать ручное редактирование. Включить AI?"
                );
                if (!ok) {
                    modeSelect.value = prev;
                    ctx.advancedControlMode = prev;
                    modeSelect.dataset.prevValue = prev;
                    setModeHint(prev);
                    dispatchMode(prev);
                    return;
                }
            }

            inFlight = true;
            setSavingUi();
            setProgress(next === "HYBRID" || next === "AI");

            try {
                await saveModeToServer(next);

                if (next === "HYBRID" || next === "AI") {
                    const r = await tryApplyMode(next);
                    const applied = (r && r.applied === true);
                    const reason = (r && r.reason) ? String(r.reason) : "";
                    setSavedUi(nowHHmm() + (applied ? " • применено" : (reason ? (" • " + reason) : "")));
                } else {
                    setSavedUi(nowHHmm());
                }

                modeSelect.dataset.prevValue = next;
                setProgress(false);
            } catch (e) {
                console.error("[general] save control mode failed:", e);

                modeSelect.value = prev;
                ctx.advancedControlMode = prev;
                modeSelect.dataset.prevValue = prev;
                setModeHint(prev);
                dispatchMode(prev);

                setProgress(false);
                setErrorUi(String(e?.message || "ошибка"));
            } finally {
                inFlight = false;
            }
        });
    }

    return { init };
})();

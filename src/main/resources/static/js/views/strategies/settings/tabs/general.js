"use strict";

window.SettingsTabGeneral = (function () {

    let started = false;

    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function nowHHmm() {
        const d = new Date();
        const hh = String(d.getHours()).padStart(2, "0");
        const mm = String(d.getMinutes()).padStart(2, "0");
        return `${hh}:${mm}`;
    }

    // =====================================================
    // CSRF (Spring Security friendly)
    // =====================================================
    function readCsrf() {
        const token  = document.querySelector('meta[name="_csrf"]')?.getAttribute("content") || "";
        const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content") || "";
        if (!token || !header) return null;

        return { token, header };
    }

    // =====================================================
    // Context / URLs
    // =====================================================
    function getCtx() {
        return window.StrategySettingsContext || null;
    }

    function buildConfigUrl(ctx, tabName) {
        // /strategies/{type}/config?chatId=..&exchange=..&network=..&tab=..
        const type = (ctx?.type || "").toString().trim();
        const q = new URLSearchParams();
        if (ctx?.chatId) q.set("chatId", String(ctx.chatId));
        if (ctx?.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx?.network) q.set("network", String(ctx.network));
        if (tabName) q.set("tab", String(tabName));
        return `/strategies/${encodeURIComponent(type)}/config?` + q.toString();
    }

    async function postForm(url, data) {
        const body = new URLSearchParams();
        Object.entries(data || {}).forEach(([k, v]) => {
            if (v !== undefined && v !== null) body.append(k, String(v));
        });

        const headers = {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With": "fetch"
        };

        const csrf = readCsrf();
        if (csrf) headers[csrf.header] = csrf.token;

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body
        });

        if (!resp.ok) throw new Error("HTTP " + resp.status);
        return await resp.text().catch(() => "");
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

            // если bootstrap/modals нет — не блокируем UX
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

        // ожидаемые цвета:
        // ok -> success, warn -> warning, info -> secondary, err -> danger
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
                "<br><b>HYBRID:</b> бот предлагает и записывает параметры, ты можешь править." +
                "<br><b>AI:</b> бот управляет параметрами; часть полей может быть read-only.";
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
        if (started) return;
        started = true;

        const ctx = getCtx();
        if (!ctx?.type || !ctx?.chatId) return;

        // ✅ поддержим оба варианта (у тебя сейчас чаще controlForm)
        const form = byId("controlForm") || byId("generalForm");
        if (!form) return;

        const modeSelect = byId("advancedControlMode");
        if (!modeSelect) return;

        const saveState = byId("controlSaveState") || byId("generalSaveState");
        const saveMeta  = byId("controlSaveMeta")  || byId("generalSaveMeta");

        // первичная отрисовка
        const initialMode = String(modeSelect.value || ctx.advancedControlMode || "MANUAL").trim().toUpperCase() || "MANUAL";
        ctx.advancedControlMode = initialMode;
        modeSelect.dataset.prevValue = initialMode;

        setModeHint(initialMode);
        setBadge(saveState, "info", "Готово");
        if (saveMeta) saveMeta.textContent = "";
        dispatchMode(initialMode);

        // disable/enable UI общим правилом (AI может блокировать “ручные” вкладки)
        // здесь не трогаем остальные формы — только сигналим event’ом
        function setSavedUi(extra) {
            setBadge(saveState, "ok", "Сохранено ✓");
            if (saveMeta) saveMeta.textContent = extra || nowHHmm();
        }
        function setSavingUi() {
            setBadge(saveState, "info", "Сохранение…");
            if (saveMeta) saveMeta.textContent = nowHHmm();
        }
        function setErrorUi() {
            setBadge(saveState, "err", "Ошибка");
            if (saveMeta) saveMeta.textContent = "проверь API";
        }

        // сохранение режима через /strategies/{type}/config
        async function saveModeToServer(mode) {
            const url = buildConfigUrl(ctx, "control");

            // Отправляем ровно то, что сервер ожидает в config:
            // saveScope=general, tab=control + exchange/network + advancedControlMode
            await postForm(url, {
                saveScope: "general",
                tab: "control",
                exchange: ctx.exchange || "",
                network: ctx.network || "",
                advancedControlMode: mode
            });
        }

        // опционально: дернуть pipeline apply (если эндпоинт реально есть)
        async function tryApplyMode(mode) {
            // Если у тебя есть отдельный endpoint для запуска тюнинга/бэктеста/обучения — дернем.
            // Если его нет — просто молча пропустим (не ломаем UX).
            const url = "/api/strategy/settings/apply";
            const payload = {
                chatId: ctx.chatId,
                type: ctx.type,
                exchange: ctx.exchange,
                network: ctx.network,
                advancedControlMode: mode,
                reason: "ui-change"
            };

            const headers = { "Content-Type": "application/json", "Accept": "application/json" };
            const csrf = readCsrf();
            if (csrf) headers[csrf.header] = csrf.token;

            try {
                const res = await fetch(url, {
                    method: "POST",
                    credentials: "same-origin",
                    headers,
                    body: JSON.stringify(payload)
                });
                // если 404/405 — значит эндпоинта нет, просто выходим
                if (!res.ok) return false;
                return true;
            } catch (_) {
                return false;
            }
        }

        // обработчик смены режима
        modeSelect.addEventListener("change", async () => {
            const prev = String(modeSelect.dataset.prevValue || "").trim().toUpperCase() || "MANUAL";
            const next = String(modeSelect.value || "").trim().toUpperCase() || "MANUAL";

            if (next === prev) return;

            // ✅ сразу отражаем по UI и раскидываем event (чтобы вкладки сразу подстроились)
            ctx.advancedControlMode = next;
            setModeHint(next);
            dispatchMode(next);

            // HYBRID: подтверждение (мягкое)
            if (next === "HYBRID") {
                const ok = await showConfirm("Подтверждение", "Включить HYBRID режим? Изменения параметров будут требовать подтверждения.");
                if (!ok) {
                    modeSelect.value = prev;
                    ctx.advancedControlMode = prev;
                    modeSelect.dataset.prevValue = prev;
                    setModeHint(prev);
                    dispatchMode(prev);
                    return;
                }
            }

            // AI: предупреждение
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

            setSavingUi();
            setProgress(next === "HYBRID" || next === "AI");

            try {
                await saveModeToServer(next);

                // Для HYBRID/AI — пробуем apply (если есть), но не делаем это обязательным
                if (next === "HYBRID" || next === "AI") {
                    await tryApplyMode(next);
                }

                modeSelect.dataset.prevValue = next;
                setProgress(false);
                setSavedUi((next === "AI" || next === "HYBRID") ? (nowHHmm() + " • применено") : nowHHmm());

            } catch (e) {
                console.error("[general] save control mode failed:", e);

                // откат
                modeSelect.value = prev;
                ctx.advancedControlMode = prev;
                modeSelect.dataset.prevValue = prev;

                setModeHint(prev);
                dispatchMode(prev);

                setProgress(false);
                setErrorUi();
            }
        });
    }

    // safe boot
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }

    return { init };
})();

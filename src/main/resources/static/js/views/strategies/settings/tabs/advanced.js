"use strict";

window.SettingsTabAdvanced = (function () {

    let _inited = false;

    function init() {
        if (_inited) return;
        _inited = true;

        const ENDPOINT = "/api/strategy/settings/advanced";
        const SUBMIT_ENDPOINT = "/api/strategy/settings/advanced/submit";

        const pageRoot =
            document.querySelector(".strategy-settings-page") ||
            document.querySelector("[data-chat-id][data-type][data-exchange][data-network]");

        if (!pageRoot) return;

        const globalCtx = window.StrategySettingsContext || null;

        const ctx = {
            chatId: Number(globalCtx?.chatId ?? pageRoot.dataset.chatId ?? 0),
            type: String(globalCtx?.type ?? pageRoot.dataset.type ?? "").trim(),
            exchange: String(globalCtx?.exchange ?? pageRoot.dataset.exchange ?? "BINANCE").trim(),
            network: String(globalCtx?.network ?? pageRoot.dataset.network ?? "MAINNET").trim()
        };

        const modeBadge = document.getElementById("advModeBadge");
        const activeBadge = document.getElementById("advActiveBadge");
        const loadState = document.getElementById("advLoadState");

        const mlConfEl = document.getElementById("advMlConfidence");
        const profitEl = document.getElementById("advTotalProfitPct");
        const updatedAtEl = document.getElementById("advUpdatedAt");

        const startedAtEl = document.getElementById("advStartedAt");
        const stoppedAtEl = document.getElementById("advStoppedAt");
        const contextEl = document.getElementById("advContext");

        const hintEl = document.getElementById("advStrategyHint");
        const blockEl = document.getElementById("advStrategyBlock");
        const formEl = document.getElementById("advStrategyForm");
        const readonlyNote = document.getElementById("advReadonlyNote");

        if (!blockEl || !formEl) return;

        let lastServer = null;

        let saveTimer = null;
        function scheduleAutosave() {
            if (!lastServer) return;
            if (lastServer.readOnly) return;
            if (lastServer.canSubmit === false) return;

            if (saveTimer) clearTimeout(saveTimer);
            saveTimer = setTimeout(() => submit(), 450);
        }

        let reloadTimer = null;
        function scheduleReload(delayMs) {
            clearTimeout(reloadTimer);
            reloadTimer = setTimeout(() => load(), typeof delayMs === "number" ? delayMs : 200);
        }

        function isAdvancedTabActive() {
            const pane = document.getElementById("tab-advanced");
            return !!pane && pane.classList.contains("active") && pane.classList.contains("show");
        }

        // =========================================================
        // LOAD
        // =========================================================
        async function load() {
            try {
                setLoadState("Загрузка…", "secondary");

                const url = new URL(ENDPOINT, window.location.origin);
                url.searchParams.set("chatId", String(ctx.chatId));
                url.searchParams.set("type", ctx.type);
                url.searchParams.set("exchange", ctx.exchange);
                url.searchParams.set("network", ctx.network);

                const res = await fetch(url.toString(), { method: "GET" });

                if (res.status === 404) {
                    lastServer = null;
                    blockEl.innerHTML = "<div class='text-secondary small'>Нет данных</div>";
                    setLoadState("404", "danger");
                    return;
                }

                if (!res.ok) {
                    lastServer = null;
                    blockEl.innerHTML = "<div class='text-danger small'>Ошибка загрузки</div>";
                    setLoadState("ERR", "danger");
                    return;
                }

                const raw = await res.json();
                const data = normalizeAdvancedDto(raw);

                lastServer = data;

                publishControlModeIfNeeded(data.controlMode);

                renderHeader(data);
                renderMetrics(data);
                renderStatus(data);
                renderStrategyBlock(data);

                setLoadState("Готово", "success");
            } catch (e) {
                console.error("ADVANCED load error", e);
                lastServer = null;
                blockEl.innerHTML = "<div class='text-danger small'>Ошибка загрузки</div>";
                setLoadState("ERR", "danger");
            }
        }

        function setLoadState(text, color) {
            if (!loadState) return;
            loadState.textContent = text;
            loadState.className = "badge bg-" + (color || "secondary");
        }

        function normalizeAdvancedDto(d) {
            const modeRaw = (d && (d.controlMode || d.advancedControlMode || d.mode)) || "MANUAL";
            const mode = String(modeRaw).trim().toUpperCase() || "MANUAL";

            const active = !!(d && d.active);

            let canSubmit = true;
            if (typeof d?.canSubmit === "boolean") canSubmit = d.canSubmit;
            else if (typeof d?.strategyCanEdit === "boolean") canSubmit = d.strategyCanEdit;
            else if (typeof d?.editable === "boolean") canSubmit = d.editable;

            const readOnly = (mode === "AI") || (canSubmit === false);

            const html = d?.strategyAdvancedHtml ?? d?.advancedHtml ?? d?.html ?? "";

            const context =
                d?.context ||
                [d?.accountAsset || "—", d?.symbol || "—", d?.timeframe || "—"].join(" / ");

            return {
                controlMode: mode,
                active,
                readOnly,
                canSubmit: canSubmit !== false,

                mlConfidence: d?.mlConfidence,
                totalProfitPct: d?.totalProfitPct,
                updatedAt: d?.updatedAt,

                startedAt: d?.startedAt,
                stoppedAt: d?.stoppedAt,
                context,

                strategyAdvancedHtml: html
            };
        }

        // =========================================================
        // MODE SYNC
        // =========================================================
        function publishControlModeIfNeeded(mode) {
            const m = String(mode || "").trim().toUpperCase();
            if (!m) return;

            // ✅ если режим уже такой — НИЧЕГО не делаем (важно, чтобы не плодить событий)
            if (window.__StrategyControlMode === m) return;

            window.__StrategyControlMode = m;

            const controlModeSelect = document.getElementById("advancedControlMode");
            if (controlModeSelect) {
                const cur = String(controlModeSelect.value || "").trim().toUpperCase();
                if (cur !== m) {
                    controlModeSelect.value = m;
                    controlModeSelect.dataset.prevValue = m;
                }
            }

            window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode: m } }));
        }

        window.addEventListener("strategy:controlModeChanged", (e) => {
            const m = String(e?.detail?.mode || "").trim().toUpperCase();
            if (!m) return;

            window.__StrategyControlMode = m;

            if (lastServer) {
                lastServer.controlMode = m;
                lastServer.readOnly = (m === "AI") || (lastServer.canSubmit === false);
                renderHeader(lastServer);
                updateReadonlyUiOnly(lastServer);
            }

            if (isAdvancedTabActive()) {
                scheduleReload(120);
            }
        });

        // =========================================================
        // RENDER
        // =========================================================
        function renderHeader(d) {
            if (modeBadge) {
                const m = (d.controlMode || "—").toUpperCase();
                modeBadge.textContent = m;
                modeBadge.className = "badge " + (m === "AI" ? "bg-info" : "bg-secondary");
            }
            if (activeBadge) {
                const a = !!d.active;
                activeBadge.textContent = a ? "ACTIVE" : "INACTIVE";
                activeBadge.className = "badge " + (a ? "bg-success" : "bg-secondary");
            }
        }

        function renderMetrics(d) {
            if (mlConfEl) mlConfEl.value = renderMetric(d.mlConfidence);
            if (profitEl) profitEl.value = renderMetric(d.totalProfitPct);
            if (updatedAtEl) updatedAtEl.value = d.updatedAt || "—";
        }

        function renderStatus(d) {
            if (startedAtEl) startedAtEl.value = d.startedAt || "—";
            if (stoppedAtEl) stoppedAtEl.value = d.stoppedAt || "—";
            if (contextEl) contextEl.value = d.context || "— / — / —";
        }

        function renderStrategyBlock(d) {
            const html = d.strategyAdvancedHtml;

            blockEl.innerHTML = (html && String(html).trim().length > 0)
                ? html
                : "<div class='text-secondary small'>Нет данных</div>";

            if (readonlyNote) readonlyNote.classList.add("d-none");

            updateReadonlyUiOnly(d);
            bindAutosaveHandlers();
        }

        function updateReadonlyUiOnly(d) {
            if (hintEl) {
                hintEl.textContent = d.readOnly
                    ? ""
                    : "MANUAL / HYBRID: параметры применяются сразу (автосохранение).";
            }
            toggleBlockInputsDisabled(!!d.readOnly);
        }

        function toggleBlockInputsDisabled(disable) {
            try {
                const inputs = blockEl.querySelectorAll("input,select,textarea,button");
                inputs.forEach(el => {
                    if (el.matches("input,select,textarea")) {
                        el.disabled = !!disable;
                        if (disable) el.setAttribute("aria-readonly", "true");
                        else el.removeAttribute("aria-readonly");
                    } else if (el.matches("button[type='submit']")) {
                        el.disabled = !!disable;
                    }
                });
            } catch (_) {}
        }

        function bindAutosaveHandlers() {
            formEl.removeEventListener("input", onFormChange, true);
            formEl.removeEventListener("change", onFormChange, true);

            formEl.addEventListener("input", onFormChange, true);
            formEl.addEventListener("change", onFormChange, true);
        }

        function onFormChange(e) {
            const t = e.target;
            if (!t) return;
            if (!blockEl.contains(t)) return;
            if (!lastServer || lastServer.readOnly) return;

            if (t.matches("input,select,textarea")) scheduleAutosave();
        }

        function renderMetric(v) {
            if (v === null || typeof v === "undefined") return "0";
            return String(v);
        }

        // =========================================================
        // SUBMIT
        // ✅ отправляем также advancedControlMode
        // =========================================================
        async function submit() {
            if (!lastServer) return;
            if (lastServer.readOnly) return;
            if (lastServer.canSubmit === false) return;

            try {
                setLoadState("Сохранение…", "secondary");

                const fd = new FormData(formEl);

                fd.set("chatId", String(ctx.chatId));
                fd.set("type", ctx.type);
                fd.set("exchange", ctx.exchange);
                fd.set("network", ctx.network);

                // ✅ ВАЖНО: режим берём из селекта или глобала
                const sel = document.getElementById("advancedControlMode");
                const mode =
                    (sel && sel.value ? String(sel.value) : null) ||
                    (window.__StrategyControlMode ? String(window.__StrategyControlMode) : null) ||
                    (lastServer?.controlMode ? String(lastServer.controlMode) : null);

                if (mode) fd.set("advancedControlMode", String(mode).trim().toUpperCase());

                const body = new URLSearchParams();
                for (const [k, v] of fd.entries()) body.set(k, String(v));

                const res = await fetch(SUBMIT_ENDPOINT, {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
                    body: body.toString()
                });

                if (!res.ok) {
                    setLoadState("ERR", "danger");
                    return;
                }

                scheduleReload(150);

            } catch (e) {
                console.error("ADVANCED submit error", e);
                setLoadState("ERR", "danger");
            }
        }

        load();
    }

    return { init };
})();

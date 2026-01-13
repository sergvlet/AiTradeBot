"use strict";

window.SettingsTabAdvanced = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        // badges / state
        const loadState    = document.getElementById("advLoadState");
        const modeBadge    = document.getElementById("advModeBadge");
        const activeBadge  = document.getElementById("advActiveBadge");

        // metrics
        const mlConfidence = document.getElementById("advMlConfidence");
        const totalProfit  = document.getElementById("advTotalProfitPct");
        const updatedAt    = document.getElementById("advUpdatedAt");
        const startedAt    = document.getElementById("advStartedAt");
        const stoppedAt    = document.getElementById("advStoppedAt");
        const contextEl    = document.getElementById("advContext");

        // strategy block
        const strategyBlock  = document.getElementById("advStrategyBlock");
        const strategyForm   = document.getElementById("advStrategyForm");
        const saveBtn        = document.getElementById("advSaveBtn");
        const hintEl         = document.getElementById("advStrategyHint");
        const readonlyNote   = document.getElementById("advReadonlyNote");

        // source of truth (GENERAL)
        const controlModeSelect = document.getElementById("advancedControlMode");

        const ENDPOINT_LOAD   = "/api/strategy/settings/advanced";
        const ENDPOINT_SUBMIT = "/api/strategy/settings/advanced/submit";

        let lastServer = null; // последняя dto с сервера

        function setState(text, cls) {
            if (!loadState) return;
            loadState.textContent = text || "—";
            loadState.className = "badge " + (cls || "bg-secondary");
        }

        function fmt(v) {
            if (v == null) return "—";
            const s = String(v).trim();
            return s ? s : "—";
        }

        function fmtDt(v) {
            const s = fmt(v);
            if (s === "—") return "—";
            return s.replace("T", " ");
        }

        function modeNowUi() {
            const v = (controlModeSelect?.value || "").trim();
            return v || "MANUAL";
        }

        function applyModeBadge(mode, dirty) {
            if (!modeBadge) return;

            const m = (mode || "MANUAL").trim();

            modeBadge.textContent = dirty ? `${m}*` : m;

            modeBadge.classList.remove("bg-secondary", "bg-primary", "bg-warning", "bg-danger", "text-dark");
            if (m === "AI") {
                modeBadge.classList.add("bg-danger");
            } else if (m === "HYBRID") {
                modeBadge.classList.add("bg-warning", "text-dark");
            } else {
                modeBadge.classList.add("bg-primary");
            }
        }

        function applyActiveBadge(active) {
            if (!activeBadge) return;
            activeBadge.textContent = active ? "🟢 ACTIVE" : "⚫ STOPPED";
            activeBadge.className = "badge " + (active ? "bg-success" : "bg-secondary");
        }

        function setFormDisabled(disabled) {
            if (!strategyForm) return;

            const controls = strategyForm.querySelectorAll("input, select, textarea, button");
            controls.forEach(el => {
                // не трогаем скрытые, но это не критично
                if (el.tagName === "BUTTON") return;
                el.disabled = !!disabled;
            });
        }

        function applyEditPolicy() {
            const uiMode = modeNowUi();
            const serverCanEdit = (lastServer?.strategyCanEdit === true);

            // UI-логика: AI всегда readonly
            const uiSaysReadonly = (uiMode === "AI");
            const canEdit = serverCanEdit && !uiSaysReadonly;

            if (saveBtn) saveBtn.disabled = !canEdit;
            setFormDisabled(!canEdit);

            if (readonlyNote) {
                readonlyNote.classList.toggle("d-none", canEdit);
            }

            if (hintEl) {
                if (uiMode === "AI") {
                    hintEl.textContent = "Режим AI запрещает ручное редактирование.";
                } else if (!serverCanEdit) {
                    hintEl.textContent = "Редактирование отключено политикой сервера.";
                } else if (uiMode === "HYBRID") {
                    hintEl.textContent = "HYBRID: можно менять вручную, AI может рекомендовать.";
                } else {
                    hintEl.textContent = "MANUAL: все параметры редактируются вручную.";
                }
            }
        }

        function syncModeFromUi() {
            // ✅ ключ: обновляем бейдж сразу по select, не ждём БД
            const uiMode = modeNowUi();
            const serverMode = String(lastServer?.advancedControlMode || "").trim();
            const dirty = (serverMode && uiMode && serverMode !== uiMode);
            applyModeBadge(uiMode, dirty);
            applyEditPolicy();
        }

        async function load() {
            try {
                setState("Загрузка...", "bg-info text-dark");

                const ex   = (ctx.exchange || "BINANCE").trim();
                const net  = (ctx.network  || "TESTNET").trim();
                const type = (ctx.type || "").trim();

                const url =
                    `${ENDPOINT_LOAD}` +
                    `?chatId=${encodeURIComponent(ctx.chatId)}` +
                    `&type=${encodeURIComponent(type)}` +
                    `&exchange=${encodeURIComponent(ex)}` +
                    `&network=${encodeURIComponent(net)}`;

                const res = await fetch(url, { headers: { "Accept": "application/json" } });
                if (res.status === 404) {
                    setState("Нет данных", "bg-warning text-dark");
                    return;
                }
                if (!res.ok) throw new Error(`http ${res.status}`);

                lastServer = await res.json();

                // server active (точно)
                applyActiveBadge(!!lastServer.active);

                // metrics
                if (mlConfidence) mlConfidence.value = fmt(lastServer.mlConfidence);
                if (totalProfit)  totalProfit.value  = fmt(lastServer.totalProfitPct);

                if (updatedAt) updatedAt.value = fmtDt(lastServer.updatedAt);
                if (startedAt) startedAt.value = fmtDt(lastServer.startedAt);
                if (stoppedAt) stoppedAt.value = fmtDt(lastServer.stoppedAt);

                if (contextEl) {
                    const a  = fmt(lastServer.accountAsset);
                    const s  = fmt(lastServer.symbol);
                    const tf = fmt(lastServer.timeframe);
                    contextEl.value = `${a} / ${s} / ${tf}`;
                }

                // strategy block HTML
                if (strategyBlock) {
                    strategyBlock.innerHTML = lastServer.strategyAdvancedHtml || "";
                }

                // ✅ режим сверху — по UI (а звёздочка покажет, что не сохранено)
                syncModeFromUi();

                setState("Готово", "bg-secondary");
            } catch (e) {
                console.error("Advanced tab load failed", e);
                setState("Ошибка", "bg-danger");
            }
        }

        async function submit() {
            try {
                if (!lastServer) return;
                syncModeFromUi();

                // если UI в AI — просто не отправляем
                if (modeNowUi() === "AI") {
                    setState("AI: редактирование запрещено", "bg-warning text-dark");
                    return;
                }
                if (saveBtn?.disabled) return;

                setState("Сохранение...", "bg-info text-dark");

                const ex   = (ctx.exchange || "BINANCE").trim();
                const net  = (ctx.network  || "TESTNET").trim();
                const type = (ctx.type || "").trim();

                const params = new URLSearchParams();
                params.set("chatId", String(ctx.chatId));
                params.set("type", type);
                params.set("exchange", ex);
                params.set("network", net);

                // все поля формы (strategy-specific)
                if (strategyForm) {
                    const fd = new FormData(strategyForm);
                    for (const [k, v] of fd.entries()) {
                        params.append(k, String(v));
                    }
                }

                const res = await fetch(ENDPOINT_SUBMIT, {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                    body: params.toString()
                });

                if (!res.ok) throw new Error(`http ${res.status}`);
                const out = await res.json();
                if (!out?.ok) {
                    setState(out?.message || "Ошибка", "bg-danger");
                    return;
                }

                setState("Сохранено", "bg-success");

                // после submit перезагрузим блок (и подтянем актуальные значения/политику)
                await load();
            } catch (e) {
                console.error("Advanced submit failed", e);
                setState("Ошибка", "bg-danger");
            }
        }

        // первичная загрузка
        load();

        // reload если меняют биржу/сеть
        const exchangeSelect = document.getElementById("exchangeSelect");
        const networkSelect  = document.getElementById("networkSelect");
        exchangeSelect?.addEventListener("change", () => setTimeout(load, 250));
        networkSelect?.addEventListener("change",  () => setTimeout(load, 250));

        // ✅ ключ: при смене режима в GENERAL — обновляем бейдж сразу (без ожидания сервера)
        controlModeSelect?.addEventListener("change", () => {
            syncModeFromUi();
            // потом чуть позже подтянем сервер (когда autosave general успеет сохранить)
            setTimeout(load, 600);
        });

        // save
        saveBtn?.addEventListener("click", () => submit());
        strategyForm?.addEventListener("submit", (e) => {
            e.preventDefault();
            submit();
        });
    }

    return { init };
})();

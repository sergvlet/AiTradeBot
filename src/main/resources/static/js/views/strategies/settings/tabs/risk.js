"use strict";

window.SettingsTabRisk = (function () {

    let started = false;

    function byId(id) { return document.getElementById(id); }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function numOrNull(v) {
        if (v == null) return null;
        const s = String(v).trim().replace(",", ".");
        if (!s) return null;
        const n = Number(s);
        return Number.isFinite(n) ? n : null;
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

    function ctxQuery() {
        const ctx = getCtx();
        if (!ctx) return "";
        const q = new URLSearchParams();
        if (ctx.chatId) q.set("chatId", String(ctx.chatId));
        if (ctx.exchange) q.set("exchange", String(ctx.exchange));
        if (ctx.network) q.set("network", String(ctx.network));
        return q.toString();
    }

    function getControlMode(ctx) {
        const sel = byId("advancedControlMode");
        const v = (sel?.value || ctx?.advancedControlMode || "MANUAL").trim().toUpperCase();
        return v || "MANUAL";
    }

    // ---------- UI ----------
    function setModeUi(mode) {
        const badge = byId("riskModeBadge");
        const help = byId("riskModeHelp");

        if (badge) {
            badge.textContent = mode;
            badge.className = "badge " + (mode === "AI"
                ? "bg-warning text-dark"
                : (mode === "HYBRID" ? "bg-info text-dark" : "bg-secondary"));
        }

        if (help) {
            help.textContent = (mode === "AI")
                ? "AI режим: поля заблокированы, значения задаёт система."
                : (mode === "HYBRID")
                    ? "HYBRID: изменения сохраняются после подтверждения."
                    : "MANUAL: изменения сохраняются автоматически.";
        }

        const form = byId("riskForm");
        if (!form) return;

        const disable = (mode === "AI");
        form.querySelectorAll("input, select, textarea, button").forEach(el => {
            if (el.id === "advancedControlMode") return;
            if (el.type === "hidden") return;
            el.disabled = disable;
        });
    }

    function setSaveUi(kind, meta, errMsg) {
        const state = byId("riskSaveState");
        const saveMeta = byId("riskSaveMeta");
        const dirtyBadge = byId("riskDirtyBadge");
        const changedList = byId("riskChangedList");

        if (kind === "saving") {
            if (state) { state.className = "badge bg-secondary"; state.textContent = "Сохранение…"; }
            if (saveMeta) saveMeta.textContent = nowHHmm();
            return;
        }

        if (kind === "ok") {
            if (dirtyBadge) dirtyBadge.classList.add("d-none");
            if (changedList) changedList.textContent = "";
            if (state) { state.className = "badge bg-success"; state.textContent = "Сохранено ✓"; }
            if (saveMeta) saveMeta.textContent = meta || nowHHmm();
            return;
        }

        if (kind === "err") {
            if (state) { state.className = "badge bg-danger"; state.textContent = "Ошибка"; }
            if (saveMeta) saveMeta.textContent = errMsg || "проверь API";
            return;
        }

        if (state) { state.className = "badge bg-secondary"; state.textContent = "Готово"; }
        if (saveMeta) saveMeta.textContent = "";
    }

    function markChanged(label) {
        const dirtyBadge = byId("riskDirtyBadge");
        const changedList = byId("riskChangedList");

        if (dirtyBadge) dirtyBadge.classList.remove("d-none");

        if (changedList && label) {
            const cur = (changedList.textContent || "").trim();
            if (!cur.includes(label)) changedList.textContent = cur ? (cur + ", " + label) : label;
        }
    }

    // ---------- confirm ----------
    function showConfirm(title, text) {
        return new Promise((resolve) => {
            const modalEl = byId("generalConfirmModal");
            const titleEl = byId("generalConfirmTitle");
            const textEl = byId("generalConfirmText");
            const okBtn = byId("generalConfirmOk");

            if (!modalEl || !window.bootstrap?.Modal || !okBtn) {
                resolve(true);
                return;
            }

            if (titleEl) titleEl.textContent = title || "Подтверждение";
            if (textEl) textEl.textContent = text || "Сохранить изменения?";

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

    // ---------- save ----------
    async function saveRisk(scopePayload) {
        const ctx = getCtx();
        if (!ctx?.type) return;

        const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`;

        const payload = Object.assign({
            saveScope: "risk",
            tab: "risk",
            exchange: ctx.exchange || "",
            network: ctx.network || ""
        }, scopePayload || {});

        if (window.SettingsApi?.postForm) {
            await window.SettingsApi.postForm(url, payload);
            return;
        }

        const body = new URLSearchParams();
        Object.entries(payload).forEach(([k, v]) => {
            if (v !== undefined && v !== null) body.append(k, String(v));
        });

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With": "fetch"
            },
            body
        });

        if (!resp.ok) throw new Error("HTTP " + resp.status);
    }

    // =====================================================
    // ✅ INIT
    // =====================================================
    function init() {
        if (started) return;

        const ctx = getCtx();
        if (!ctx) {
            console.warn("[risk] ctx not ready -> skip init (will retry on tab click)");
            return;
        }

        const form = byId("riskForm");
        if (!form) {
            console.warn("[risk] #riskForm not found -> skip init (will retry)");
            return;
        }

        started = true;
        console.log("[risk] init OK, binding listeners…", ctx);

        const budgetMode = byId("strategyBudgetMode");
        const budgetValue = byId("strategyBudgetValue");
        const budgetPreview = byId("strategyBudgetPreview");
        const budgetValueHint = byId("strategyBudgetValueHint");
        const budgetValueLabel = byId("strategyBudgetValueLabel");

        // ✅ ВАЖНО: это реальные id из твоего HTML
        const initialModeEl = byId("strategyBudgetInitialMode");
        const initialUsdEl = byId("strategyBudgetInitialUsd");
        const initialPctEl = byId("strategyBudgetInitialPct");

        const riskPerTrade = byId("riskPerTradePctInput");

        const prev = {
            budgetMode: (initialModeEl?.value || "NONE"),
            budgetValue: (budgetValue?.value || ""),
            riskPerTrade: (riskPerTrade?.value || "")
        };

        function updateBudgetPreview() {
            if (!budgetPreview) return;

            const m = (budgetMode?.value || "NONE").toUpperCase();
            const v = numOrNull(budgetValue?.value);

            if (m === "NONE") {
                budgetPreview.textContent = "Будет использован весь доступный баланс (без лимита).";
                return;
            }
            if (m === "USD") {
                budgetPreview.textContent = (v != null)
                    ? `Лимит: ${v} (фиксированная сумма).`
                    : "Лимит: укажи сумму.";
                return;
            }
            if (m === "PCT") {
                budgetPreview.textContent = (v != null)
                    ? `Лимит: ${v}% от доступного баланса.`
                    : "Лимит: укажи процент.";
                return;
            }
            budgetPreview.textContent = "Лимит: —";
        }

        function setBudgetUi(mode) {
            const m = (mode || "NONE").toUpperCase();

            if (budgetValueLabel) {
                budgetValueLabel.textContent = (m === "USD")
                    ? "Сумма"
                    : (m === "PCT" ? "Процент" : "Значение");
            }

            if (budgetValueHint) {
                budgetValueHint.textContent = (m === "USD")
                    ? "Максимальная сумма в валюте счёта."
                    : (m === "PCT"
                        ? "Процент от доступного баланса."
                        : "Лимит не используется.");
            }

            if (budgetValue) {
                const disable = (m === "NONE" || getControlMode(ctx) === "AI");
                budgetValue.disabled = disable;
                if (m === "NONE") budgetValue.value = "";
            }

            updateBudgetPreview();
        }

        // ✅ КЛЮЧЕВОЙ ФИКС: подтягиваем стартовое значение лимита в input,
        // чтобы не улетало null при первом же сохранении
        function applyInitialBudgetValueIfEmpty() {
            if (!budgetMode || !budgetValue) return;

            if (!isBlank(budgetValue.value)) return; // уже заполнено
            const m = (budgetMode.value || "NONE").toUpperCase();

            if (m === "USD") {
                const v = (initialUsdEl?.value || "").trim();
                if (!isBlank(v)) budgetValue.value = v;
            } else if (m === "PCT") {
                const v = (initialPctEl?.value || "").trim();
                if (!isBlank(v)) budgetValue.value = v;
            }

            updateBudgetPreview();
            prev.budgetValue = budgetValue.value || "";
        }

        let timer = null;
        let inFlight = false;

        async function commitSave(payloadBuilder, rollback) {
            if (inFlight) return;
            inFlight = true;

            setSaveUi("saving");

            try {
                await saveRisk(payloadBuilder());
                setSaveUi("ok", nowHHmm());
            } catch (e) {
                console.error("[risk] save failed:", e);
                setSaveUi("err", "", String(e?.message || "ошибка"));
                if (typeof rollback === "function") rollback();
            } finally {
                inFlight = false;
                clearTimeout(timer);
                timer = setTimeout(() => setSaveUi("idle"), 900);
            }
        }

        function scheduleSave(ms, payloadBuilder, rollback) {
            clearTimeout(timer);
            timer = setTimeout(() => {
                timer = null;
                commitSave(payloadBuilder, rollback);
            }, Math.max(0, ms | 0));
        }

        async function maybeConfirmIfHybrid() {
            const mode = getControlMode(ctx);
            if (mode !== "HYBRID") return true;
            return await showConfirm(
                "Подтверждение",
                "Изменение параметров риска влияет на объём сделок. Сохранить?"
            );
        }

        // ✅ Защита от затирания: при смене режима USD/PCT без значения — не сохраняем null
        function hasValidBudgetValueFor(mode) {
            if (!budgetValue) return false;
            const m = (mode || "NONE").toUpperCase();
            if (m === "NONE") return true;

            const raw = (budgetValue.value || "").trim();
            const n = numOrNull(raw);
            if (n == null) return false;

            // доп. защита (0 — бессмысленно)
            return n > 0;
        }

        // ----- budget mode -----
        if (budgetMode) {
            if (initialModeEl && initialModeEl.value) budgetMode.value = initialModeEl.value;
            prev.budgetMode = budgetMode.value || "NONE";

            setBudgetUi(prev.budgetMode);
            applyInitialBudgetValueIfEmpty(); // ✅ вот тут

            budgetMode.addEventListener("change", async () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") {
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                    applyInitialBudgetValueIfEmpty();
                    return;
                }

                const nextMode = (budgetMode.value || "NONE").toUpperCase();
                markChanged("лимит капитала");
                setBudgetUi(nextMode);
                applyInitialBudgetValueIfEmpty();

                // ✅ если выбрали USD/PCT и значение пустое/невалидное — не даём сохранить null
                if (!hasValidBudgetValueFor(nextMode)) {
                    updateBudgetPreview();
                    // откатываем режим обратно (чтобы не терять старое значение в БД)
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                    applyInitialBudgetValueIfEmpty();
                    setSaveUi("err", "", "укажи значение лимита (> 0)");
                    return;
                }

                const ok = await maybeConfirmIfHybrid();
                if (!ok) {
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                    applyInitialBudgetValueIfEmpty();
                    return;
                }

                await commitSave(() => {
                    const payload = {};
                    const v = (budgetValue?.value || "").trim() || null;

                    if (nextMode === "USD") {
                        payload.maxExposureUsd = v;
                        payload.maxExposurePct = null;
                    } else if (nextMode === "PCT") {
                        payload.maxExposurePct = v;
                        payload.maxExposureUsd = null;
                    } else {
                        payload.maxExposureUsd = null;
                        payload.maxExposurePct = null;
                    }
                    return payload;
                }, () => {
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                    applyInitialBudgetValueIfEmpty();
                });

                prev.budgetMode = budgetMode.value || "NONE";
                prev.budgetValue = budgetValue?.value || "";
            });
        }

        // ----- budget value -----
        if (budgetValue) {
            prev.budgetValue = budgetValue.value || "";

            budgetValue.addEventListener("input", () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") return;

                markChanged("значение лимита");
                updateBudgetPreview();

                scheduleSave(650, () => {
                    const m = (budgetMode?.value || "NONE").toUpperCase();
                    const payload = {};
                    const v = (budgetValue.value || "").trim() || null;

                    // ✅ если пусто/невалидно — не шлём null (не затираем БД)
                    if (m === "USD") {
                        if (numOrNull(v) == null) return {};
                        payload.maxExposureUsd = v;
                    } else if (m === "PCT") {
                        if (numOrNull(v) == null) return {};
                        payload.maxExposurePct = v;
                    }
                    return payload;
                }, () => {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                });
            });

            budgetValue.addEventListener("change", async () => {
                clearTimeout(timer);

                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                    return;
                }

                const v = (budgetValue.value || "").trim();
                const n = isBlank(v) ? null : numOrNull(v);

                // ✅ если очищено — НЕ сохраняем null (чтобы не потерять лимит случайно),
                // а откатываем значение обратно
                if (isBlank(v)) {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                    setSaveUi("err", "", "нельзя пусто (иначе сбросишь лимит)");
                    return;
                }

                // ✅ не число — откат
                if (n == null || n <= 0) {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                    setSaveUi("err", "", "значение должно быть > 0");
                    return;
                }

                markChanged("значение лимита");
                updateBudgetPreview();

                const ok = await maybeConfirmIfHybrid();
                if (!ok) {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                    return;
                }

                await commitSave(() => {
                    const m = (budgetMode?.value || "NONE").toUpperCase();
                    const payload = {};
                    const vv = (budgetValue.value || "").trim() || null;
                    if (m === "USD") payload.maxExposureUsd = vv;
                    else if (m === "PCT") payload.maxExposurePct = vv;
                    return payload;
                }, () => {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                });

                prev.budgetValue = budgetValue.value || "";
            });
        }

        // ----- risk per trade -----
        if (riskPerTrade) {
            prev.riskPerTrade = riskPerTrade.value || "";

            riskPerTrade.addEventListener("input", () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") return;

                markChanged("риск на сделку");

                scheduleSave(650, () => {
                    const raw = (riskPerTrade.value || "").trim();
                    if (isBlank(raw)) return {}; // ✅ не затираем случайно
                    const n = numOrNull(raw);
                    if (n == null || n < 0) return {}; // невалид — не шлём
                    return { riskPerTradePct: raw };
                }, () => {
                    riskPerTrade.value = prev.riskPerTrade;
                });
            });

            riskPerTrade.addEventListener("change", async () => {
                clearTimeout(timer);

                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") {
                    riskPerTrade.value = prev.riskPerTrade;
                    return;
                }

                const raw = (riskPerTrade.value || "").trim();

                // ✅ пусто — откат (чтобы не улетело null)
                if (isBlank(raw)) {
                    riskPerTrade.value = prev.riskPerTrade;
                    setSaveUi("err", "", "нельзя пусто");
                    return;
                }

                const n = numOrNull(raw);
                if (n == null || n < 0) {
                    riskPerTrade.value = prev.riskPerTrade;
                    setSaveUi("err", "", "введи число");
                    return;
                }

                markChanged("риск на сделку");

                const ok = await maybeConfirmIfHybrid();
                if (!ok) {
                    riskPerTrade.value = prev.riskPerTrade;
                    return;
                }

                await commitSave(() => ({
                    riskPerTradePct: raw
                }), () => {
                    riskPerTrade.value = prev.riskPerTrade;
                });

                prev.riskPerTrade = riskPerTrade.value || "";
            });
        }

        function syncMode() {
            const mode = getControlMode(ctx);
            ctx.advancedControlMode = mode;
            setModeUi(mode);
            setBudgetUi(budgetMode?.value || "NONE");
            applyInitialBudgetValueIfEmpty();
            if (mode === "AI") setSaveUi("ok", nowHHmm());
        }

        window.addEventListener("strategy:controlModeChanged", (e) => {
            const m = String(e?.detail?.mode || "").toUpperCase();
            if (m) {
                ctx.advancedControlMode = m;
                syncMode();
            }
        });

        setSaveUi("idle");
        syncMode();
        updateBudgetPreview();
    }

    return { init };
})();

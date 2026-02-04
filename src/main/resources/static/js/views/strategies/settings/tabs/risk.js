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

    function getCtx() {
        return window.StrategySettingsContext || null;
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
            // ничего лишнего не блокируем, если вдруг появится кнопка
            if (el.id === "advancedControlMode") return;
            if (el.type === "hidden") return;
            el.disabled = disable;
        });
    }

    function setSaveUi(kind, meta) {
        const state = byId("riskSaveState");
        const saveMeta = byId("riskSaveMeta");
        const dirtyBadge = byId("riskDirtyBadge");
        const changedList = byId("riskChangedList");

        if (kind === "saving") {
            if (state) { state.className = "badge bg-secondary"; state.textContent = "Сохранение…"; }
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
            if (saveMeta) saveMeta.textContent = "проверь API";
            return;
        }

        // idle
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
            const textEl  = byId("generalConfirmText");
            const okBtn   = byId("generalConfirmOk");

            // если bootstrap нет — не блокируем UX
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

    // ---------- save ----------
    async function saveRisk(scopePayload) {
        const ctx = getCtx();
        if (!ctx?.type) return;

        const url = `/strategies/${encodeURIComponent(String(ctx.type))}/config?${ctxQuery()}`;

        // ⚠️ отправляем только реально существующие поля
        const payload = Object.assign({
            saveScope: "risk",
            tab: "risk"
        }, scopePayload || {});

        // используем общий API если есть, иначе fetch
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

    // ---------- bind ----------
    function init() {
        if (started) return;
        started = true;

        const ctx = getCtx();
        if (!ctx) return;

        const form = byId("riskForm");
        if (!form) return;

        // элементы из твоего текущего “минимального risk”
        const budgetMode = byId("strategyBudgetMode");
        const budgetValue = byId("strategyBudgetValue");
        const budgetPreview = byId("strategyBudgetPreview");
        const budgetValueHint = byId("strategyBudgetValueHint");
        const budgetValueLabel = byId("strategyBudgetValueLabel");

        const initialModeEl = byId("strategyBudgetInitialMode");
        const maxExposureUsd = byId("maxExposureUsd");
        const maxExposurePct = byId("maxExposurePct");

        const riskPerTrade = byId("riskPerTradePctInput");

        // чтобы при HYBRID “откатывать”
        const prev = {
            budgetMode: (initialModeEl?.value || "NONE"),
            budgetValue: "",
            riskPerTrade: (riskPerTrade?.value || "")
        };

        // ----- budget UI helpers -----
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

            // в NONE value можно скрыть/заблокировать
            if (budgetValue) {
                const disable = (m === "NONE" || getControlMode(ctx) === "AI");
                budgetValue.disabled = disable;
                if (m === "NONE") budgetValue.value = "";
            }

            updateBudgetPreview();
        }

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

        // ----- save pipeline -----
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
                setSaveUi("err");
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

        // ----- binds: budget mode/value -----
        if (budgetMode) {
            // init from server flags (maxExposureUsd/maxExposurePct)
            if (initialModeEl && initialModeEl.value) {
                budgetMode.value = initialModeEl.value;
            }
            prev.budgetMode = budgetMode.value || "NONE";

            budgetMode.addEventListener("change", async () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") {
                    budgetMode.value = prev.budgetMode;
                    return;
                }

                const nextMode = (budgetMode.value || "NONE").toUpperCase();
                markChanged("лимит капитала");
                setBudgetUi(nextMode);

                const ok = await maybeConfirmIfHybrid();
                if (!ok) {
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                    return;
                }

                // сохраняем: записываем только одно поле (USD или PCT), второе чистим
                await commitSave(() => {
                    const payload = {};
                    if (nextMode === "USD") {
                        payload.maxExposureUsd = (budgetValue?.value || "").trim() || null;
                        payload.maxExposurePct = null;
                        if (maxExposureUsd) maxExposureUsd.value = payload.maxExposureUsd || "";
                        if (maxExposurePct) maxExposurePct.value = "";
                    } else if (nextMode === "PCT") {
                        payload.maxExposurePct = (budgetValue?.value || "").trim() || null;
                        payload.maxExposureUsd = null;
                        if (maxExposurePct) maxExposurePct.value = payload.maxExposurePct || "";
                        if (maxExposureUsd) maxExposureUsd.value = "";
                    } else {
                        payload.maxExposureUsd = null;
                        payload.maxExposurePct = null;
                        if (maxExposureUsd) maxExposureUsd.value = "";
                        if (maxExposurePct) maxExposurePct.value = "";
                    }
                    return payload;
                }, () => {
                    budgetMode.value = prev.budgetMode;
                    setBudgetUi(prev.budgetMode);
                });

                prev.budgetMode = budgetMode.value || "NONE";
            });
        }

        if (budgetValue) {
            prev.budgetValue = budgetValue.value || "";

            budgetValue.addEventListener("input", () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") return;

                markChanged("значение лимита");
                updateBudgetPreview();

                // debounce save
                scheduleSave(650, () => {
                    const m = (budgetMode?.value || "NONE").toUpperCase();
                    const payload = {};
                    if (m === "USD") payload.maxExposureUsd = (budgetValue.value || "").trim() || null;
                    else if (m === "PCT") payload.maxExposurePct = (budgetValue.value || "").trim() || null;
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

                // валидация числа
                const v = budgetValue.value;
                if (!isBlank(v) && numOrNull(v) == null) {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
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
                    if (m === "USD") payload.maxExposureUsd = (budgetValue.value || "").trim() || null;
                    if (m === "PCT") payload.maxExposurePct = (budgetValue.value || "").trim() || null;
                    return payload;
                }, () => {
                    budgetValue.value = prev.budgetValue;
                    updateBudgetPreview();
                });

                prev.budgetValue = budgetValue.value || "";
            });
        }

        // ----- bind: riskPerTradePct -----
        if (riskPerTrade) {
            prev.riskPerTrade = riskPerTrade.value || "";

            riskPerTrade.addEventListener("input", () => {
                const modeNow = getControlMode(ctx);
                if (modeNow === "AI") return;

                markChanged("риск на сделку");
                scheduleSave(650, () => ({
                    riskPerTradePct: (riskPerTrade.value || "").trim() || null
                }), () => {
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

                const v = riskPerTrade.value;
                if (!isBlank(v) && numOrNull(v) == null) {
                    riskPerTrade.value = prev.riskPerTrade;
                    return;
                }

                markChanged("риск на сделку");

                const ok = await maybeConfirmIfHybrid();
                if (!ok) {
                    riskPerTrade.value = prev.riskPerTrade;
                    return;
                }

                await commitSave(() => ({
                    riskPerTradePct: (riskPerTrade.value || "").trim() || null
                }), () => {
                    riskPerTrade.value = prev.riskPerTrade;
                });

                prev.riskPerTrade = riskPerTrade.value || "";
            });
        }

        // ----- sync mode changes from Control tab -----
        function syncMode() {
            const mode = getControlMode(ctx);
            ctx.advancedControlMode = mode;
            setModeUi(mode);
            setBudgetUi(budgetMode?.value || "NONE");

            // если AI — сбрасываем “грязь”
            if (mode === "AI") {
                setSaveUi("ok", nowHHmm());
            }
        }

        const controlModeSelect = byId("advancedControlMode");
        if (controlModeSelect) {
            controlModeSelect.addEventListener("change", syncMode);
        }

        window.addEventListener("strategy:controlModeChanged", (e) => {
            const m = String(e?.detail?.mode || "").toUpperCase();
            if (m) {
                ctx.advancedControlMode = m;
                syncMode();
            }
        });

        // first render
        setSaveUi("idle");
        syncMode();
        updateBudgetPreview();
    }

    // safe boot
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }

    return { init };
})();

"use strict";

window.SettingsTabGeneral = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const form = document.getElementById("generalForm");
        if (!form) return;

        // =====================================================
        // UI
        // =====================================================
        const saveState   = document.getElementById("generalSaveState");
        const saveMeta    = document.getElementById("generalSaveMeta");
        const changedList = document.getElementById("generalChangedList");
        const dirtyBadge  = document.getElementById("generalDirtyBadge");
        const applyBtn    = document.getElementById("generalApplyBtn");

        const confirmModalEl = document.getElementById("generalConfirmModal");
        const confirmTitleEl = document.getElementById("generalConfirmTitle");
        const confirmTextEl  = document.getElementById("generalConfirmText");
        const confirmOkBtn   = document.getElementById("generalConfirmOk");

        const controlModeSelect   = document.getElementById("advancedControlMode");
        const controlModeProgress = document.getElementById("controlModeProgress");

        const accountAssetSelect   = document.getElementById("accountAssetSelect");
        const selectedAssetView    = document.getElementById("selectedAssetView");
        const availableBalanceView = document.getElementById("availableBalanceView");

        const exposureMode          = document.getElementById("strategyBudgetMode");
        const exposureValue         = document.getElementById("strategyBudgetValue");
        const exposureValueReadonly = document.getElementById("strategyBudgetValueReadonly");

        const exposureValueLabel = document.getElementById("strategyBudgetValueLabel");
        const exposureValueHint  = document.getElementById("strategyBudgetValueHint");
        const exposurePreview    = document.getElementById("strategyBudgetPreview");

        const maxExposureUsdHidden = document.getElementById("maxExposureUsd");
        const maxExposurePctHidden = document.getElementById("maxExposurePct");
        const exposureInitialMode  = document.getElementById("strategyBudgetInitialMode");

        const dailyLossInput   = document.getElementById("dailyLossLimitPct");
        const reinvestCheckbox = document.getElementById("reinvestProfit");

        // =====================================================
        // AUTOSAVE
        // =====================================================
        const rootEl = document.getElementById("generalHeader") || form;

        const AUTOSAVE_ENDPOINT = "/api/strategy/settings/autosave";
        const BALANCE_ENDPOINT  = "/api/strategy/settings/balance";
        const APPLY_ENDPOINT    = "/api/strategy/settings/apply";

        const autosave = window.SettingsAutoSave?.create?.({
            rootEl,
            scope: "general",
            context: ctx,
            endpoints: { autosave: AUTOSAVE_ENDPOINT },
            elements: { saveState, saveMeta, changedList, applyBtn },
            buildPayload: buildPayload
        });

        function markChanged(key) {
            if (dirtyBadge) dirtyBadge.classList.remove("d-none");
            autosave?.markChanged?.(key);
        }

        function scheduleSave(ms) {
            autosave?.scheduleSave?.(ms ?? 400);
        }

        autosave?.bindApplyButton?.();
        autosave?.initReadyState?.();

        // =====================================================
        // HELPERS
        // =====================================================
        function nowHHmm() {
            const d = new Date();
            const hh = String(d.getHours()).padStart(2, "0");
            const mm = String(d.getMinutes()).padStart(2, "0");
            return `${hh}:${mm}`;
        }

        function parseNumberLoose(v) {
            if (v == null) return null;
            const s = String(v).trim().replace(",", ".");
            if (!s) return null;
            const n = Number(s);
            return Number.isFinite(n) ? n : null;
        }

        function fmt(n, decimals = 8) {
            if (!Number.isFinite(n)) return "—";
            return n.toFixed(decimals).replace(/\.?0+$/, "");
        }

        function setSavedUiHint(extraMeta) {
            if (dirtyBadge) dirtyBadge.classList.add("d-none");
            if (saveState) {
                saveState.classList.remove("bg-secondary");
                saveState.classList.add("bg-success");
                saveState.textContent = "Сохранено ✓";
            }
            if (saveMeta) saveMeta.textContent = extraMeta || nowHHmm();
        }

        function setSavingUiHint() {
            if (saveState) {
                saveState.classList.remove("bg-success");
                saveState.classList.add("bg-secondary");
                saveState.textContent = "Сохранение…";
            }
        }

        function setErrorUiHint() {
            if (saveState) {
                saveState.classList.remove("bg-success");
                saveState.classList.add("bg-secondary");
                saveState.textContent = "Ошибка";
            }
            if (saveMeta) saveMeta.textContent = "проверь API";
        }

        function setApplyingUiHint() {
            if (saveState) {
                saveState.classList.remove("bg-success");
                saveState.classList.add("bg-secondary");
                saveState.textContent = "Применяю AI…";
            }
            if (saveMeta) saveMeta.textContent = nowHHmm();
        }

        function showProgress(on) {
            if (!controlModeProgress) return;
            controlModeProgress.classList.toggle("d-none", !on);
        }

        function getAssetForUi() {
            const a1 = (accountAssetSelect?.value || "").trim();
            if (a1) return a1;

            const a2 = (selectedAssetView?.textContent || "").trim();
            if (a2 && a2 !== "—") return a2;

            return null;
        }

        function getFreeBalanceNumber() {
            const raw = (availableBalanceView?.value || "").trim();
            return parseNumberLoose(raw);
        }

        function setBalanceUi(asset, freeValue) {
            if (selectedAssetView) selectedAssetView.textContent = asset || "—";
            if (availableBalanceView) {
                const v = (freeValue === null || freeValue === undefined || freeValue === "")
                    ? "—"
                    : String(freeValue);
                availableBalanceView.value = v;
            }
        }

        // =====================================================
        // ✅ ЖЁСТКИЙ AUTOSAVE: гарантирует payload из buildPayload
        // (обходит кривую реализацию SettingsAutoSave.saveNow)
        // =====================================================
        async function postAutosaveDirect(payload) {
            const res = await fetch(AUTOSAVE_ENDPOINT, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "application/json"
                },
                body: JSON.stringify(payload)
            });

            if (!res.ok) throw new Error(`autosave http ${res.status}`);

            const text = await res.text();
            try { return JSON.parse(text); } catch { return null; }
        }

        // =====================================================
        // ✅ FIX: синхронизация контекста (обязательно!)
        // если режим сохранялся в другую строку — это покажет и починит
        // =====================================================
        function syncContextFromServer(resp) {
            const ex = (resp?.exchange || resp?.exchangeName || "").toString().trim();
            const net = (resp?.network || resp?.networkType || "").toString().trim();
            const type = (resp?.type || "").toString().trim();

            if (ex) ctx.exchange = ex;
            if (net) ctx.network = net;
            if (type) ctx.type = type;
        }

        function syncControlModeFromServer(resp) {
            const mode = String(resp?.advancedControlMode || "").trim().toUpperCase();
            if (!mode) return;

            if (controlModeSelect) {
                const cur = String(controlModeSelect.value || "").trim().toUpperCase();
                if (cur !== mode) controlModeSelect.value = mode;
                controlModeSelect.dataset.prevValue = mode;
            }

            ctx.advancedControlMode = mode;
            window.__StrategyControlMode = mode;

            try {
                window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode } }));
            } catch (_) {}
        }

        // =====================================================
        // PAYLOAD
        // =====================================================
        function buildPayload() {
            const asset = (accountAssetSelect?.value || "").trim() || null;

            return {
                chatId: ctx.chatId,
                type: ctx.type,
                exchange: ctx.exchange,
                network: ctx.network,
                scope: "general",

                advancedControlMode: controlModeSelect ? ((controlModeSelect.value || "").trim() || null) : null,

                accountAsset: asset,
                maxExposureUsd: (maxExposureUsdHidden?.value || "").trim() || null,
                maxExposurePct: (maxExposurePctHidden?.value || "").trim() || null,
                dailyLossLimitPct: (dailyLossInput?.value || "").trim() || null,
                reinvestProfit: reinvestCheckbox ? !!reinvestCheckbox.checked : null
            };
        }

        // =====================================================
        // BALANCE API
        // =====================================================
        async function fetchBalanceSnapshot(asset) {
            const a = (asset || "").trim();
            if (!a) return null;

            const url =
                `${BALANCE_ENDPOINT}` +
                `?chatId=${encodeURIComponent(ctx.chatId)}` +
                `&type=${encodeURIComponent(ctx.type)}` +
                `&exchange=${encodeURIComponent(ctx.exchange)}` +
                `&network=${encodeURIComponent(ctx.network)}` +
                `&asset=${encodeURIComponent(a)}`;

            const res = await fetch(url, { method: "GET", headers: { "Accept": "application/json" } });
            if (!res.ok) throw new Error(`balance http ${res.status}`);

            const text = await res.text();
            try { return JSON.parse(text); } catch { throw new Error("balance not json"); }
        }

        async function refreshBalance(asset) {
            const a = (asset || "").trim() || getAssetForUi();
            if (!a) {
                setBudgetUi(false);
                return null;
            }

            if (selectedAssetView) selectedAssetView.textContent = a;

            try {
                const snap = await fetchBalanceSnapshot(a);
                const selAsset = snap?.selectedAsset || a;
                const free = snap?.selectedFreeBalance ?? "—";
                setBalanceUi(selAsset, free);

                setBudgetUi(false);
                return snap;
            } catch (e) {
                console.error("refreshBalance failed", e);
                setBudgetUi(false);
                return null;
            }
        }

        // =====================================================
        // CONFIRM
        // =====================================================
        function confirmText(el) {
            return {
                title: el?.dataset?.confirmTitle || "Подтверждение",
                text: el?.dataset?.confirmText || "Сохранить изменения?"
            };
        }

        function showConfirm(el, overrideText) {
            return new Promise((resolve) => {
                if (!confirmModalEl || !window.bootstrap?.Modal) {
                    resolve(true);
                    return;
                }

                const { title, text } = overrideText || confirmText(el);
                if (confirmTitleEl) confirmTitleEl.textContent = title;
                if (confirmTextEl) confirmTextEl.textContent = text;

                const modal = window.bootstrap.Modal.getOrCreateInstance(confirmModalEl, {
                    backdrop: "static",
                    keyboard: false
                });

                let done = false;

                const cleanup = () => {
                    confirmOkBtn?.removeEventListener("click", onOk);
                    confirmModalEl.removeEventListener("hidden.bs.modal", onHide);
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

                confirmOkBtn?.addEventListener("click", onOk, { once: true });
                confirmModalEl.addEventListener("hidden.bs.modal", onHide);

                modal.show();
            });
        }

        function shouldConfirmByPolicy(el, key, extra) {
            if (!el) return false;
            const flag = String(el.dataset.confirm || "").toLowerCase() === "true";
            if (!flag) return false;

            if (key === "budgetValue") return false;

            if (key === "budgetMode") {
                const nextMode = (extra?.nextMode || "").trim();
                return nextMode === "NONE";
            }

            return true;
        }

        // =====================================================
        // DEBOUNCE
        // =====================================================
        const Debounce = (function () {
            let t = null;

            function clear() {
                if (t) {
                    clearTimeout(t);
                    t = null;
                }
            }

            function schedule(fn, ms) {
                clear();
                t = setTimeout(() => {
                    t = null;
                    fn();
                }, Math.max(0, ms | 0));
            }

            function flush(fn) {
                clear();
                fn();
            }

            return { schedule, flush, clear };
        })();

        // =====================================================
        // ✅ SAVE NOW: сначала пробуем autosave.saveNow, но режим — только через direct
        // =====================================================
        async function autosaveNowStrict() {
            const payload = buildPayload();

            setSavingUiHint();

            // 1) прямой POST гарантирует что advancedControlMode реально уйдёт
            const resp = await postAutosaveDirect(payload);

            // 2) подтягиваем контекст и режим
            syncContextFromServer(resp);
            syncControlModeFromServer(resp);

            // 3) баланс
            const snap = resp?.snapshot || resp?.balanceSnapshot || null;
            if (snap?.selectedAsset) {
                setBalanceUi(snap.selectedAsset, snap.selectedFreeBalance);
                setBudgetUi(false);
            }

            // 4) (не обязательно) диагностический лог
            if (resp?.id) {
                console.log("✅ AUTOSAVE OK", {
                    id: resp.id, ex: resp.exchange, net: resp.network, mode: resp.advancedControlMode
                });
            }

            setSavedUiHint();
            return resp;
        }

        // =====================================================
        // APPLY
        // =====================================================
        async function applyControlMode(reason) {
            if (!controlModeSelect) return null;

            const payload = {
                chatId: ctx.chatId,
                type: ctx.type,
                exchange: ctx.exchange,
                network: ctx.network,
                advancedControlMode: (controlModeSelect.value || "").trim() || null,
                reason: (reason || "").trim() || null
            };

            const res = await fetch(APPLY_ENDPOINT, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "application/json"
                },
                body: JSON.stringify(payload)
            });

            if (!res.ok) throw new Error(`apply http ${res.status}`);

            const text = await res.text();
            try { return JSON.parse(text); } catch { return null; }
        }

        // =====================================================
        // “CHANGE” ACTION WRAPPER
        // =====================================================
        async function commitChange(el, key, action, confirmExtra, overrideConfirmText) {
            markChanged(key);

            if (shouldConfirmByPolicy(el, key, confirmExtra)) {
                const ok = await showConfirm(el, overrideConfirmText);
                if (!ok) return false;
            }

            if (typeof action === "function") {
                await action();
            }

            try {
                await autosaveNowStrict();
            } catch (e) {
                console.error("autosaveNowStrict failed", e);
                setErrorUiHint();
                return false;
            }

            scheduleSave(250);
            return true;
        }

        // =====================================================
        // BUDGET UI
        // =====================================================
        function setBudgetTexts(mode, asset, valueRaw) {
            const free = getFreeBalanceNumber();

            if (mode === "NONE") {
                if (exposureValueLabel) exposureValueLabel.textContent = "Значение";
                if (exposureValueHint) exposureValueHint.textContent = "Будет использован весь доступный баланс.";

                if (exposureValueReadonly) {
                    if (asset && free != null) exposureValueReadonly.value = `Весь доступный баланс: ${fmt(free)} ${asset}`;
                    else exposureValueReadonly.value = "Весь доступный баланс";
                }

                if (exposurePreview) {
                    if (asset && free != null) exposurePreview.textContent = `Лимит: весь баланс ≈ ${fmt(free)} ${asset}`;
                    else exposurePreview.textContent = "Лимит: весь баланс";
                }
                return;
            }

            if (mode === "USD") {
                if (exposureValueLabel) exposureValueLabel.textContent = asset ? `Сумма (${asset})` : "Сумма";
                if (exposureValueHint) exposureValueHint.textContent = asset ? `Максимальная сумма в ${asset}.` : "Максимальная сумма в выбранном активе.";

                if (exposurePreview) {
                    const v = parseNumberLoose(valueRaw);
                    if (v != null && asset) {
                        if (free != null && free > 0) {
                            const pct = (v / free) * 100;
                            exposurePreview.textContent = `Лимит: ${fmt(v)} ${asset} (≈ ${fmt(pct, 2)}% от free)`;
                        } else {
                            exposurePreview.textContent = `Лимит: ${fmt(v)} ${asset}`;
                        }
                    } else {
                        exposurePreview.textContent = asset ? `Лимит: укажи сумму (${asset})` : "Лимит: укажи сумму";
                    }
                }
                return;
            }

            if (mode === "PCT") {
                if (exposureValueLabel) exposureValueLabel.textContent = "Процент (%)";
                if (exposureValueHint) exposureValueHint.textContent = "Процент от доступного баланса (0–100%).";

                if (exposurePreview) {
                    const p = parseNumberLoose(valueRaw);
                    if (p != null) {
                        const clamped = Math.min(100, Math.max(0, p));
                        if (free != null && asset) {
                            const abs = (free * clamped) / 100.0;
                            exposurePreview.textContent = `Лимит: ${fmt(clamped, 2)}% ≈ ${fmt(abs)} ${asset}`;
                        } else {
                            exposurePreview.textContent = `Лимит: ${fmt(clamped, 2)}%`;
                        }
                    } else {
                        exposurePreview.textContent = "Лимит: укажи процент (0–100)";
                    }
                }
            }
        }

        function setBudgetUi(triggerSave) {
            if (!exposureMode) return;

            const mode = (exposureMode.value || "PCT").trim();
            const asset = getAssetForUi();

            if (mode === "NONE") {
                exposureValue?.classList.add("d-none");
                exposureValueReadonly?.classList.remove("d-none");

                if (exposureValue) exposureValue.value = "";
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";

                setBudgetTexts("NONE", asset, "");

                if (triggerSave) {
                    markChanged("budget");
                    scheduleSave(400);
                }
                return;
            }

            exposureValueReadonly?.classList.add("d-none");
            exposureValue?.classList.remove("d-none");

            if (mode === "USD") {
                if (exposureValue) {
                    exposureValue.min = "0";
                    exposureValue.step = "1";
                    exposureValue.placeholder = "Напр. 500";
                    const free = getFreeBalanceNumber();
                    exposureValue.max = (free != null && free > 0) ? String(free) : "";
                }
            }

            if (mode === "PCT") {
                if (exposureValue) {
                    exposureValue.min = "0";
                    exposureValue.max = "100";
                    exposureValue.step = "0.1";
                    exposureValue.placeholder = "Напр. 10";
                }
            }

            syncBudgetHidden(triggerSave);
        }

        function syncBudgetHidden(triggerSave) {
            const mode = (exposureMode?.value || "PCT").trim();
            const raw  = (exposureValue?.value || "").trim();
            const asset = getAssetForUi();

            if (!raw) {
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";
                setBudgetTexts(mode, asset, "");

                if (triggerSave) {
                    markChanged("budget");
                    scheduleSave(400);
                }
                return;
            }

            const v = parseNumberLoose(raw);
            if (v == null || v < 0) return;

            if (mode === "USD") {
                const free = getFreeBalanceNumber();
                const clamped = (free != null && free > 0) ? Math.min(v, free) : v;

                if (maxExposureUsdHidden) maxExposureUsdHidden.value = String(clamped);
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";
                if (exposureValue && clamped !== v) exposureValue.value = String(clamped);

                setBudgetTexts("USD", asset, String(clamped));
            }

            if (mode === "PCT") {
                const clamped = Math.min(100, Math.max(0, v));
                if (maxExposurePctHidden) maxExposurePctHidden.value = String(clamped);
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (exposureValue) exposureValue.value = String(clamped);

                setBudgetTexts("PCT", asset, String(clamped));
            }

            if (triggerSave) {
                markChanged("budget");
                scheduleSave(400);
            }
        }

        function moveOptionToEnd(selectEl, value) {
            if (!selectEl) return;
            const opt = Array.from(selectEl.options).find(o => o.value === value);
            if (!opt) return;
            selectEl.removeChild(opt);
            selectEl.appendChild(opt);
        }

        function initBudgetFromBackendSafe() {
            moveOptionToEnd(exposureMode, "NONE");

            let initial = (exposureInitialMode?.value || "").trim();

            if (!initial || initial === "NONE") {
                initial = "PCT";
                if (exposureValue) exposureValue.value = "10";
                if (maxExposurePctHidden) maxExposurePctHidden.value = "10";
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
            } else {
                if (initial === "USD" && exposureValue) exposureValue.value = maxExposureUsdHidden?.value || "";
                if (initial === "PCT" && exposureValue) exposureValue.value = maxExposurePctHidden?.value || "";
            }

            if (exposureMode) exposureMode.value = initial;
            setBudgetUi(false);
        }

        initBudgetFromBackendSafe();

        // =====================================================
        // FIELD HANDLERS
        // =====================================================

        if (controlModeSelect) {
            controlModeSelect.dataset.prevValue = controlModeSelect.value;
            controlModeSelect.dataset.confirm = "false";

            controlModeSelect.addEventListener("change", async () => {
                const prev = (controlModeSelect.dataset.prevValue ?? "").trim();
                const next = (controlModeSelect.value || "").trim();

                const nextUpper = String(next).toUpperCase();
                const isAiMode = (nextUpper === "HYBRID" || nextUpper === "AI");

                markChanged("advancedControlMode");

                window.__StrategyControlMode = nextUpper;
                try {
                    window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode: nextUpper } }));
                } catch (_) {}

                if (isAiMode) {
                    showProgress(true);
                    setApplyingUiHint();
                } else {
                    setSavingUiHint();
                }

                try {
                    // ✅ строгое сохранение (гарантирует advancedControlMode)
                    const saved = await autosaveNowStrict();

                    if (!saved) throw new Error("autosave returned null");

                    // 2) для HYBRID/AI — apply
                    if (isAiMode) {
                        try {
                            const applyResp = await applyControlMode(null);
                            // applyResp может быть null — не критично
                            setSavedUiHint(`${nowHHmm()} • apply ok`);
                        } catch (e) {
                            console.error("applyControlMode failed", e);
                            setSavedUiHint(`${nowHHmm()} • apply error`);
                        } finally {
                            showProgress(false);
                        }
                    } else {
                        showProgress(false);
                        setSavedUiHint();
                    }

                    controlModeSelect.dataset.prevValue = String(controlModeSelect.value || "").trim();

                } catch (e) {
                    console.error("controlMode change failed", e);

                    // откат UI
                    controlModeSelect.value = prev;
                    controlModeSelect.dataset.prevValue = prev;

                    window.__StrategyControlMode = String(prev).trim().toUpperCase();
                    try {
                        window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode: window.__StrategyControlMode } }));
                    } catch (_) {}

                    showProgress(false);
                    setErrorUiHint();
                }

                scheduleSave(250);
            });
        }

        if (accountAssetSelect) {
            accountAssetSelect.dataset.prevValue = (accountAssetSelect.value || "").trim();

            accountAssetSelect.addEventListener("change", async () => {
                const prev = accountAssetSelect.dataset.prevValue ?? "";
                const next = (accountAssetSelect.value || "").trim();

                await refreshBalance(next);

                const ok = await commitChange(
                    accountAssetSelect,
                    "accountAsset",
                    async () => { accountAssetSelect.dataset.prevValue = next; }
                );

                if (!ok) {
                    accountAssetSelect.value = prev;
                    await refreshBalance(prev);
                }
            });
        }

        if (dailyLossInput) {
            dailyLossInput.dataset.prevValue = dailyLossInput.value;

            dailyLossInput.addEventListener("input", () => {
                markChanged("dailyLossLimitPct");
                Debounce.schedule(async () => {
                    await commitChange(dailyLossInput, "dailyLossLimitPct", async () => {});
                }, 1200);
            });

            dailyLossInput.addEventListener("change", async () => {
                Debounce.clear();

                const prev = dailyLossInput.dataset.prevValue ?? "";
                const next = dailyLossInput.value;

                const ok = await commitChange(
                    dailyLossInput,
                    "dailyLossLimitPct",
                    async () => { dailyLossInput.dataset.prevValue = next; }
                );

                if (!ok) dailyLossInput.value = prev;
            });
        }

        if (reinvestCheckbox) {
            reinvestCheckbox.addEventListener("change", async () => {
                await commitChange(reinvestCheckbox, "reinvestProfit", async () => {});
            });
        }

        if (exposureMode) {
            exposureMode.dataset.prevValue = exposureMode.value;

            exposureMode.addEventListener("change", async () => {
                const prev = exposureMode.dataset.prevValue ?? "";
                const next = (exposureMode.value || "PCT").trim();

                setBudgetUi(false);
                markChanged("budget");

                const ok = await commitChange(
                    exposureMode,
                    "budgetMode",
                    async () => {
                        exposureMode.dataset.prevValue = next;
                        syncBudgetHidden(false);
                    },
                    { nextMode: next },
                    next === "NONE"
                        ? { title: "Внимание", text: "Режим «Весь баланс» опасен. Стратегия сможет использовать весь доступный free. Включить?" }
                        : null
                );

                if (!ok) {
                    exposureMode.value = prev;
                    setBudgetUi(false);
                    syncBudgetHidden(false);
                }
            });
        }

        if (exposureValue) {
            exposureValue.dataset.prevValue = exposureValue.value;
            exposureValue.dataset.confirm = "false";

            exposureValue.addEventListener("input", () => {
                syncBudgetHidden(false);
                markChanged("budget");

                Debounce.schedule(async () => {
                    const n = parseNumberLoose(exposureValue.value);
                    if (n == null) return;

                    syncBudgetHidden(false);

                    exposureValue.dataset.prevValue = exposureValue.value;
                    try {
                        await autosaveNowStrict();
                    } catch (e) {
                        console.error("budget autosave failed", e);
                        setErrorUiHint();
                    }
                    scheduleSave(250);
                }, 1200);
            });

            const saveBudgetValueNow = async () => {
                Debounce.flush(async () => {
                    const n = parseNumberLoose(exposureValue.value);
                    if (n == null) {
                        exposureValue.value = exposureValue.dataset.prevValue ?? "";
                        syncBudgetHidden(false);
                        return;
                    }

                    syncBudgetHidden(false);
                    exposureValue.dataset.prevValue = exposureValue.value;

                    try {
                        await autosaveNowStrict();
                    } catch (e) {
                        console.error("budget autosave failed", e);
                        setErrorUiHint();
                    }
                    scheduleSave(250);
                });
            };

            exposureValue.addEventListener("change", saveBudgetValueNow);
            exposureValue.addEventListener("blur", saveBudgetValueNow);

            exposureValue.addEventListener("keydown", (e) => {
                if (e.key === "Enter") {
                    e.preventDefault();
                    saveBudgetValueNow();
                }
            });
        }

        // =====================================================
        // FIRST LOAD
        // =====================================================
        if (controlModeSelect) {
            const m = String(controlModeSelect.value || "").trim().toUpperCase();
            if (m) {
                window.__StrategyControlMode = m;
                try {
                    window.dispatchEvent(new CustomEvent("strategy:controlModeChanged", { detail: { mode: m } }));
                } catch (_) {}
            }
        }

        const initialAsset = getAssetForUi();
        if (initialAsset) {
            refreshBalance(initialAsset).catch(() => {});
        } else {
            setBudgetUi(false);
        }
    }

    return { init };
})();

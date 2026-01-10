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

        // confirm modal
        const confirmModalEl = document.getElementById("generalConfirmModal");
        const confirmTitleEl = document.getElementById("generalConfirmTitle");
        const confirmTextEl  = document.getElementById("generalConfirmText");
        const confirmOkBtn   = document.getElementById("generalConfirmOk");

        // inputs
        const controlModeSelect = document.getElementById("advancedControlMode");

        const accountAssetSelect   = document.getElementById("accountAssetSelect");
        const selectedAssetView    = document.getElementById("selectedAssetView");
        const availableBalanceView = document.getElementById("availableBalanceView");

        // Budget
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
        const APPLY_ENDPOINT    = "/api/strategy/settings/apply";
        const BALANCE_ENDPOINT  = "/api/strategy/settings/balance";

        const autosave = window.SettingsAutoSave?.create?.({
            rootEl,
            scope: "general",
            context: ctx,
            endpoints: {
                autosave: AUTOSAVE_ENDPOINT,
                apply: APPLY_ENDPOINT
            },
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
        // HELPERS (без хардкода USDT/BTC)
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

        function setSavedUiHint() {
            if (dirtyBadge) dirtyBadge.classList.add("d-none");
            if (saveState) {
                saveState.classList.remove("bg-secondary");
                saveState.classList.add("bg-success");
                saveState.textContent = "Сохранено ✓";
            }
            if (saveMeta) saveMeta.textContent = nowHHmm();
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

                advancedControlMode: (controlModeSelect?.value || "").trim() || null,

                accountAsset: asset,
                maxExposureUsd: (maxExposureUsdHidden?.value || "").trim() || null,
                maxExposurePct: (maxExposurePctHidden?.value || "").trim() || null,

                dailyLossLimitPct: (dailyLossInput?.value || "").trim() || null,
                reinvestProfit: !!reinvestCheckbox?.checked
            };
        }

        // =====================================================
        // BALANCE API (AccountBalanceSnapshot)
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

            const res = await fetch(url, {
                method: "GET",
                headers: { "Accept": "application/json" }
            });

            if (!res.ok) throw new Error(`balance http ${res.status}`);

            const text = await res.text();
            try {
                return JSON.parse(text);
            } catch {
                throw new Error("balance not json");
            }
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

                // пересчитать бюджет/preview под этот актив
                setBudgetUi(false);

                return snap;
            } catch (e) {
                console.error("refreshBalance failed", e);
                // не затираем текущее значение, но обновим подписи/preview под выбранный актив
                setBudgetUi(false);
                return null;
            }
        }

        // =====================================================
        // CONFIRM MODAL
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

        // =====================================================
        // CONFIRM POLICY (главное: НЕ мешаем вводить)
        // =====================================================
        function shouldConfirmByPolicy(el, key, extra) {
            if (!el) return false;

            // Если вообще нет data-confirm — не подтверждаем
            const flag = String(el.dataset.confirm || "").toLowerCase() === "true";
            if (!flag) return false;

            // ✅ Никогда не подтверждаем ввод числа (иначе невозможно настроить)
            if (key === "budgetValue") return false;

            // ✅ Режим бюджета подтверждаем ТОЛЬКО если включают "весь баланс"
            if (key === "budgetMode") {
                const nextMode = (extra?.nextMode || "").trim();
                return nextMode === "NONE";
            }

            // Остальные поля — как раньше (если в HTML стоит confirm)
            return true;
        }

        // =====================================================
        // DEBOUNCE (чтобы автосейв не дергался на каждую цифру)
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
        // SAVE NOW (с fallback если SettingsAutoSave не умеет saveNow)
        // =====================================================
        async function fallbackSaveNow() {
            const payload = buildPayload();
            setSavingUiHint();

            try {
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
                let data = null;
                try { data = JSON.parse(text); } catch { data = null; }

                const snap = data?.snapshot || data?.balanceSnapshot || null;
                if (snap?.selectedAsset) {
                    setBalanceUi(snap.selectedAsset, snap.selectedFreeBalance);
                    setBudgetUi(false);
                }

                setSavedUiHint();
                return data;
            } catch (e) {
                console.error("fallbackSaveNow failed", e);
                setErrorUiHint();
                return null;
            }
        }

        async function autosaveNow() {
            if (!autosave) return null;

            if (typeof autosave.saveNow === "function") {
                setSavingUiHint();
                try {
                    const resp = await autosave.saveNow();
                    const snap = resp?.snapshot || resp?.balanceSnapshot || null;
                    if (snap?.selectedAsset) {
                        setBalanceUi(snap.selectedAsset, snap.selectedFreeBalance);
                        setBudgetUi(false);
                    }
                    setSavedUiHint();
                    return resp;
                } catch (e) {
                    console.error("autosave.saveNow failed", e);
                    setErrorUiHint();
                    return null;
                }
            }

            return await fallbackSaveNow();
        }

        // =====================================================
        // “CHANGE” ACTION WRAPPER (confirm -> action -> save)
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

            await autosaveNow();
            scheduleSave(250);
            return true;
        }

        // =====================================================
        // BUDGET UI (dynamic by asset + free)
        // =====================================================
        function setBudgetTexts(mode, asset, valueRaw) {
            const free = getFreeBalanceNumber(); // number|null

            if (mode === "NONE") {
                if (exposureValueLabel) exposureValueLabel.textContent = "Значение";
                if (exposureValueHint) exposureValueHint.textContent = "Будет использован весь доступный баланс.";

                if (exposureValueReadonly) {
                    if (asset && free != null) {
                        exposureValueReadonly.value = `Весь доступный баланс: ${fmt(free)} ${asset}`;
                    } else {
                        exposureValueReadonly.value = "Весь доступный баланс";
                    }
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

            const mode = (exposureMode.value || "PCT").trim(); // безопасный дефолт
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
                    // max = free (если известно)
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

        // =====================================================
        // SAFE DEFAULT: НЕ ставим "весь баланс" первым/по умолчанию
        // =====================================================
        function moveOptionToEnd(selectEl, value) {
            if (!selectEl) return;
            const opt = Array.from(selectEl.options).find(o => o.value === value);
            if (!opt) return;
            selectEl.removeChild(opt);
            selectEl.appendChild(opt);
        }

        function initBudgetFromBackendSafe() {
            // "NONE" в конец
            moveOptionToEnd(exposureMode, "NONE");

            // initial mode from backend
            let initial = (exposureInitialMode?.value || "").trim();

            // если лимита нет — ставим безопасно PCT=10
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

        // --- controlMode: confirm (если стоит data-confirm), rollback
        if (controlModeSelect) {
            controlModeSelect.dataset.prevValue = controlModeSelect.value;

            controlModeSelect.addEventListener("change", async () => {
                const prev = controlModeSelect.dataset.prevValue ?? "";
                const next = controlModeSelect.value;

                const ok = await commitChange(
                    controlModeSelect,
                    "advancedControlMode",
                    async () => { controlModeSelect.dataset.prevValue = next; }
                );

                if (!ok) controlModeSelect.value = prev;
            });
        }

        // --- accountAsset: любой токен, без хардкода
        if (accountAssetSelect) {
            accountAssetSelect.dataset.prevValue = (accountAssetSelect.value || "").trim();

            accountAssetSelect.addEventListener("change", async () => {
                const prev = accountAssetSelect.dataset.prevValue ?? "";
                const next = (accountAssetSelect.value || "").trim();

                // сразу обновим баланс/preview
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

        // --- dailyLoss: не дергаем модалку на input, только на change
        if (dailyLossInput) {
            dailyLossInput.dataset.prevValue = dailyLossInput.value;

            dailyLossInput.addEventListener("input", () => {
                markChanged("dailyLossLimitPct");
                // можно сделать мягкий автосейв после паузы (без confirm), но оставим безопасно:
                Debounce.schedule(async () => {
                    // НЕ показываем confirm на ввод — просто сохраняем через паузу
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

        // --- reinvestProfit: без confirm
        if (reinvestCheckbox) {
            reinvestCheckbox.addEventListener("change", async () => {
                await commitChange(reinvestCheckbox, "reinvestProfit", async () => {});
            });
        }

        // --- budget mode: confirm ТОЛЬКО на NONE (весь баланс), rollback
        if (exposureMode) {
            exposureMode.dataset.prevValue = exposureMode.value;

            exposureMode.addEventListener("change", async () => {
                const prev = exposureMode.dataset.prevValue ?? "";
                const next = (exposureMode.value || "PCT").trim();

                // UI меняем сразу, но если cancel — откатим
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
                        ? {
                            title: "Внимание",
                            text: "Режим «Весь баланс» опасен. Стратегия сможет использовать весь доступный free. Включить?"
                        }
                        : null
                );

                if (!ok) {
                    exposureMode.value = prev;
                    setBudgetUi(false);
                    syncBudgetHidden(false);
                }
            });
        }

        // --- budget value: ГЛАВНОЕ — НИКАКОЙ модалки, чтобы ты успевал вводить
        if (exposureValue) {
            exposureValue.dataset.prevValue = exposureValue.value;

            // Даже если в HTML стоит data-confirm=true — игнорируем для этого поля
            exposureValue.dataset.confirm = "false";

            exposureValue.addEventListener("input", () => {
                syncBudgetHidden(false);
                markChanged("budget");

                // автосейв после паузы (без confirm)
                Debounce.schedule(async () => {
                    const n = parseNumberLoose(exposureValue.value);
                    if (n == null) return;

                    // clamp уже делается в syncBudgetHidden (если USD и free известен)
                    syncBudgetHidden(false);

                    exposureValue.dataset.prevValue = exposureValue.value;
                    await autosaveNow();
                    scheduleSave(250);
                }, 1200);
            });

            // blur/change — сохранить сразу, без модалки
            const saveBudgetValueNow = async () => {
                Debounce.flush(async () => {
                    const n = parseNumberLoose(exposureValue.value);
                    if (n == null) {
                        // если мусор — откатим
                        exposureValue.value = exposureValue.dataset.prevValue ?? "";
                        syncBudgetHidden(false);
                        return;
                    }

                    syncBudgetHidden(false);
                    exposureValue.dataset.prevValue = exposureValue.value;

                    await autosaveNow();
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
        const initialAsset = getAssetForUi();
        if (initialAsset) {
            refreshBalance(initialAsset).catch(() => {});
        } else {
            setBudgetUi(false);
        }
    }

    return { init };
})();

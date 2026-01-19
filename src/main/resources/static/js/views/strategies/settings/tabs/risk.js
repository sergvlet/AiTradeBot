"use strict";

window.SettingsTabRisk = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const form = document.getElementById("riskForm");
        if (!form) return;

        // =====================================================
        // UI
        // =====================================================
        const riskModeBadge = document.getElementById("riskModeBadge");
        const riskModeHelp  = document.getElementById("riskModeHelp");

        const saveState   = document.getElementById("riskSaveState");
        const saveMeta    = document.getElementById("riskSaveMeta");
        const changedList = document.getElementById("riskChangedList");
        const dirtyBadge  = document.getElementById("riskDirtyBadge");

        // confirm modal (используем общий, если он уже есть на странице)
        const confirmModalEl = document.getElementById("generalConfirmModal");
        const confirmTitleEl = document.getElementById("generalConfirmTitle");
        const confirmTextEl  = document.getElementById("generalConfirmText");
        const confirmOkBtn   = document.getElementById("generalConfirmOk");

        // режим управления — берём из вкладки general
        const controlModeSelect = document.getElementById("advancedControlMode");

        // general (для валюты и free)
        const accountAssetSelect   = document.getElementById("accountAssetSelect");
        const selectedAssetView    = document.getElementById("selectedAssetView");
        const availableBalanceView = document.getElementById("availableBalanceView");

        // =====================================================
        // Inputs (risk)
        // =====================================================
        const riskPerTradePctInput       = document.getElementById("riskPerTradePctInput");
        const minRiskRewardInput         = document.getElementById("minRiskRewardInput");
        const leverageInput              = document.getElementById("leverageInput");

        const allowAveragingInput        = document.getElementById("allowAveragingInput");
        const cooldownSecondsInput       = document.getElementById("cooldownSecondsInput");
        const maxTradesPerDayInput       = document.getElementById("maxTradesPerDayInput");

        const maxDrawdownPctInput        = document.getElementById("maxDrawdownPctInput");
        const maxDrawdownUsdInput        = document.getElementById("maxDrawdownUsdInput");
        const drawdownPreview            = document.getElementById("drawdownPreview");

        const maxPositionPctInput        = document.getElementById("maxPositionPctInput");
        const maxPositionUsdInput        = document.getElementById("maxPositionUsdInput");
        const positionPreview            = document.getElementById("positionPreview");

        const cooldownAfterLossSecondsInput = document.getElementById("cooldownAfterLossSecondsInput");
        const maxConsecutiveLossesInput     = document.getElementById("maxConsecutiveLossesInput");
        const maxOpenOrdersInput            = document.getElementById("maxOpenOrdersInput");

        // =====================================================
        // API endpoints
        // =====================================================
        const AUTOSAVE_ENDPOINT = "/api/strategy/settings/autosave";

        // =====================================================
        // Autosave helper (без кнопок)
        // =====================================================
        function nowHHmm() {
            const d = new Date();
            const hh = String(d.getHours()).padStart(2, "0");
            const mm = String(d.getMinutes()).padStart(2, "0");
            return `${hh}:${mm}`;
        }

        function setSavingUi() {
            if (saveState) {
                saveState.classList.remove("bg-success");
                saveState.classList.add("bg-secondary");
                saveState.textContent = "Сохранение…";
            }
        }

        function setSavedUi(metaText) {
            if (dirtyBadge) dirtyBadge.classList.add("d-none");
            if (saveState) {
                saveState.classList.remove("bg-secondary");
                saveState.classList.add("bg-success");
                saveState.textContent = "Сохранено ✓";
            }
            if (saveMeta) saveMeta.textContent = metaText || nowHHmm();
        }

        function setErrorUi() {
            if (saveState) {
                saveState.classList.remove("bg-success");
                saveState.classList.add("bg-secondary");
                saveState.textContent = "Ошибка";
            }
            if (saveMeta) saveMeta.textContent = "проверь API";
        }

        function markChanged(key) {
            if (dirtyBadge) dirtyBadge.classList.remove("d-none");
            if (changedList) {
                const t = (changedList.textContent || "").trim();
                const next = key && !t.includes(key) ? (t ? (t + ", " + key) : key) : t;
                changedList.textContent = next;
            }
        }

        // =====================================================
        // Helpers (numbers, asset, free)
        // =====================================================
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

        // =====================================================
        // Confirm modal (только когда надо, и не мешает вводить)
        // =====================================================
        function showConfirm(title, text) {
            return new Promise((resolve) => {
                if (!confirmModalEl || !window.bootstrap?.Modal) {
                    resolve(true);
                    return;
                }

                if (confirmTitleEl) confirmTitleEl.textContent = title || "Подтверждение";
                if (confirmTextEl) confirmTextEl.textContent = text || "Сохранить изменения?";

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

        // Gate: input → дебаунс, blur/change → мгновенно
        const ConfirmGate = (function () {
            let timer = null;
            let inFlight = false;

            function clear() {
                if (timer) {
                    clearTimeout(timer);
                    timer = null;
                }
            }

            async function runHybridConfirm() {
                const mode = getControlMode();
                if (mode !== "HYBRID") return true;
                return await showConfirm(
                    "Подтверждение",
                    "Изменение параметров риска влияет на объём сделок. Сохранить?"
                );
            }

            async function commit(action, rollback) {
                if (inFlight) return;
                inFlight = true;
                try {
                    const ok = await runHybridConfirm();
                    if (!ok) {
                        if (typeof rollback === "function") rollback();
                        return;
                    }
                    await action();
                } finally {
                    inFlight = false;
                }
            }

            function schedule(delayMs, action, rollback) {
                clear();
                timer = setTimeout(() => {
                    timer = null;
                    commit(action, rollback);
                }, Math.max(0, delayMs | 0));
            }

            return { clear, schedule, commit };
        })();

        // =====================================================
        // Mode badge + enable/disable
        // =====================================================
        function getControlMode() {
            const v = (controlModeSelect?.value || ctx.advancedControlMode || "MANUAL").trim().toUpperCase();
            return v || "MANUAL";
        }

        function setModeUi(mode) {
            if (riskModeBadge) {
                riskModeBadge.textContent = mode;
                riskModeBadge.classList.remove("bg-success", "bg-warning", "bg-secondary");
                if (mode === "AI") riskModeBadge.classList.add("bg-warning");
                else if (mode === "HYBRID") riskModeBadge.classList.add("bg-secondary");
                else riskModeBadge.classList.add("bg-success");
            }

            if (riskModeHelp) {
                if (mode === "AI") {
                    riskModeHelp.textContent = "AI режим: значения задаёт система. Поля только для просмотра.";
                } else if (mode === "HYBRID") {
                    riskModeHelp.textContent = "HYBRID: изменения сохраняются автоматически после подтверждения.";
                } else {
                    riskModeHelp.textContent = "MANUAL: изменения сохраняются автоматически.";
                }
            }

            const disable = (mode === "AI");
            const allInputs = form.querySelectorAll("input, select, textarea");
            allInputs.forEach(el => {
                if (el.id === "advancedControlMode") return;
                el.disabled = disable;
            });
        }

        // =====================================================
        // Preview + взаимное исключение полей
        // =====================================================
        function updateDrawdownPreview() {
            if (!drawdownPreview) return;

            const asset = getAssetForUi();
            const free = getFreeBalanceNumber();

            const pct = parseNumberLoose(maxDrawdownPctInput?.value);
            const usd = parseNumberLoose(maxDrawdownUsdInput?.value);

            if (pct != null) {
                if (free != null && asset) {
                    const abs = (free * pct) / 100.0;
                    drawdownPreview.textContent = `Просадка: ${fmt(pct, 2)}% ≈ ${fmt(abs)} ${asset}`;
                } else {
                    drawdownPreview.textContent = `Просадка: ${fmt(pct, 2)}%`;
                }
                return;
            }

            if (usd != null) {
                drawdownPreview.textContent = asset ? `Просадка: ${fmt(usd)} ${asset}` : `Просадка: ${fmt(usd)}`;
                return;
            }

            drawdownPreview.textContent = "Просадка: —";
        }

        function updatePositionPreview() {
            if (!positionPreview) return;

            const asset = getAssetForUi();
            const free = getFreeBalanceNumber();

            const pct = parseNumberLoose(maxPositionPctInput?.value);
            const usd = parseNumberLoose(maxPositionUsdInput?.value);

            if (pct != null) {
                if (free != null && asset) {
                    const abs = (free * pct) / 100.0;
                    positionPreview.textContent = `Позиция: ${fmt(pct, 2)}% ≈ ${fmt(abs)} ${asset}`;
                } else {
                    positionPreview.textContent = `Позиция: ${fmt(pct, 2)}%`;
                }
                return;
            }

            if (usd != null) {
                positionPreview.textContent = asset ? `Позиция: ${fmt(usd)} ${asset}` : `Позиция: ${fmt(usd)}`;
                return;
            }

            positionPreview.textContent = "Позиция: —";
        }

        function clearPairIfFilled(primaryEl, secondaryEl) {
            const p = parseNumberLoose(primaryEl?.value);
            if (p == null) return;
            if (secondaryEl && String(secondaryEl.value || "").trim() !== "") {
                secondaryEl.value = "";
            }
        }

        // =====================================================
        // Payload (scope=risk) — НЕ затираем поля, если инпута нет
        // =====================================================
        function buildPayloadRisk() {
            return {
                chatId: ctx.chatId,
                type: ctx.type,
                exchange: ctx.exchange,
                network: ctx.network,
                scope: "risk",

                riskPerTradePct: (riskPerTradePctInput?.value || "").trim() || null,
                minRiskReward: (minRiskRewardInput?.value || "").trim() || null,
                leverage: (leverageInput?.value || "").trim() || null,

                allowAveraging: allowAveragingInput ? !!allowAveragingInput.checked : null,
                cooldownSeconds: (cooldownSecondsInput?.value || "").trim() || null,
                maxTradesPerDay: (maxTradesPerDayInput?.value || "").trim() || null,

                maxDrawdownPct: (maxDrawdownPctInput?.value || "").trim() || null,
                maxDrawdownUsd: (maxDrawdownUsdInput?.value || "").trim() || null,

                maxPositionPct: (maxPositionPctInput?.value || "").trim() || null,
                maxPositionUsd: (maxPositionUsdInput?.value || "").trim() || null,

                cooldownAfterLossSeconds: (cooldownAfterLossSecondsInput?.value || "").trim() || null,
                maxConsecutiveLosses: (maxConsecutiveLossesInput?.value || "").trim() || null,

                maxOpenOrders: (maxOpenOrdersInput?.value || "").trim() || null
            };
        }

        async function saveNow() {
            const payload = buildPayloadRisk();
            setSavingUi();

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

                // если бек вернул JSON — ок, но нам не обязателен
                await res.text().catch(() => "");
                setSavedUi();
            } catch (e) {
                console.error("risk autosave failed", e);
                setErrorUi();
            }
        }

        // =====================================================
        // Bind helpers (чтобы модалка не мешала)
        // =====================================================
        function bindNumberInput(el, key, opts) {
            if (!el) return;

            const delay = opts?.delayMs ?? 900;
            const onInput = opts?.onInput;
            const onBeforeSave = opts?.onBeforeSave;
            const confirmRollback = opts?.rollback;

            el.dataset.prevValue = el.value || "";

            el.addEventListener("input", () => {
                // ✅ AI: игнорируем (на всякий случай, если инпут не disabled)
                if (getControlMode() === "AI") return;

                markChanged(key);
                if (typeof onInput === "function") onInput();

                const prev = el.dataset.prevValue ?? "";
                ConfirmGate.schedule(delay, async () => {
                    if (getControlMode() === "AI") return;

                    if (typeof onBeforeSave === "function") onBeforeSave();
                    el.dataset.prevValue = el.value || "";
                    await saveNow();
                }, () => {
                    el.value = prev;
                    if (typeof confirmRollback === "function") confirmRollback();
                });
            });

            el.addEventListener("change", async () => {
                ConfirmGate.clear();

                // ✅ AI: откатываем и выходим
                if (getControlMode() === "AI") {
                    el.value = el.dataset.prevValue ?? "";
                    if (typeof confirmRollback === "function") confirmRollback();
                    return;
                }

                markChanged(key);

                const prev = el.dataset.prevValue ?? "";
                const next = el.value || "";

                const n = parseNumberLoose(next);
                if (next.trim() !== "" && n == null) {
                    el.value = prev;
                    if (typeof confirmRollback === "function") confirmRollback();
                    return;
                }

                await ConfirmGate.commit(async () => {
                    if (typeof onBeforeSave === "function") onBeforeSave();
                    el.dataset.prevValue = el.value || "";
                    await saveNow();
                }, () => {
                    el.value = prev;
                    if (typeof confirmRollback === "function") confirmRollback();
                });
            });
        }

        function bindCheckbox(el, key, opts) {
            if (!el) return;

            el.dataset.prevChecked = el.checked ? "1" : "0";

            el.addEventListener("change", async () => {
                // ✅ AI: откат
                if (getControlMode() === "AI") {
                    el.checked = (el.dataset.prevChecked === "1");
                    return;
                }

                markChanged(key);
                const prev = el.dataset.prevChecked === "1";
                const next = !!el.checked;

                await ConfirmGate.commit(async () => {
                    el.dataset.prevChecked = next ? "1" : "0";
                    await saveNow();
                }, () => {
                    el.checked = prev;
                });
            });
        }

        // =====================================================
        // Binds
        // =====================================================
        bindNumberInput(riskPerTradePctInput, "riskPerTradePct");
        bindNumberInput(minRiskRewardInput, "minRiskReward");
        bindNumberInput(leverageInput, "leverage", {
            onBeforeSave: () => {
                const n = parseNumberLoose(leverageInput.value);
                if (n != null && n < 1) leverageInput.value = "1";
            }
        });

        bindCheckbox(allowAveragingInput, "allowAveraging");
        bindNumberInput(cooldownSecondsInput, "cooldownSeconds");
        bindNumberInput(maxTradesPerDayInput, "maxTradesPerDay");

        // drawdown pair
        bindNumberInput(maxDrawdownPctInput, "maxDrawdownPct", {
            onInput: () => {
                clearPairIfFilled(maxDrawdownPctInput, maxDrawdownUsdInput);
                updateDrawdownPreview();
            },
            onBeforeSave: () => {
                clearPairIfFilled(maxDrawdownPctInput, maxDrawdownUsdInput);
                updateDrawdownPreview();
            },
            rollback: updateDrawdownPreview
        });

        bindNumberInput(maxDrawdownUsdInput, "maxDrawdownUsd", {
            onInput: () => {
                clearPairIfFilled(maxDrawdownUsdInput, maxDrawdownPctInput);
                updateDrawdownPreview();
            },
            onBeforeSave: () => {
                clearPairIfFilled(maxDrawdownUsdInput, maxDrawdownPctInput);
                updateDrawdownPreview();
            },
            rollback: updateDrawdownPreview
        });

        // position pair
        bindNumberInput(maxPositionPctInput, "maxPositionPct", {
            onInput: () => {
                clearPairIfFilled(maxPositionPctInput, maxPositionUsdInput);
                updatePositionPreview();
            },
            onBeforeSave: () => {
                clearPairIfFilled(maxPositionPctInput, maxPositionUsdInput);
                updatePositionPreview();
            },
            rollback: updatePositionPreview
        });

        bindNumberInput(maxPositionUsdInput, "maxPositionUsd", {
            onInput: () => {
                clearPairIfFilled(maxPositionUsdInput, maxPositionPctInput);
                updatePositionPreview();
            },
            onBeforeSave: () => {
                clearPairIfFilled(maxPositionUsdInput, maxPositionPctInput);
                updatePositionPreview();
            },
            rollback: updatePositionPreview
        });

        bindNumberInput(cooldownAfterLossSecondsInput, "cooldownAfterLossSeconds");
        bindNumberInput(maxConsecutiveLossesInput, "maxConsecutiveLosses");
        bindNumberInput(maxOpenOrdersInput, "maxOpenOrders");

        // =====================================================
        // Sync mode badge on change + слушаем глобальный эвент из General
        // =====================================================
        function syncMode() {
            const mode = getControlMode();
            setModeUi(mode);

            // ✅ если режим стал AI — сбросим in-flight confirm/debounce
            if (mode === "AI") {
                ConfirmGate.clear();
                setSavedUi(nowHHmm());
            }
        }

        if (controlModeSelect) {
            controlModeSelect.addEventListener("change", () => {
                syncMode();
            });
        }

        // ✅ общий сигнал от General (мы его диспатчим там)
        window.addEventListener("strategy:controlModeChanged", (e) => {
            const m = String(e?.detail?.mode || "").toUpperCase();
            if (m) {
                ctx.advancedControlMode = m;
                syncMode();
            }
        });

        // =====================================================
        // First render
        // =====================================================
        syncMode();
        updateDrawdownPreview();
        updatePositionPreview();

        // если актив/баланс меняется в general — превью тоже обновим
        if (accountAssetSelect) {
            accountAssetSelect.addEventListener("change", () => {
                updateDrawdownPreview();
                updatePositionPreview();
            });
        }
    }

    return { init };
})();

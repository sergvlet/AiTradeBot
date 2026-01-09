"use strict";

window.SettingsTabGeneral = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const saveBar = document.getElementById("generalSaveBar");
        if (!saveBar) return; // вкладка может отсутствовать на других страницах

        const api = window.SettingsApi;

        // UI
        const saveState = document.getElementById("generalSaveState");
        const saveMeta = document.getElementById("generalSaveMeta");
        const changedList = document.getElementById("generalChangedList");

        const applyBtn = document.getElementById("generalApplyBtn");
        const autoApplyToggle = document.getElementById("generalAutoApplyToggle");

        // inputs
        const controlModeSelect = document.getElementById("advancedControlModeSelect");

        const accountAssetSelect = document.getElementById("accountAssetSelect");
        const accountAssetHidden = document.getElementById("accountAssetHidden");

        const selectedAssetView = document.getElementById("selectedAssetView");

        const exposureMode = document.getElementById("exposureMode");
        const exposureValue = document.getElementById("exposureValue");
        const exposureValueReadonly = document.getElementById("exposureValueReadonly");

        const exposureValueLabel = document.getElementById("exposureValueLabel");
        const exposureValueHint = document.getElementById("exposureValueHint");
        const exposurePreview = document.getElementById("exposurePreview");

        const maxExposureUsdHidden = document.getElementById("maxExposureUsdHidden");
        const maxExposurePctHidden = document.getElementById("maxExposurePctHidden");
        const exposureInitialMode = document.getElementById("exposureInitialMode");

        const dailyLossInput = document.getElementById("dailyLossLimitPctInput");
        const reinvestCheckbox = document.getElementById("reinvestProfit");

        const autosave = window.SettingsAutoSave.create({
            rootEl: saveBar,
            scope: "general",
            context: ctx,
            endpoints: {
                autosave: "/api/strategy/settings/autosave",
                apply: "/api/strategy/settings/apply"
            },
            elements: {
                saveState,
                saveMeta,
                changedList,
                applyBtn,
                autoApplyToggle
            },
            buildPayload: () => {
                return {
                    chatId: ctx.chatId,
                    type: ctx.type,
                    exchange: ctx.exchange,
                    network: ctx.network,
                    scope: "general",

                    advancedControlMode: controlModeSelect?.value || null,

                    accountAsset: (accountAssetHidden?.value || "").trim() || null,
                    maxExposureUsd: (maxExposureUsdHidden?.value || "").trim() || null,
                    maxExposurePct: (maxExposurePctHidden?.value || "").trim() || null,

                    dailyLossLimitPct: (dailyLossInput?.value || "").trim() || null,
                    reinvestProfit: !!reinvestCheckbox?.checked
                };
            }
        });

        autosave.bindApplyButton();
        autosave.initReadyState();

        // ---- helper: текущий актив
        function currentAsset() {
            return (accountAssetHidden?.value || "USDT").trim() || "USDT";
        }

        // ---- Budget UI
        function setBudgetUi(triggerSave) {
            if (!exposureMode) return;

            const mode = exposureMode.value;

            if (mode === "NONE") {
                exposureValue?.classList.add("d-none");
                exposureValueReadonly?.classList.remove("d-none");

                if (exposureValueLabel) exposureValueLabel.textContent = "Значение";
                if (exposureValueHint) exposureValueHint.textContent = "Будет использован весь доступный баланс.";
                if (exposurePreview) exposurePreview.textContent = "Лимит: весь баланс";

                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";

                if (triggerSave) {
                    autosave.markChanged("budget");
                    autosave.scheduleSave(600);
                }
                return;
            }

            exposureValueReadonly?.classList.add("d-none");
            exposureValue?.classList.remove("d-none");

            if (mode === "USD") {
                if (exposureValueLabel) exposureValueLabel.textContent = `Сумма (${currentAsset()})`;
                if (exposureValueHint) exposureValueHint.textContent = `Максимальная сумма в ${currentAsset()}.`;
                if (exposureValue) exposureValue.max = "";
            }

            if (mode === "PCT") {
                if (exposureValueLabel) exposureValueLabel.textContent = "Процент (%)";
                if (exposureValueHint) exposureValueHint.textContent = "Процент от доступного баланса.";
                if (exposureValue) exposureValue.max = "100";
            }

            syncBudgetHidden(triggerSave);
        }

        function syncBudgetHidden(triggerSave) {
            const mode = exposureMode?.value;
            const raw = (exposureValue?.value || "").trim();

            if (!raw || mode === "NONE") {
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";
                if (exposurePreview) exposurePreview.textContent = "Лимит: весь баланс";

                if (triggerSave) {
                    autosave.markChanged("budget");
                    autosave.scheduleSave(600);
                }
                return;
            }

            const value = Number(raw);
            if (!Number.isFinite(value) || value < 0) return;

            if (mode === "USD") {
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = raw;
                if (maxExposurePctHidden) maxExposurePctHidden.value = "";
                if (exposurePreview) exposurePreview.textContent = `Лимит: ${raw} ${currentAsset()}`;
            }

            if (mode === "PCT") {
                const clamped = Math.min(100, value);
                if (maxExposurePctHidden) maxExposurePctHidden.value = String(clamped);
                if (maxExposureUsdHidden) maxExposureUsdHidden.value = "";
                if (exposureValue) exposureValue.value = String(clamped);
                if (exposurePreview) exposurePreview.textContent = `Лимит: ${clamped}%`;
            }

            if (triggerSave) {
                autosave.markChanged("budget");
                autosave.scheduleSave(600);
            }
        }

        // init mode/value from backend
        (function initBudgetFromBackend() {
            const initial = (exposureInitialMode?.value || "NONE").trim();
            if (exposureMode) exposureMode.value = initial;

            if (initial === "USD" && exposureValue) exposureValue.value = maxExposureUsdHidden?.value || "";
            if (initial === "PCT" && exposureValue) exposureValue.value = maxExposurePctHidden?.value || "";

            setBudgetUi(false);
        })();

        // ---- binds
        if (controlModeSelect) {
            controlModeSelect.addEventListener("change", () => {
                autosave.markChanged("controlMode");
                autosave.scheduleSave(600);
            });
        }

        if (accountAssetSelect && accountAssetHidden) {
            accountAssetSelect.addEventListener("change", async () => {
                accountAssetHidden.value = accountAssetSelect.value;

                if (selectedAssetView) selectedAssetView.textContent = accountAssetSelect.value;

                // оставляем твоё безопасное поведение: POST на старый endpoint asset (чтобы не ломать backend)
                try {
                    await api.postForm(`${ctx.baseUrl}/asset`, {
                        chatId: String(ctx.chatId),
                        exchange: ctx.exchange,
                        network: ctx.network,
                        asset: accountAssetSelect.value
                    });
                } catch (e) {
                    console.error("asset change failed", e);
                }

                autosave.markChanged("accountAsset");
                autosave.scheduleSave(600);
            });
        }

        if (dailyLossInput) {
            dailyLossInput.addEventListener("input", () => {
                autosave.markChanged("dailyLossLimitPct");
                autosave.scheduleSave(600);
            });
        }

        if (reinvestCheckbox) {
            reinvestCheckbox.addEventListener("change", () => {
                autosave.markChanged("reinvestProfit");
                autosave.scheduleSave(600);
            });
        }

        if (exposureMode) exposureMode.addEventListener("change", () => setBudgetUi(true));
        if (exposureValue) exposureValue.addEventListener("input", () => syncBudgetHidden(true));
    }

    return { init };
})();

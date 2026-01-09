"use strict";

window.SettingsTabRisk = (function () {

    function init() {
        const ctx = window.StrategySettingsContext;
        if (!ctx) return;

        const saveBar = document.getElementById("riskSaveBar");
        if (!saveBar) return;

        // UI
        const saveState = document.getElementById("riskSaveState");
        const saveMeta = document.getElementById("riskSaveMeta");
        const changedList = document.getElementById("riskChangedList");

        const applyBtn = document.getElementById("riskApplyBtn");
        const autoApplyToggle = document.getElementById("riskAutoApplyToggle");

        // inputs
        const riskPerTradePctInput = document.getElementById("riskPerTradePctInput");
        const leverageInput = document.getElementById("leverageInput");

        const cooldownIndicator = document.getElementById("cooldownIndicator");

        const autosave = window.SettingsAutoSave.create({
            rootEl: saveBar,
            scope: "risk",
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
                    scope: "risk",

                    riskPerTradePct: (riskPerTradePctInput?.value || "").trim() || null,
                    leverage: (leverageInput?.value || "").trim() || null
                };
            }
        });

        autosave.bindApplyButton();
        autosave.initReadyState();

        if (riskPerTradePctInput) {
            riskPerTradePctInput.addEventListener("input", () => {
                autosave.markChanged("riskPerTradePct");
                autosave.scheduleSave(600);
            });
        }

        if (leverageInput) {
            leverageInput.addEventListener("input", () => {
                autosave.markChanged("leverage");
                autosave.scheduleSave(600);
            });
        }

        // runtime indicator: cooldown (обновляется из strategy-live.js)
        // сохранять не надо — только UI
        window.onStrategyLiveEvent = function (event) {
            if (!cooldownIndicator) return;
            if (!event || event.type !== "signal") return;

            const sig = event.signal;
            if (!sig || sig.name !== "HOLD" || typeof sig.reason !== "string") return;

            const m = sig.reason.match(/^cooldown\s+(\d+)s$/i);
            if (m) {
                cooldownIndicator.value = `${m[1]} сек`;
                cooldownIndicator.classList.remove("text-secondary");
                cooldownIndicator.classList.add("text-warning");
            } else {
                cooldownIndicator.value = "—";
                cooldownIndicator.classList.remove("text-warning");
                cooldownIndicator.classList.add("text-secondary");
            }
        };
    }

    return { init };
})();

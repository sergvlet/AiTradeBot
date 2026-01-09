"use strict";

/**
 * Универсальный контроллер:
 * - автосохранение (debounce)
 * - статус "Сохранено / Ошибка / Сохранено но не применено"
 * - кнопка "Применить в торговлю" + автоприменение
 *
 * ВАЖНО:
 * Ты передаёшь buildPayload() и scope ("general"/"risk"/и т.д.)
 */
window.SettingsAutoSave = (function () {

    function create(options) {
        const api = window.SettingsApi;
        if (!api) throw new Error("SettingsApi не подключён");

        const rootEl = options.rootEl;
        const scope = options.scope;
        const endpoints = options.endpoints;

        const elements = options.elements;

        let pendingLabels = new Set();
        let savingNow = false;
        let debounceTimer = null;

        function nowTime() {
            const d = new Date();
            return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
        }

        function setBadge(kind, text) {
            if (!elements.saveState) return;
            elements.saveState.className = "badge bg-" + kind;
            elements.saveState.textContent = text;
        }

        function setMeta(text) {
            if (!elements.saveMeta) return;
            elements.saveMeta.textContent = text || "";
        }

        function isRunning() {
            if (!rootEl) return false;
            return String(rootEl.getAttribute("data-running")) === "true";
        }

        function renderPending() {
            if (!elements.changedList) return;

            if (pendingLabels.size === 0) {
                elements.changedList.classList.add("d-none");
                elements.changedList.textContent = "";
                return;
            }

            elements.changedList.classList.remove("d-none");
            elements.changedList.textContent = "Изменено: " + Array.from(pendingLabels).join(", ");
        }

        function markChanged(label) {
            if (label) pendingLabels.add(label);
            renderPending();
        }

        function clearPending() {
            pendingLabels.clear();
            renderPending();
        }

        function scheduleSave(delayMs) {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => saveNow(), delayMs);
        }

        async function saveNow() {
            if (savingNow) return;
            savingNow = true;

            setBadge("info", "Сохраняю…");
            setMeta("");

            try {
                const payload = options.buildPayload();

                await api.postJson(endpoints.autosave, payload);

                if (isRunning()) {
                    setBadge("warning", "Сохранено · не применено");
                } else {
                    setBadge("success", "Сохранено");
                }

                setMeta("· " + nowTime());
                clearPending();

                if (isRunning() && elements.autoApplyToggle && elements.autoApplyToggle.checked) {
                    await applyNow();
                }

            } catch (e) {
                console.error("autosave failed", e);
                setBadge("danger", "Ошибка сохранения");
                setMeta("· проверь API/логи");
            } finally {
                savingNow = false;
            }
        }

        async function applyNow() {
            if (!isRunning()) return;

            setBadge("info", "Применяю…");
            setMeta("");

            try {
                await api.postJson(endpoints.apply, {
                    chatId: options.context.chatId,
                    type: options.context.type,
                    exchange: options.context.exchange,
                    network: options.context.network,
                    scope: scope
                });

                setBadge("success", "Применено в торговлю");
                setMeta("· " + nowTime());

            } catch (e) {
                console.error("apply failed", e);
                setBadge("danger", "Ошибка применения");
                setMeta("· проверь логи");
            }
        }

        function bindApplyButton() {
            if (!elements.applyBtn) return;
            elements.applyBtn.addEventListener("click", () => applyNow());
        }

        function initReadyState() {
            setBadge("secondary", "Готово");
            setMeta("");
            clearPending();
        }

        return {
            initReadyState,
            markChanged,
            scheduleSave,
            saveNow,
            applyNow,
            bindApplyButton
        };
    }

    return { create };
})();

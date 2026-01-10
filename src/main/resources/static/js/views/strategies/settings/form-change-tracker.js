"use strict";

/**
 * Универсальный контроллер:
 * - автосохранение (debounce)
 * - статус "Сохранено / Ошибка / Сохранено но не применено"
 * - кнопка "Применить в торговлю" + автоприменение (если настроено)
 *
 * ВАЖНО:
 * - endpoints.apply может отсутствовать (например, для вкладки Trade)
 */
window.SettingsAutoSave = (function () {

    function create(options) {
        const api = window.SettingsApi;
        if (!api) throw new Error("SettingsApi не подключён");

        const rootEl = options.rootEl;
        const scope = options.scope;

        const endpoints = options.endpoints || {};
        if (!endpoints.autosave) throw new Error("SettingsAutoSave: endpoints.autosave обязателен");

        // apply может не существовать — это нормально
        const hasApplyEndpoint = typeof endpoints.apply === "string" && endpoints.apply.trim().length > 0;

        const elements = options.elements || {};

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
            const ms = (typeof delayMs === "number" && delayMs >= 0) ? delayMs : 500;
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => saveNow(), ms);
        }

        async function saveNow() {
            if (savingNow) return;
            savingNow = true;

            setBadge("info", "Сохраняю…");
            setMeta("");

            try {
                const payload = options.buildPayload ? options.buildPayload() : null;
                await api.postJson(endpoints.autosave, payload);

                if (isRunning()) {
                    setBadge("warning", "Сохранено · не применено");
                } else {
                    setBadge("success", "Сохранено");
                }

                setMeta("· " + nowTime());
                clearPending();

                // автоприменение: только если
                // 1) стратегия запущена
                // 2) есть toggle
                // 3) он включён
                // 4) есть endpoints.apply
                if (isRunning()
                    && hasApplyEndpoint
                    && elements.autoApplyToggle
                    && elements.autoApplyToggle.checked) {
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
            // ✅ если apply не поддерживается — просто выходим
            if (!hasApplyEndpoint) return;
            if (!isRunning()) return;

            setBadge("info", "Применяю…");
            setMeta("");

            try {
                await api.postJson(endpoints.apply, {
                    chatId: options.context?.chatId,
                    type: options.context?.type,
                    exchange: options.context?.exchange,
                    network: options.context?.network,
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

            // если apply endpoint отсутствует — просто отключаем кнопку, чтобы UX был ясный
            if (!hasApplyEndpoint) {
                elements.applyBtn.disabled = true;
                elements.applyBtn.title = "Применение не настроено (нет endpoints.apply)";
                return;
            }

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

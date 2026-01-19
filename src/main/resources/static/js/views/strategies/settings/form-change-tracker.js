"use strict";

/**
 * Универсальный контроллер:
 * - автосохранение (debounce)
 * - статус "Сохранено / Ошибка / Сохранено но не применено"
 * - кнопка "Применить в торговлю" + автоприменение (если настроено)
 *
 * ВАЖНО:
 * - endpoints.apply может отсутствовать (например, для вкладки Trade)
 *
 * ✅ FIX (критично для твоих вкладок):
 * 1) saveNow() теперь возвращает JSON-ответ (tabs ждут snapshot / tuningResult и т.д.)
 * 2) защита от гонок: если save в процессе — ставим очередь и выполняем после завершения
 * 3) applyNow() тоже возвращает JSON-ответ
 * 4) onAfterSave/onAfterApply хуки (если надо синхронизировать вкладки)
 */
window.SettingsAutoSave = (function () {

    function create(options) {
        const api = window.SettingsApi;
        if (!api) throw new Error("SettingsApi не подключён");

        const rootEl = options.rootEl;
        const scope  = options.scope;

        const endpoints = options.endpoints || {};
        if (!endpoints.autosave) throw new Error("SettingsAutoSave: endpoints.autosave обязателен");

        // apply может не существовать — это нормально
        const hasApplyEndpoint = typeof endpoints.apply === "string" && endpoints.apply.trim().length > 0;

        const elements = options.elements || {};

        let pendingLabels = new Set();

        let savingNow = false;
        let saveQueued = false;     // ✅ если во время save пришёл новый schedule/save
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
            if (debounceTimer) clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => saveNow(), ms);
        }

        async function saveNow() {
            // ✅ если уже сохраняем — ставим очередь и выходим
            if (savingNow) {
                saveQueued = true;
                return null;
            }

            savingNow = true;
            saveQueued = false;

            setBadge("info", "Сохраняю…");
            setMeta("");

            let resp = null;
            const payload = options.buildPayload ? options.buildPayload() : null;

            try {
                // ✅ важно: вернуть ответ наружу
                resp = await api.postJson(endpoints.autosave, payload);

                if (isRunning()) {
                    setBadge("warning", "Сохранено · не применено");
                } else {
                    setBadge("success", "Сохранено");
                }

                setMeta("· " + nowTime());
                clearPending();

                // hook после сохранения (для синхронизации вкладок)
                try {
                    if (typeof options.onAfterSave === "function") {
                        await options.onAfterSave(resp, payload);
                    }
                } catch (e) {
                    console.warn("SettingsAutoSave.onAfterSave failed", e);
                }

                // автоприменение: только если
                // 1) стратегия запущена
                // 2) есть toggle
                // 3) он включён
                // 4) есть endpoints.apply
                if (isRunning()
                    && hasApplyEndpoint
                    && elements.autoApplyToggle
                    && elements.autoApplyToggle.checked) {
                    await applyNow(); // applyNow сам корректно отработает
                }

                return resp;

            } catch (e) {
                console.error("autosave failed", e);
                setBadge("danger", "Ошибка сохранения");
                setMeta("· проверь API/логи");

                // ⚠️ pending не чистим — чтобы пользователь видел, что не сохранилось
                return null;

            } finally {
                savingNow = false;

                // ✅ если во время save прилетели новые изменения — сохраним ещё раз
                if (saveQueued) {
                    saveQueued = false;
                    // небольшой микродилей, чтобы UI не дёргался
                    setTimeout(() => {
                        // не делаем scheduleSave, а сразу saveNow, чтобы не терять изменения
                        saveNow().catch(() => {});
                    }, 120);
                }
            }
        }

        async function applyNow(extra) {
            // ✅ если apply не поддерживается — просто выходим
            if (!hasApplyEndpoint) return null;
            if (!isRunning()) return null;

            setBadge("info", "Применяю…");
            setMeta("");

            try {
                const base = {
                    chatId: options.context?.chatId,
                    type: options.context?.type,
                    exchange: options.context?.exchange,
                    network: options.context?.network,
                    scope: scope
                };

                const body = Object.assign(base, (extra && typeof extra === "object") ? extra : {});

                const resp = await api.postJson(endpoints.apply, body);

                setBadge("success", "Применено в торговлю");
                setMeta("· " + nowTime());

                // hook после применения
                try {
                    if (typeof options.onAfterApply === "function") {
                        await options.onAfterApply(resp, body);
                    }
                } catch (e) {
                    console.warn("SettingsAutoSave.onAfterApply failed", e);
                }

                return resp;

            } catch (e) {
                console.error("apply failed", e);
                setBadge("danger", "Ошибка применения");
                setMeta("· проверь логи");
                return null;
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

            elements.applyBtn.addEventListener("click", () => {
                applyNow().catch(() => {});
            });
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

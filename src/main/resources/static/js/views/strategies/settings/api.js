"use strict";

/**
 * Мини-обёртка над fetch:
 * - JSON POST
 * - form-urlencoded POST
 *
 * ✅ FIX (чтобы сохранение работало стабильно):
 * 1) Добавлен GET JSON (нужен вкладкам: balance/advanced/symbols если захочешь через SettingsApi)
 * 2) Добавлен авто-парсинг ошибок (пытаемся прочитать JSON/text и показать полезное в консоли)
 * 3) Добавлен таймаут (по умолчанию 20с) + AbortController
 * 4) postForm теперь отправляет корректную строку (URLSearchParams(...).toString())
 * 5) Всегда ставим Accept: application/json
 */
window.SettingsApi = (function () {

    const DEFAULT_TIMEOUT_MS = 20000;

    function buildTimeoutSignal(timeoutMs) {
        const controller = new AbortController();
        const t = setTimeout(() => controller.abort("timeout"), Math.max(1, timeoutMs | 0));
        return { controller, signal: controller.signal, cleanup: () => clearTimeout(t) };
    }

    async function readErrorPayload(res) {
        // пытаемся разобрать JSON, если нет — текст
        const ct = (res.headers.get("content-type") || "").toLowerCase();
        try {
            if (ct.includes("application/json")) {
                return await res.json();
            }
        } catch (_) { /* ignore */ }

        try {
            const text = await res.text();
            return text ? { message: text } : {};
        } catch (_) {
            return {};
        }
    }

    async function ensureJsonOrEmpty(res) {
        // иногда у тебя может быть пустой ответ
        const ct = (res.headers.get("content-type") || "").toLowerCase();
        if (!ct.includes("application/json")) {
            // если не json — попробуем текст, но не падаем
            try {
                const t = await res.text();
                return t ? { message: t } : {};
            } catch (_) {
                return {};
            }
        }
        try {
            return await res.json();
        } catch (_) {
            return {};
        }
    }

    async function getJson(url, opts) {
        const timeoutMs = (opts && Number.isFinite(opts.timeoutMs)) ? opts.timeoutMs : DEFAULT_TIMEOUT_MS;
        const { signal, cleanup } = buildTimeoutSignal(timeoutMs);

        try {
            const res = await fetch(url, {
                method: "GET",
                headers: {
                    "Accept": "application/json",
                    "X-Requested-With": "XMLHttpRequest"
                },
                signal
            });

            if (!res.ok) {
                const payload = await readErrorPayload(res);
                console.error("SettingsApi GET failed", { url, status: res.status, payload });
                const msg = payload?.message || payload?.error || ("HTTP " + res.status);
                throw new Error(msg);
            }

            return await ensureJsonOrEmpty(res);
        } finally {
            cleanup();
        }
    }

    async function postJson(url, payload, opts) {
        const timeoutMs = (opts && Number.isFinite(opts.timeoutMs)) ? opts.timeoutMs : DEFAULT_TIMEOUT_MS;
        const { signal, cleanup } = buildTimeoutSignal(timeoutMs);

        try {
            const res = await fetch(url, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                    "X-Requested-With": "XMLHttpRequest"
                },
                body: JSON.stringify(payload || {}),
                signal
            });

            if (!res.ok) {
                const err = await readErrorPayload(res);
                console.error("SettingsApi POST JSON failed", { url, status: res.status, err, payload });
                const msg = err?.message || err?.error || ("HTTP " + res.status);
                throw new Error(msg);
            }

            return await ensureJsonOrEmpty(res);
        } finally {
            cleanup();
        }
    }

    async function postForm(url, form, opts) {
        const timeoutMs = (opts && Number.isFinite(opts.timeoutMs)) ? opts.timeoutMs : DEFAULT_TIMEOUT_MS;
        const { signal, cleanup } = buildTimeoutSignal(timeoutMs);

        // важно: body должен быть строкой или URLSearchParams
        const params = new URLSearchParams(form || {});

        try {
            const res = await fetch(url, {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "Accept": "application/json",
                    "X-Requested-With": "XMLHttpRequest"
                },
                body: params.toString(),
                signal
            });

            if (!res.ok) {
                const err = await readErrorPayload(res);
                console.error("SettingsApi POST FORM failed", { url, status: res.status, err, form });
                const msg = err?.message || err?.error || ("HTTP " + res.status);
                throw new Error(msg);
            }

            return await ensureJsonOrEmpty(res);
        } finally {
            cleanup();
        }
    }

    return { getJson, postJson, postForm };
})();

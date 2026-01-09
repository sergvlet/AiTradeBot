"use strict";

/**
 * Мини-обёртка над fetch:
 * - JSON POST
 * - form-urlencoded POST
 */
window.SettingsApi = (function () {

    async function postJson(url, payload) {
        const res = await fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: JSON.stringify(payload || {})
        });

        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }

        // иногда у тебя может быть пустой ответ
        try {
            return await res.json();
        } catch (_) {
            return {};
        }
    }

    async function postForm(url, form) {
        const res = await fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: new URLSearchParams(form || {})
        });

        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }

        try {
            return await res.json();
        } catch (_) {
            return {};
        }
    }

    return { postJson, postForm };
})();

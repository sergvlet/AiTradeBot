"use strict";

/**
 * SettingsApi
 * - CSRF friendly (meta + hidden input)
 * - same-origin cookies
 * - safe error parsing (JSON/HTML/text)
 * - detects silent HTML login/403 pages (so you see real problem)
 */
window.SettingsApi = (function () {


    // =====================================================
    // 🔄 UI-state bus (общий стор для вкладок)
    // =====================================================
    function ensureStrategyStore() {
        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.subscribe === "function") {
            return window.StrategySettingsStore;
        }

        const listeners = new Set();

        const store = {
            _state: null,

            getState() { return this._state; },

            setState(state) {
                this._state = state;

                // 1) подписчики (вкладки)
                listeners.forEach(fn => {
                    try { fn(state); } catch (e) {}
                });

                // 2) событие на window (фолбэк)
                try {
                    window.dispatchEvent(new CustomEvent("strategy:state", { detail: state }));
                } catch (e) {}
            },

            subscribe(fn) {
                if (typeof fn !== "function") return () => {};
                listeners.add(fn);

                // сразу отдадим текущее состояние
                if (this._state) {
                    try { fn(this._state); } catch (e) {}
                }

                return () => listeners.delete(fn);
            }
        };

        window.StrategySettingsStore = store;
        return store;
    }

    function looksLikeUiState(obj) {
        if (!obj || typeof obj !== "object") return false;
        return ("chatId" in obj) && ("type" in obj) && ("exchange" in obj) && ("network" in obj);
    }

    function publishUiStateIfAny(obj) {
        if (!looksLikeUiState(obj)) return;
        try { ensureStrategyStore().setState(obj); } catch (e) {}
    }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function readCsrf() {
        const tokenMeta  = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');

        const token  = tokenMeta?.getAttribute("content") || "";
        const header = headerMeta?.getAttribute("content") || "";

        if (token && header) return { token, header, paramName: "_csrf" };

        // fallback: hidden input
        const input = document.querySelector('input[type="hidden"][name="_csrf"]');
        if (input?.value) return { token: input.value, header: "X-CSRF-TOKEN", paramName: "_csrf" };

        const any = document.querySelector('input[type="hidden"][name*="csrf" i]');
        if (any?.value) return { token: any.value, header: "X-CSRF-TOKEN", paramName: any.name || "_csrf" };

        return null;
    }

    function withCsrfHeaders(headers) {
        const csrf = readCsrf();
        if (csrf?.token && csrf?.header) headers[csrf.header] = csrf.token;
        return headers;
    }

    function withCsrfParam(params) {
        const csrf = readCsrf();
        if (csrf?.token && csrf?.paramName && !params.has(csrf.paramName)) {
            params.append(csrf.paramName, csrf.token);
        }
        return params;
    }

    function looksLikeLoginHtml(text) {
        if (!text) return false;
        const t = String(text).toLowerCase();
        // типичные признаки Spring Security login/403 страниц
        if (t.includes("login") && (t.includes("password") || t.includes("username"))) return true;
        if (t.includes("sign in") && t.includes("password")) return true;
        if (t.includes("csrf") && t.includes("invalid")) return true;
        if (t.includes("whitelabel error page")) return true;
        if (t.includes("access denied")) return true;
        return t.includes("403") && t.includes("forbidden");

    }

    async function readBodySafe(resp) {
        // 204 / empty body
        if (resp.status === 204) return { kind: "empty", text: "" };

        const ct = (resp.headers.get("content-type") || "").toLowerCase();
        const text = await resp.text().catch(() => "");
        if (!text) return { kind: "empty", text: "" };

        if (ct.includes("application/json")) {
            try { return { kind: "json", json: JSON.parse(text), text }; } catch (_) {}
        } else {
            // иногда сервер отдаёт JSON как text/plain
            const trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try { return { kind: "json", json: JSON.parse(trimmed), text }; } catch (_) {}
            }
        }
        return { kind: "text", text };
    }

    function prettifyError(status, body) {
        if (!body) return `HTTP ${status}`;

        if (body.kind === "json" && body.json && typeof body.json === "object") {
            const obj = body.json;
            const msg = obj.message || obj.error || obj.details || `HTTP ${status}`;
            const path = obj.path ? `\nПуть: ${obj.path}` : "";
            const code = obj.code ? `\nКод: ${obj.code}` : "";
            return `${msg}${path}${code}`;
        }

        if (body.kind === "text") {
            const t = String(body.text || "");
            const cut = t.replace(/\s+/g, " ").trim().slice(0, 260);
            return `HTTP ${status}: ${cut}`;
        }

        return `HTTP ${status}`;
    }

    async function getJson(url) {
        const headers = {
            "Accept": "application/json",
            "X-Requested-With": "fetch"
        };

        const resp = await fetch(url, {
            method: "GET",
            credentials: "same-origin",
            headers
        });

        if (!resp.ok) {
            const body = await readBodySafe(resp);
            throw new Error(prettifyError(resp.status, body));
        }

        const body = await readBodySafe(resp);
        if (body.kind === "json") return body.json;
        // пусто/текст -> вернём пустой объект
        return {};
    }

    async function postForm(url, data) {
        const params = new URLSearchParams();

        Object.entries(data || {}).forEach(([k, v]) => {
            if (v === undefined || v === null) return;
            params.append(k, String(v));
        });

        withCsrfParam(params);

        const headers = withCsrfHeaders({
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept": "*/*",                 // ✅ важно: сервер может вернуть HTML redirect
            "X-Requested-With": "fetch"
        });

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body: params.toString(),
            redirect: "follow"
        });

        const body = await readBodySafe(resp);

        // ❌ сервер может вернуть 200 и HTML login page — это НЕ успех
        if (resp.ok && body.kind === "text" && looksLikeLoginHtml(body.text)) {
            throw new Error("Сессия/доступ потеряны (похоже на страницу логина/403). Проверь авторизацию и CSRF.");
        }

        if (!resp.ok) {
            throw new Error(prettifyError(resp.status, body));
        }

        // ✅ если вернулся JSON — отдадим его
        if (body.kind === "json") return body.json;

        // ✅ если HTML/пусто — считаем успехом
        return { ok: true };
    }

    async function postJson(url, payload) {
        const headers = withCsrfHeaders({
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Requested-With": "fetch"
        });

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body: JSON.stringify(payload || {}),
            redirect: "follow"
        });

        const body = await readBodySafe(resp);

        if (resp.ok && body.kind === "text" && looksLikeLoginHtml(body.text)) {
            throw new Error("Сессия/доступ потеряны (похоже на страницу логина/403). Проверь авторизацию и CSRF.");
        }

        if (!resp.ok) {
            throw new Error(prettifyError(resp.status, body));
        }

        if (body.kind === "json") return body.json;
        return {};
    }

    return { getJson, postForm, postJson };
})();

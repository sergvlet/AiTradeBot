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
    // 🚌 ensure global store exists (чтобы не зависеть от порядка скриптов)
    // =====================================================
    function ensureStore() {
        if (!window.StrategySettingsBus) {
            window.StrategySettingsBus = {
                emit(name, detail) {
                    try {
                        window.dispatchEvent(new CustomEvent(name, { detail }));
                    } catch (e) {}
                },
                on(name, handler) {
                    window.addEventListener(name, handler);
                    return () => window.removeEventListener(name, handler);
                }
            };
        }

        if (!window.StrategySettingsStore) {
            let state = null;
            const listeners = new Set();
            window.StrategySettingsStore = {
                set(next) {
                    state = next;
                    try { window.StrategySettingsBus.emit("strategy:state", state); } catch (e) {}
                    listeners.forEach((fn) => { try { fn(state); } catch (e) {} });
                },
                get() { return state; },
                subscribe(fn) {
                    listeners.add(fn);
                    if (state !== null) { try { fn(state); } catch (e) {} }
                    return () => listeners.delete(fn);
                }
            };
        }
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

        // ✅ если вернулся JSON — отдадим его (и попробуем применить как UI-state)
        if (body.kind === "json") {
            const json = body.json;
            tryPublishState(json);
            // если это не state — всё равно попробуем подтянуть state отдельным запросом
            await refreshUiStateIfConfig(url, data);
            return json;
        }

        // ✅ если HTML/пусто — считаем успехом
        await refreshUiStateIfConfig(url, data);
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

    // =====================================================
    // ✅ UI State refresh helpers
    // =====================================================

    function tryPublishState(maybeState) {
        ensureStore();
        if (!maybeState || typeof maybeState !== "object") return false;

        // минимальный признак, что это именно наш UI-state
        if (!maybeState.chatId || !maybeState.type) return false;

        // store > event
        if (window.StrategySettingsStore && typeof window.StrategySettingsStore.set === "function") {
            window.StrategySettingsStore.set(maybeState);
            return true;
        }
        if (window.StrategySettingsBus && typeof window.StrategySettingsBus.emit === "function") {
            window.StrategySettingsBus.emit("strategy:state", maybeState);
            return true;
        }
        try {
            window.dispatchEvent(new CustomEvent("strategy:state", { detail: maybeState }));
            return true;
        } catch (e) {
            return false;
        }
    }

    function parseConfigCtx(url, data) {
        try {
            const u = new URL(url, window.location.origin);
            const m = u.pathname.match(/\/strategies\/([^\/]+)\/config/i);
            if (!m) return null;

            const type = decodeURIComponent(m[1]);
            const chatId = (u.searchParams.get("chatId") || data?.chatId || window.StrategySettingsContext?.chatId || "").toString();
            if (!chatId) return null;

            const exchange = (data?.exchange || u.searchParams.get("exchange") || window.StrategySettingsContext?.exchange || "").toString();
            const network  = (data?.network  || u.searchParams.get("network")  || window.StrategySettingsContext?.network  || "").toString();

            return { type, chatId, exchange, network };
        } catch (e) {
            return null;
        }
    }

    function buildStateUrl(ctx) {
        if (!ctx) return null;
        const params = new URLSearchParams();
        params.set("chatId", String(ctx.chatId));
        if (ctx.exchange) params.set("exchange", String(ctx.exchange));
        if (ctx.network)  params.set("network", String(ctx.network));
        return `/strategies/${encodeURIComponent(ctx.type)}/config/state?${params.toString()}`;
    }

    async function refreshUiStateIfConfig(url, data) {
        ensureStore();
        const ctx = parseConfigCtx(url, data);
        if (!ctx) return;

        const stateUrl = buildStateUrl(ctx);
        if (!stateUrl) return;

        try {
            const state = await getJson(stateUrl);
            tryPublishState(state);
        } catch (e) {
            // не валим сохранение из-за авто-refresh
            if (console && console.debug) console.debug("state refresh failed", e);
        }
    }

    return { getJson, postForm, postJson };
})();

"use strict";

/**
 * SettingsApi
 * - CSRF friendly (meta + hidden input)
 * - same-origin cookies
 * - safe error parsing (JSON/HTML/text)
 */
window.SettingsApi = (function () {

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function readCsrf() {
        const tokenMeta = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');

        const token = tokenMeta?.getAttribute("content") || "";
        const header = headerMeta?.getAttribute("content") || "";

        if (token && header) return { token, header, paramName: "_csrf" };

        // fallback: hidden input
        const input = document.querySelector('input[type="hidden"][name="_csrf"]');
        if (input?.value) return { token: input.value, header: "X-CSRF-TOKEN", paramName: "_csrf" };

        const any = document.querySelector('input[type="hidden"][name*="csrf" i]');
        if (any?.value) return { token: any.value, header: "X-CSRF-TOKEN", paramName: any.name || "_csrf" };

        return null;
    }

    async function readBodySafe(resp) {
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

        // если это JSON с полями message/error/path/code — красиво соберём
        if (body.kind === "json" && body.json && typeof body.json === "object") {
            const obj = body.json;
            const msg = obj.message || obj.error || obj.details || `HTTP ${status}`;
            const path = obj.path ? `\nПуть: ${obj.path}` : "";
            const code = obj.code ? `\nКод: ${obj.code}` : "";
            return `${msg}${path}${code}`;
        }

        // если это HTML — режем
        if (body.kind === "text") {
            const t = String(body.text || "");
            const cut = t.replace(/\s+/g, " ").trim().slice(0, 240);
            return `HTTP ${status}: ${cut}`;
        }

        return `HTTP ${status}`;
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

    async function getJson(url) {
        const headers = withCsrfHeaders({
            "Accept": "application/json",
            "X-Requested-With": "fetch"
        });

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
        // fallback: пусто / текст
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
            "Accept": "application/json",
            "X-Requested-With": "fetch"
        });

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body: params.toString()
        });

        if (!resp.ok) {
            const body = await readBodySafe(resp);
            throw new Error(prettifyError(resp.status, body));
        }

        const body = await readBodySafe(resp);
        if (body.kind === "json") return body.json;
        return true;
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
            body: JSON.stringify(payload || {})
        });

        if (!resp.ok) {
            const body = await readBodySafe(resp);
            throw new Error(prettifyError(resp.status, body));
        }

        const body = await readBodySafe(resp);
        if (body.kind === "json") return body.json;
        return {};
    }

    return { getJson, postForm, postJson };
})();

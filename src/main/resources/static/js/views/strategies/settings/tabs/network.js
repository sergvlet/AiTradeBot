"use strict";

window.SettingsTabNetwork = (function () {

    function $(id) { return document.getElementById(id); }

    // =====================================================
    // CSRF (не мешает если Spring Security выключен)
    // =====================================================
    function getCsrf() {
        const tokenMeta  = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (tokenMeta && tokenMeta.content) {
            return {
                token: tokenMeta.content,
                header: (headerMeta && headerMeta.content) ? headerMeta.content : "X-CSRF-TOKEN",
                paramName: "_csrf"
            };
        }

        const input = document.querySelector('input[type="hidden"][name="_csrf"]');
        if (input && input.value) {
            return { token: input.value, header: "X-CSRF-TOKEN", paramName: "_csrf" };
        }

        const anyCsrf = document.querySelector('input[type="hidden"][name*="csrf" i]');
        if (anyCsrf && anyCsrf.value) {
            return { token: anyCsrf.value, header: "X-CSRF-TOKEN", paramName: anyCsrf.name };
        }

        return null;
    }

    function isBlank(s) {
        return s === null || s === undefined || String(s).trim() === "";
    }

    function normalizeUpper(s) {
        return isBlank(s) ? "" : String(s).trim().toUpperCase();
    }

    function isDiagnosticsSupported(exchange) {
        const ex = normalizeUpper(exchange);
        return ex === "BINANCE" || ex === "BYBIT";
    }

    // =====================================================
    // UI helpers
    // =====================================================
    function setCell(id, ok) {
        const el = $(id);
        if (!el) return;

        if (ok === true) {
            el.textContent = "✔";
            el.className = "text-success fw-bold";
        } else if (ok === false) {
            el.textContent = "✖";
            el.className = "text-danger fw-bold";
        } else {
            el.textContent = "—";
            el.className = "";
        }
    }

    function resetDiagnosticsUI(opts) {
        const statusEl     = $("diag-status");
        const notSupported = $("diag-not-supported");
        const table        = $("diag-table");
        const msg          = $("diag-message");

        if (table) table.style.display = "none";
        if (msg) {
            msg.style.display = "none";
            msg.textContent = "";
            msg.className = "";
        }

        // сбрасываем ячейки, чтобы не было “старого OK”
        setCell("d-apiKeyValid", null);
        setCell("d-secretValid", null);
        setCell("d-signatureValid", null);
        setCell("d-accountReadable", null);
        setCell("d-tradingAllowed", null);
        setCell("d-ipAllowed", null);
        setCell("d-networkOk", null);

        if (notSupported) notSupported.style.display = "none";

        if (statusEl) {
            statusEl.textContent = (opts && opts.text) ? opts.text : "";
            statusEl.className = (opts && opts.className) ? opts.className : "small text-secondary";
        }
    }

    function ensureAlertHost() {
        // Если у тебя уже есть контейнер — используй его (лучше)
        let host = $("network-alert");
        if (host) return host;

        // иначе создадим рядом с autosave/формой, чтобы не “сбоку”
        const networkForm = $("networkForm");
        if (networkForm && networkForm.parentElement) {
            host = document.createElement("div");
            host.id = "network-alert";
            host.className = "mt-2";
            networkForm.parentElement.insertBefore(host, networkForm);
            return host;
        }

        // fallback: наверх страницы
        host = document.createElement("div");
        host.id = "network-alert";
        host.className = "container-fluid mt-2";
        document.body.insertBefore(host, document.body.firstChild);
        return host;
    }

    function showAlert(kind, title, details) {
        const host = ensureAlertHost();
        if (!host) return;

        const bsType =
            kind === "ok" ? "success" :
                kind === "warn" ? "warning" :
                    kind === "info" ? "info" : "danger";

        const safeTitle = isBlank(title) ? "" : String(title);
        const safeDetails = isBlank(details) ? "" : String(details);

        host.innerHTML = `
          <div class="alert alert-${bsType} alert-dismissible fade show" role="alert" style="max-width: 980px;">
            ${safeTitle ? `<div class="fw-bold mb-1">${escapeHtml(safeTitle)}</div>` : ""}
            ${safeDetails ? `<div class="small" style="white-space: pre-wrap;">${escapeHtml(safeDetails)}</div>` : ""}
            <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
          </div>
        `;
    }

    function escapeHtml(s) {
        return String(s)
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function prettifyHttpError(err) {
        const raw = (err && err.message) ? String(err.message) : "Unknown error";
        // Попробуем вытащить JSON из "HTTP 500: {...}"
        const idx = raw.indexOf("{");
        if (idx >= 0) {
            const maybeJson = raw.slice(idx);
            try {
                const obj = JSON.parse(maybeJson);
                const msg = obj.message || obj.error || raw;
                const path = obj.path ? `\nПуть: ${obj.path}` : "";
                const code = obj.code ? `\nКод: ${obj.code}` : "";
                return `${msg}${path}${code}`;
            } catch (_) {
                // не JSON
            }
        }
        return raw;
    }

    // =====================================================
    // Requests
    // =====================================================
    async function postUrlEncoded(url, data) {
        const csrf = getCsrf();
        const body = new URLSearchParams();

        Object.entries(data || {}).forEach(([k, v]) => {
            if (v !== undefined && v !== null) body.append(k, String(v));
        });

        // CSRF как параметр (если есть)
        if (csrf && csrf.paramName && csrf.token && !body.has(csrf.paramName)) {
            body.append(csrf.paramName, csrf.token);
        }

        const headers = {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With": "fetch"
        };

        // CSRF как header (если есть)
        if (csrf && csrf.header && csrf.token) {
            headers[csrf.header] = csrf.token;
        }

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body
        });

        if (!resp.ok) {
            const text = await resp.text().catch(() => "");
            throw new Error("HTTP " + resp.status + (text ? (": " + text.slice(0, 400)) : ""));
        }

        return true;
    }

    async function postJson(url) {
        const csrf = getCsrf();
        const headers = { "X-Requested-With": "fetch" };
        if (csrf && csrf.header && csrf.token) headers[csrf.header] = csrf.token;

        const resp = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers
        });

        if (!resp.ok) {
            const text = await resp.text().catch(() => "");
            throw new Error("HTTP " + resp.status + (text ? (": " + text.slice(0, 400)) : ""));
        }

        return resp.json().catch(() => ({}));
    }

    // =====================================================
    // URL helpers
    // =====================================================
    function getCtx() {
        return window.StrategySettingsContext || null;
    }

    function getBaseUrl() {
        const ctx = getCtx();
        if (ctx && ctx.baseUrl) return String(ctx.baseUrl);
        // fallback: текущий путь без query
        return window.location.pathname;
    }

    function getChatIdFallback() {
        const ctx = getCtx();
        if (ctx && ctx.chatId) return String(ctx.chatId);
        // иногда chatId лежит в data-chat-id на main
        const main = document.querySelector("main[data-chat-id]");
        if (main && main.dataset && main.dataset.chatId) return String(main.dataset.chatId);
        return "";
    }

    function getTypeFallback() {
        const ctx = getCtx();
        if (ctx && ctx.type) return String(ctx.type);
        // иногда type лежит в data-type на main
        const main = document.querySelector("main[data-type]");
        if (main && main.dataset && main.dataset.type) return String(main.dataset.type);
        return "";
    }

    function buildSettingsUrl(chatId, exchange, network, tab) {
        const base = getBaseUrl();
        const q = new URLSearchParams();
        if (!isBlank(chatId)) q.set("chatId", String(chatId));
        if (!isBlank(exchange)) q.set("exchange", String(exchange));
        if (!isBlank(network)) q.set("network", String(network));
        if (!isBlank(tab)) q.set("tab", String(tab));
        return base + "?" + q.toString();
    }

    function replaceUrlWithoutReload(exchange, network) {
        const chatId = getChatIdFallback();
        const activeTab = localStorage.getItem("strategy_settings_active_tab") || "network";
        const url = buildSettingsUrl(chatId, exchange, network, activeTab);
        try { history.replaceState(null, "", url); } catch (_) {}
    }

    function buildDiagnoseUrl(chatId, exchange, network) {
        // предпочитаем новый эндпоинт: /strategies/{type}/config/diagnose
        const type = normalizeUpper(getTypeFallback());
        if (type) {
            return `/strategies/${encodeURIComponent(type)}/config/diagnose` +
                `?chatId=${encodeURIComponent(String(chatId))}` +
                `&exchange=${encodeURIComponent(String(exchange))}` +
                `&network=${encodeURIComponent(String(network))}`;
        }
        // fallback на legacy
        return `/strategies/network/diagnose` +
            `?chatId=${encodeURIComponent(String(chatId))}` +
            `&exchange=${encodeURIComponent(String(exchange))}` +
            `&network=${encodeURIComponent(String(network))}`;
    }

    // =====================================================
    // SAFE INIT
    // =====================================================
    let started = false;

    function init() {
        if (started) return;
        started = true;

        const networkForm    = $("networkForm");
        const exchangeSelect = $("exchangeSelect");
        const networkSelect  = $("networkSelect");
        if (!networkForm || !exchangeSelect || !networkSelect) return;

        const autosaveEl   = $("network-autosave-status");
        const keysExchange = $("keysExchange"); // hidden в форме ключей
        const keysNetwork  = $("keysNetwork");  // hidden в форме ключей

        // diagnostics
        const btnDiagnose  = $("btn-diagnose");
        const statusEl     = $("diag-status");
        const notSupported = $("diag-not-supported");
        const table        = $("diag-table");
        const msg          = $("diag-message");

        function setAutosave(text, kind) {
            if (!autosaveEl) return;
            autosaveEl.textContent = text || "";
            autosaveEl.className = "small";
            if (kind === "info") autosaveEl.classList.add("text-info");
            else if (kind === "ok") autosaveEl.classList.add("text-success");
            else if (kind === "err") autosaveEl.classList.add("text-danger");
            else autosaveEl.classList.add("text-secondary");
        }

        function syncKeysHidden() {
            if (keysExchange) keysExchange.value = exchangeSelect.value || "";
            if (keysNetwork)  keysNetwork.value  = networkSelect.value  || "";
        }

        // ---------------------------------
        // on load: sync initial + clear stale UI
        // ---------------------------------
        const initialExchange = exchangeSelect.value || "";
        const initialNetwork  = networkSelect.value  || "";

        // при старте: если биржа не поддерживает диагностику — убираем таблицу/OK
        if (!isDiagnosticsSupported(initialExchange)) {
            resetDiagnosticsUI({
                text: "Диагностика недоступна для выбранной биржи.",
                className: "text-warning small"
            });
            if (notSupported) notSupported.style.display = "block";
        }

        // ---------------------------------
        // AUTOSAVE network (и рефреш страницы)
        // ---------------------------------
        let inFlight = false;
        let timer = null;
        let pendingReload = false;

        let lastExchange = initialExchange;
        let lastNetwork  = initialNetwork;

        function prepareForChange(ex, net) {
            // сразу убираем старую диагностику, чтобы не было “OK” от предыдущей биржи
            if (!isDiagnosticsSupported(ex)) {
                resetDiagnosticsUI({
                    text: "Диагностика недоступна для выбранной биржи.",
                    className: "text-warning small"
                });
                if (notSupported) notSupported.style.display = "block";
            } else {
                resetDiagnosticsUI({
                    text: "Диагностика сброшена. Нажми «Проверить», чтобы выполнить снова.",
                    className: "text-secondary small"
                });
                if (notSupported) notSupported.style.display = "none";
            }

            // на всякий случай подправим dataset
            if (btnDiagnose) {
                btnDiagnose.dataset.exchange = ex || "";
                btnDiagnose.dataset.network = net || "";
            }
        }

        async function autosaveNow() {
            const ex  = exchangeSelect.value || "";
            const net = networkSelect.value  || "";

            // ничего не меняли
            if (ex === lastExchange && net === lastNetwork) {
                syncKeysHidden();
                replaceUrlWithoutReload(ex, net);
                return;
            }

            if (inFlight) { scheduleAutosave(); return; }

            inFlight = true;
            exchangeSelect.disabled = true;
            networkSelect.disabled  = true;

            setAutosave("Сохраняю…", "info");

            try {
                syncKeysHidden();
                prepareForChange(ex, net);

                await postUrlEncoded(networkForm.action, {
                    saveScope: "network",
                    tab: "network",
                    exchange: ex,
                    network: net
                });

                lastExchange = ex;
                lastNetwork  = net;

                replaceUrlWithoutReload(ex, net);

                setAutosave("Сохранено", "ok");
                showAlert("ok", "Сеть/биржа сохранены", `Биржа: ${ex}\nСеть: ${net}`);

                // ✅ важно: после смены биржи/сети делаем рефреш страницы,
                // чтобы:
                // - не показывалась старая диагностика,
                // - подтянулись правильные данные с сервера,
                // - корректно отрисовались supports/keys.
                pendingReload = true;
                const chatId = getChatIdFallback();
                const url = buildSettingsUrl(chatId, ex, net, "network");
                setTimeout(() => {
                    // защита от лишнего reload: только если реально меняли
                    if (pendingReload) window.location.assign(url);
                }, 250);

            } catch (e) {
                const pretty = prettifyHttpError(e);
                setAutosave("Ошибка сохранения", "err");
                showAlert("err", "Ошибка сохранения сети/биржи", pretty);
            } finally {
                inFlight = false;
                exchangeSelect.disabled = false;
                networkSelect.disabled  = false;
                setTimeout(() => setAutosave("", "idle"), 1200);
            }
        }

        function scheduleAutosave() {
            clearTimeout(timer);
            timer = setTimeout(autosaveNow, 300);
        }

        exchangeSelect.addEventListener("change", () => {
            pendingReload = false;
            syncKeysHidden();
            prepareForChange(exchangeSelect.value || "", networkSelect.value || "");
            scheduleAutosave();
        });

        networkSelect.addEventListener("change", () => {
            pendingReload = false;
            syncKeysHidden();
            prepareForChange(exchangeSelect.value || "", networkSelect.value || "");
            scheduleAutosave();
        });

        // ---------------------------------
        // DIAGNOSTICS
        // ---------------------------------
        async function diagnose() {
            if (!btnDiagnose || !statusEl) return;

            const chatId  = getChatIdFallback() || (btnDiagnose.dataset.chatId || "");
            const exchange = exchangeSelect.value || btnDiagnose.dataset.exchange || "";
            const network  = networkSelect.value  || btnDiagnose.dataset.network  || "";

            if (!isDiagnosticsSupported(exchange)) {
                resetDiagnosticsUI({
                    text: "Эта биржа не поддерживает диагностику.",
                    className: "text-warning small"
                });
                if (notSupported) notSupported.style.display = "block";
                return;
            }

            // UI reset + loading
            if (notSupported) notSupported.style.display = "none";
            if (table) table.style.display = "none";
            if (msg) msg.style.display = "none";

            statusEl.textContent = "Диагностика выполняется…";
            statusEl.className = "text-info small";

            setCell("d-apiKeyValid", null);
            setCell("d-secretValid", null);
            setCell("d-signatureValid", null);
            setCell("d-accountReadable", null);
            setCell("d-tradingAllowed", null);
            setCell("d-ipAllowed", null);
            setCell("d-networkOk", null);

            try {
                const url = buildDiagnoseUrl(chatId, exchange, network);
                const d = await postJson(url);

                // сервер может вернуть "not configured"
                const ok = !!d.ok;

                if (table) table.style.display = "table";

                if (msg) {
                    msg.style.display = "block";
                    msg.textContent = d.message || "—";
                    msg.className = ok ? "text-success mt-2" : "text-danger mt-2";
                }

                // Заполняем только если поля есть (чтобы не писать ✔ там, где null)
                setCell("d-apiKeyValid", d.apiKeyValid);
                setCell("d-secretValid", d.secretValid);
                setCell("d-signatureValid", d.signatureValid);
                setCell("d-accountReadable", d.accountReadable);
                setCell("d-tradingAllowed", d.tradingAllowed);
                setCell("d-ipAllowed", d.ipAllowed);
                setCell("d-networkOk", d.networkOk);

                statusEl.textContent = ok ? "Диагностика: OK" : "Диагностика: ошибка";
                statusEl.className = ok ? "text-success small" : "text-danger small";

                // красиво подскажем, если ключей нет
                if (!ok && d && typeof d.message === "string" && d.message.toLowerCase().includes("ключ")) {
                    showAlert("warn", "Диагностика не выполнена", d.message);
                }

            } catch (e) {
                const pretty = prettifyHttpError(e);
                statusEl.textContent = "Ошибка диагностики";
                statusEl.className = "text-danger small";
                showAlert("err", "Ошибка диагностики API", pretty);
            }
        }

        if (btnDiagnose) btnDiagnose.addEventListener("click", diagnose);

        // init sync
        syncKeysHidden();
        replaceUrlWithoutReload(initialExchange, initialNetwork);
    }

    // boot safe
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }

    return { init };
})();

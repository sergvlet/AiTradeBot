"use strict";

window.SettingsTabNetwork = (function () {

    function $(id) { return document.getElementById(id); }

    // =====================================================
    // helpers
    // =====================================================
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

    function escapeHtml(s) {
        return String(s)
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function prettifyError(err) {
        const raw = (err && err.message) ? String(err.message) : "Unknown error";
        const idx = raw.indexOf("{");
        if (idx >= 0) {
            const maybeJson = raw.slice(idx);
            try {
                const obj = JSON.parse(maybeJson);
                const msg = obj.message || obj.error || raw;
                const path = obj.path ? `\nПуть: ${obj.path}` : "";
                const code = obj.code ? `\nКод: ${obj.code}` : "";
                return `${msg}${path}${code}`;
            } catch (_) {}
        }
        return raw;
    }

    function getCtx() {
        return window.StrategySettingsContext || null;
    }

    function getBaseUrl() {
        const ctx = getCtx();
        if (ctx && ctx.baseUrl) return String(ctx.baseUrl);
        return window.location.pathname;
    }

    function getChatId() {
        const ctx = getCtx();
        if (ctx && ctx.chatId) return String(ctx.chatId);
        // fallback
        const el = document.querySelector("[data-chat-id]");
        return el?.dataset?.chatId ? String(el.dataset.chatId) : "";
    }

    function getType() {
        const ctx = getCtx();
        if (ctx && ctx.type) return String(ctx.type);
        const el = document.querySelector("[data-type]");
        return el?.dataset?.type ? String(el.dataset.type) : "";
    }

    function getActiveTabId() {
        // ✅ теперь НЕ используем старый localStorage key
        const activeBtn = document.querySelector(".tab-btn.active");
        const tabId = activeBtn?.dataset?.tab;
        return tabId || "tab-network";
    }

    function buildSettingsUrl(chatId, exchange, network, tabId) {
        const base = getBaseUrl();
        const q = new URLSearchParams();

        if (!isBlank(chatId)) q.set("chatId", String(chatId));
        if (!isBlank(exchange)) q.set("exchange", String(exchange));
        if (!isBlank(network)) q.set("network", String(network));
        if (!isBlank(tabId)) q.set("tab", String(tabId).replace("tab-", "")); // на сервер часто уходит "network/control/.."

        return base + "?" + q.toString();
    }

    function replaceUrlWithoutReload(exchange, network) {
        const url = buildSettingsUrl(getChatId(), exchange, network, getActiveTabId());
        try { history.replaceState(null, "", url); } catch (_) {}
    }

    function buildDiagnoseUrl(chatId, exchange, network) {
        const type = String(getType() || "").trim();
        if (type) {
            return `/strategies/${encodeURIComponent(type)}/config/diagnose` +
                `?chatId=${encodeURIComponent(String(chatId))}` +
                `&exchange=${encodeURIComponent(String(exchange))}` +
                `&network=${encodeURIComponent(String(network))}`;
        }
        // fallback legacy
        return `/strategies/network/diagnose` +
            `?chatId=${encodeURIComponent(String(chatId))}` +
            `&exchange=${encodeURIComponent(String(exchange))}` +
            `&network=${encodeURIComponent(String(network))}`;
    }

    // =====================================================
    // UI alerts (внутри таба)
    // =====================================================
    function ensureAlertHost() {
        let host = $("network-alert");
        if (host) return host;

        const pane = $("tab-network");
        if (pane) {
            host = document.createElement("div");
            host.id = "network-alert";
            host.className = "mb-3";
            // ставим в начало карточки, чтобы не прыгало
            const card = pane.querySelector(".card");
            if (card) card.insertBefore(host, card.firstChild);
            else pane.insertBefore(host, pane.firstChild);
            return host;
        }

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
          <div class="alert alert-${bsType} alert-dismissible fade show" role="alert">
            ${safeTitle ? `<div class="fw-bold mb-1">${escapeHtml(safeTitle)}</div>` : ""}
            ${safeDetails ? `<div class="small" style="white-space: pre-wrap;">${escapeHtml(safeDetails)}</div>` : ""}
            <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Закрыть"></button>
          </div>
        `;
    }

    // =====================================================
    // Diagnostics UI
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
            statusEl.className = (opts && opts.className) ? opts.className : "text-secondary small";
        }
    }

    // =====================================================
    // init
    // =====================================================
    let started = false;

    function init() {
        // ✅ важно: не стартуем, пока page.js не создал контекст
        if (!window.StrategySettingsContext) {
            // page.js создаёт контекст на DOMContentLoaded; сеть инициализируется оттуда
            return;
        }

        if (started) return;
        started = true;

        const api = window.SettingsApi;
        if (!api) {
            console.error("SettingsTabNetwork: SettingsApi не найден (api.js не подключён?)");
            return;
        }

        const networkForm    = $("networkForm");
        const exchangeSelect = $("exchangeSelect");
        const networkSelect  = $("networkSelect");

        if (!networkForm || !exchangeSelect || !networkSelect) return;

        const autosaveEl   = $("network-autosave-status");
        const keysExchange = $("keysExchange");
        const keysNetwork  = $("keysNetwork");

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
        // on load
        // ---------------------------------
        const initialExchange = exchangeSelect.value || "";
        const initialNetwork  = networkSelect.value  || "";

        // сброс диагностики по бирже
        if (!isDiagnosticsSupported(initialExchange)) {
            resetDiagnosticsUI({
                text: "Диагностика недоступна для выбранной биржи.",
                className: "text-warning small"
            });
            if (notSupported) notSupported.style.display = "block";
        } else {
            resetDiagnosticsUI({
                text: "Нажмите «Запустить диагностику».",
                className: "text-secondary small"
            });
            if (notSupported) notSupported.style.display = "none";
        }

        // ---------------------------------
        // AUTOSAVE (exchange/network)
        // ---------------------------------
        let inFlight = false;
        let timer = null;

        let lastExchange = initialExchange;
        let lastNetwork  = initialNetwork;

        function prepareForChange(ex, net) {
            // сбрасываем диагностику, чтобы не оставалось “старое OK”
            if (!isDiagnosticsSupported(ex)) {
                resetDiagnosticsUI({
                    text: "Диагностика недоступна для выбранной биржи.",
                    className: "text-warning small"
                });
                if (notSupported) notSupported.style.display = "block";
            } else {
                resetDiagnosticsUI({
                    text: "Диагностика сброшена. Запустите снова.",
                    className: "text-secondary small"
                });
                if (notSupported) notSupported.style.display = "none";
            }

            if (btnDiagnose) {
                btnDiagnose.dataset.exchange = ex || "";
                btnDiagnose.dataset.network = net || "";
            }
        }

        async function autosaveNow() {
            const ex  = exchangeSelect.value || "";
            const net = networkSelect.value  || "";

            if (ex === lastExchange && net === lastNetwork) {
                syncKeysHidden();
                replaceUrlWithoutReload(ex, net);
                return;
            }

            if (inFlight) return;

            inFlight = true;
            exchangeSelect.disabled = true;
            networkSelect.disabled  = true;

            setAutosave("Сохраняю…", "info");

            try {
                syncKeysHidden();
                prepareForChange(ex, net);

                // ✅ используем SettingsApi.postForm, он сам добавит CSRF
                await api.postForm(networkForm.action, {
                    saveScope: "network",
                    tab: "network",
                    exchange: ex,
                    network: net
                });

                lastExchange = ex;
                lastNetwork  = net;

                replaceUrlWithoutReload(ex, net);

                setAutosave("Сохранено", "ok");
                showAlert("ok", "Биржа/сеть сохранены", `Биржа: ${ex}\nСеть: ${net}`);

                // ✅ тут лучше делать reload, чтобы сервер заново отдал:
                // - connectionOk
                // - accountFees
                // - exchangeSettings.hasBaseKeys()
                // иначе UI будет полуправдой
                const url = buildSettingsUrl(getChatId(), ex, net, "tab-network");
                setTimeout(() => window.location.assign(url), 250);

            } catch (e) {
                const pretty = prettifyError(e);
                setAutosave("Ошибка сохранения", "err");
                showAlert("err", "Ошибка сохранения биржи/сети", pretty);

            } finally {
                inFlight = false;
                exchangeSelect.disabled = false;
                networkSelect.disabled  = false;
                setTimeout(() => setAutosave("", "idle"), 1200);
            }
        }

        function scheduleAutosave() {
            clearTimeout(timer);
            timer = setTimeout(() => autosaveNow().catch(() => {}), 300);
        }

        exchangeSelect.addEventListener("change", () => {
            syncKeysHidden();
            prepareForChange(exchangeSelect.value || "", networkSelect.value || "");
            scheduleAutosave();
        });

        networkSelect.addEventListener("change", () => {
            syncKeysHidden();
            prepareForChange(exchangeSelect.value || "", networkSelect.value || "");
            scheduleAutosave();
        });

        // ---------------------------------
        // DIAGNOSTICS
        // ---------------------------------
        async function diagnose() {
            if (!btnDiagnose || !statusEl) return;

            const chatId  = getChatId() || (btnDiagnose.dataset.chatId || "");
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

                // ✅ diagnose у тебя POST без body
                const d = await api.postJson(url, {});

                const ok = !!d.ok;

                if (table) table.style.display = "table";
                if (msg) {
                    msg.style.display = "block";
                    msg.textContent = d.message || "—";
                    msg.className = ok ? "text-success mt-2" : "text-danger mt-2";
                }

                setCell("d-apiKeyValid", d.apiKeyValid);
                setCell("d-secretValid", d.secretValid);
                setCell("d-signatureValid", d.signatureValid);
                setCell("d-accountReadable", d.accountReadable);
                setCell("d-tradingAllowed", d.tradingAllowed);
                setCell("d-ipAllowed", d.ipAllowed);
                setCell("d-networkOk", d.networkOk);

                statusEl.textContent = ok ? "Диагностика: OK" : "Диагностика: ошибка";
                statusEl.className = ok ? "text-success small" : "text-danger small";

                if (!ok && typeof d.message === "string" && d.message.toLowerCase().includes("ключ")) {
                    showAlert("warn", "Диагностика не прошла", d.message);
                }

            } catch (e) {
                const pretty = prettifyError(e);
                statusEl.textContent = "Ошибка диагностики";
                statusEl.className = "text-danger small";
                showAlert("err", "Ошибка диагностики API", pretty);
            }
        }

        if (btnDiagnose) btnDiagnose.addEventListener("click", () => diagnose().catch(() => {}));

        // init sync
        syncKeysHidden();
        replaceUrlWithoutReload(initialExchange, initialNetwork);
    }

    // ✅ ВАЖНО: больше НЕ автозапускаемся тут.
    // init() вызовет page.js, когда вкладка реально активна.

    return { init };
})();

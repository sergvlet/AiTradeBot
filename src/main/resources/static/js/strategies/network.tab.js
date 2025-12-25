"use strict";

/**
 * NETWORK TAB LOGIC
 * Диагностика API (Binance / Bybit)
 * Вынесено из шаблона, без зависимости от Thymeleaf inline JS
 */
(function () {

    function $(id) {
        return document.getElementById(id);
    }

    const btn = $("btn-diagnose");
    if (!btn) {
        return; // таб не активен
    }

    const status = $("diag-status");
    const notSupported = $("diag-not-supported");
    const table = $("diag-table");
    const msg = $("diag-message");

    const exchangeSelect = $("exchangeSelect");
    const networkSelect = $("networkSelect");

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

    async function diagnose() {

        const exchange = exchangeSelect?.value || "";
        const network = networkSelect?.value || "";
        const chatId = btn.dataset.chatId;

        const exUpper = exchange.toUpperCase();
        const supported = (exUpper === "BINANCE" || exUpper === "BYBIT");

        table.style.display = "none";
        msg.style.display = "none";
        notSupported.style.display = supported ? "none" : "block";

        if (!supported) {
            status.textContent = "Эта биржа не поддерживает диагностику.";
            status.className = "text-warning small";
            return;
        }

        status.textContent = "Диагностика выполняется…";
        status.className = "text-info small";

        setCell("d-apiKeyValid", null);
        setCell("d-secretValid", null);
        setCell("d-signatureValid", null);
        setCell("d-accountReadable", null);
        setCell("d-tradingAllowed", null);
        setCell("d-ipAllowed", null);
        setCell("d-networkOk", null);

        try {
            const url =
                `/strategies/network/diagnose`
                + `?chatId=${encodeURIComponent(chatId)}`
                + `&exchange=${encodeURIComponent(exchange)}`
                + `&network=${encodeURIComponent(network)}`;

            const resp = await fetch(url, {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                }
            });

            if (!resp.ok) {
                status.textContent = "Ошибка диагностики (HTTP " + resp.status + ")";
                status.className = "text-danger small";
                return;
            }

            const d = await resp.json();

            table.style.display = "table";
            msg.style.display = "block";

            setCell("d-apiKeyValid", d.apiKeyValid);
            setCell("d-secretValid", d.secretValid);
            setCell("d-signatureValid", d.signatureValid);
            setCell("d-accountReadable", d.accountReadable);
            setCell("d-tradingAllowed", d.tradingAllowed);
            setCell("d-ipAllowed", d.ipAllowed);
            setCell("d-networkOk", d.networkOk);

            msg.textContent = d.message || "—";
            msg.className = d.ok ? "text-success mt-2" : "text-danger mt-2";

            status.textContent = d.ok
                ? "Диагностика: OK"
                : "Диагностика: ошибка";

            status.className = d.ok
                ? "text-success small"
                : "text-danger small";

        } catch (e) {
            status.textContent = "Ошибка диагностики: " + (e?.message || "unknown");
            status.className = "text-danger small";
        }
    }

    btn.addEventListener("click", diagnose);

})();

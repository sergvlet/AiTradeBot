"use strict";

console.log("🔧 diagnostics.js loaded");

/**
 * Рендер HTML диагностики по JSON-ответу.
 */
function renderDiagnostics(status) {
    const box = document.getElementById("diagnostics-box");
    if (!box) return;

    if (!status) {
        box.innerHTML = `
            <div class="text-secondary">Нет данных диагностики.</div>
        `;
        return;
    }

    const html = `
        <table class="table table-dark table-bordered align-middle text-center mt-2" style="max-width:600px;">
            <tbody>
                <tr>
                    <td>API Key валиден</td>
                    <td class="${status.apiKeyValid ? 'text-success' : 'text-danger'}">
                        ${status.apiKeyValid ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>Secret валиден</td>
                    <td class="${status.secretValid ? 'text-success' : 'text-danger'}">
                        ${status.secretValid ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>Подпись корректна</td>
                    <td class="${status.signatureValid ? 'text-success' : 'text-danger'}">
                        ${status.signatureValid ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>Аккаунт читается</td>
                    <td class="${status.accountReadable ? 'text-success' : 'text-danger'}">
                        ${status.accountReadable ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>Разрешена торговля</td>
                    <td class="${status.tradingAllowed ? 'text-success' : 'text-danger'}">
                        ${status.tradingAllowed ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>IP разрешён</td>
                    <td class="${status.ipAllowed ? 'text-success' : 'text-danger'}">
                        ${status.ipAllowed ? '✔' : '✖'}
                    </td>
                </tr>

                <tr>
                    <td>Сеть корректна</td>
                    <td class="${status.networkOk ? 'text-success' : 'text-danger'}">
                        ${status.networkOk ? '✔' : '✖'}
                    </td>
                </tr>
            </tbody>
        </table>

        <div class="mt-2 text-info">${status.message ?? ''}</div>

        ${
        status.extra
            ? `<pre class="mt-2 small bg-dark p-2 rounded">${JSON.stringify(status.extra, null, 2)}</pre>`
            : ''
    }
    `;

    box.innerHTML = html;
}


/**
 * Запрос диагностики с сервера
 */
async function refreshDiagnostics() {
    if (typeof chatId === "undefined") {
        console.warn("⚠ diagnostics.js: chatId не определён");
        return;
    }

    const exchange = selectedExchange ?? "BINANCE";
    const network  = selectedNetwork ?? "MAINNET";

    const url = `/api/exchange/diagnostics/binance?chatId=${chatId}&exchange=${exchange}&network=${network}`;

    console.log("🔍 GET:", url);

    try {
        const resp = await fetch(url);

        if (!resp.ok) {
            renderDiagnostics({
                ok: false,
                message: `HTTP ${resp.status}`
            });
            return;
        }

        const data = await resp.json();

        console.log("🔧 Diagnostics:", data);

        renderDiagnostics(data);

    } catch (err) {
        console.error("Ошибка диагностики:", err);

        renderDiagnostics({
            ok: false,
            message: "Ошибка соединения"
        });
    }
}


/* -------- Привязка кнопки -------- */
document.addEventListener("DOMContentLoaded", () => {
    const btn = document.getElementById("btn-refresh-diagnostics");
    if (btn) {
        btn.addEventListener("click", refreshDiagnostics);
    }
});

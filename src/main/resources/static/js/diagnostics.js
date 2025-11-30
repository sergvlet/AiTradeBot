"use strict";

console.log("🧪 diagnostics.js loaded");

/**
 * Перерисовать HTML блока диагностики по данным,
 * которые вернул /api/exchange/diagnostics/binance.
 */
function renderDiagnostics(status) {
    const box = document.getElementById("diagnostics-box");
    if (!box) return;

    if (!status) {
        box.innerHTML = '<div class="text-secondary">Нет данных диагностики.</div>';
        return;
    }

    const html = `
        <table class="table table-dark table-bordered align-middle text-center mt-2" style="max-width:600px;">
          <tbody>
            <tr>
              <td>Ключ валиден</td>
              <td class="${status.keyValid ? 'text-success' : 'text-danger'}">
                ${status.keyValid ? '✔' : '✖'}
              </td>
            </tr>
            <tr>
              <td>Секрет валиден</td>
              <td class="${status.secretValid ? 'text-success' : 'text-danger'}">
                ${status.secretValid ? '✔' : '✖'}
              </td>
            </tr>
            <tr>
              <td>Чтение аккаунта</td>
              <td class="${status.readingEnabled ? 'text-success' : 'text-danger'}">
                ${status.readingEnabled ? '✔' : '✖'}
              </td>
            </tr>
            <tr>
              <td>Разрешена торговля</td>
              <td class="${status.tradingEnabled ? 'text-success' : 'text-danger'}">
                ${status.tradingEnabled ? '✔' : '✖'}
              </td>
            </tr>
            <tr>
              <td>IP разрешён</td>
              <td class="${status.ipAllowed ? 'text-success' : 'text-danger'}">
                ${status.ipAllowed ? '✔' : '✖'}
              </td>
            </tr>
            <tr>
              <td>Сеть совпадает</td>
              <td class="${status.networkMismatch ? 'text-danger' : 'text-success'}">
                ${status.networkMismatch ? '✖ Неверная сеть' : '✔ OK'}
              </td>
            </tr>
          </tbody>
        </table>
        <div class="mt-2 small">${status.message ?? ''}</div>
        ${
        status.reasons && status.reasons.length
            ? `<ul class="small text-muted mt-1">${status.reasons.map(r => `<li>${r}</li>`).join("")}</ul>`
            : ''
    }
    `;

    box.innerHTML = html;
}

async function refreshDiagnostics() {
    if (typeof chatId === "undefined") {
        console.warn("chatId is not defined in JS (Thymeleaf не подставил значение).");
        return;
    }

    const params = new URLSearchParams({
        chatId: String(chatId),
        exchange: selectedExchange,
        network: selectedNetwork
    });

    const url = `/api/exchange/diagnostics/binance?${params.toString()}`;

    console.log("🔍 Calling diagnostics:", url);

    try {
        const resp = await fetch(url, {
            method: "GET",
            headers: {
                "Accept": "application/json"
            }
        });

        if (!resp.ok) {
            console.error("Diagnostics HTTP error:", resp.status);
            renderDiagnostics({
                ok: false,
                keyValid: false,
                secretValid: false,
                readingEnabled: false,
                tradingEnabled: false,
                ipAllowed: false,
                networkMismatch: false,
                message: `HTTP ошибка: ${resp.status}`,
                reasons: []
            });
            return;
        }

        const data = await resp.json();
        console.log("🔍 Diagnostics result:", data);
        renderDiagnostics(data);

    } catch (e) {
        console.error("Diagnostics fetch error:", e);
        renderDiagnostics({
            ok: false,
            keyValid: false,
            secretValid: false,
            readingEnabled: false,
            tradingEnabled: false,
            ipAllowed: false,
            networkMismatch: false,
            message: `Ошибка запроса: ${e}`,
            reasons: []
        });
    }
}

// Привязка кнопки
document.addEventListener("DOMContentLoaded", () => {
    const btn = document.getElementById("btn-refresh-diagnostics");
    if (btn) {
        btn.addEventListener("click", refreshDiagnostics);
    }
});

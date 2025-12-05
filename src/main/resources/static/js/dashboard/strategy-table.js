"use strict";

console.log("📄 strategy-table.js loaded");

// =============================================================
// ГЛОБАЛЬНОЕ СОСТОЯНИЕ
// =============================================================
let tradeRows = [];
let lastTradeId = 0;

// =============================================================
// ОСНОВНОЙ ИНИЦИАЛИЗАТОР
// =============================================================
function init() {
    console.log("📘 AiStrategyTable.init()");

    // таблица может быть скрыта на некоторых страницах
    const table = document.querySelector("#trades-table");
    if (!table) {
        console.log("ℹ strategy-table: таблица отсутствует на странице");
        return;
    }

    // сохраняем начальные данные
    loadInitialRows(table);

    // будущая возможность → сортировка и фильтры
    initSorting(table);
    initFiltering(table);

    console.log("✅ strategy-table initialized");
}

// =============================================================
// СЧИТЫВАНИЕ СТАРТОВЫХ СТРОК (из Thymeleaf)
// =============================================================
function loadInitialRows(table) {
    const rows = Array.from(table.querySelectorAll("tbody tr"));
    tradeRows = rows.map((row, index) => ({
        id: index + 1,
        element: row
    }));
}

// =============================================================
// СОРТИРОВКА ТАБЛИЦЫ (пока заглушка, без реализации)
// =============================================================
function initSorting(table) {
    // если позже захочешь: клик по заголовку сортирует строки
    // пример:
    //
    // const headers = table.querySelectorAll("thead th");
    // headers.forEach((th, idx) => {
    //     th.addEventListener("click", () => sortByColumn(idx));
    // });
}

// =============================================================
// ФИЛЬТРАЦИЯ ТАБЛИЦЫ (пока заглушка)
// =============================================================
function initFiltering(table) {
    // можно сделать панель фильтров:
    // BUY/SELL, диапазон дат, диапазон цены и т.п.
}

// =============================================================
// LIVE-UPDATE (опционально)
// Может вызываться из WebSocket или polling
// =============================================================
function addTradeRow(trade) {
    /**
     * trade = {
     *   time:   1710000000000,
     *   side:   "BUY" / "SELL",
     *   price:  50250.12,
     *   qty:    0.004,
     *   pnl:    -0.35
     * }
     */

    const table = document.querySelector("#trades-table tbody");
    if (!table) return;

    const row = document.createElement("tr");

    // форматирование времени
    const dt = new Date(trade.time).toLocaleString();

    // цветовое оформление BUY/SELL
    const sideHtml =
        trade.side === "BUY"
            ? `<span class="badge-trade-buy small px-2 py-1"><i class="bi bi-arrow-up-right me-1"></i>BUY</span>`
            : `<span class="badge-trade-sell small px-2 py-1"><i class="bi bi-arrow-down-right me-1"></i>SELL</span>`;

    row.innerHTML = `
        <td class="small">${dt}</td>
        <td>${sideHtml}</td>
        <td class="small">${trade.price.toFixed(4)}</td>
        <td class="small">${trade.qty.toFixed(4)}</td>
        <td class="small ${trade.pnl >= 0 ? "text-success" : "text-danger"}">
            ${trade.pnl.toFixed(4)}
        </td>
    `;

    table.prepend(row);
    lastTradeId++;
}

// =============================================================
// ПУБЛИЧНЫЙ API
// =============================================================
window.AiStrategyTable = {
    init,
    addTradeRow
};

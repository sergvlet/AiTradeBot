"use strict";

import { FeatureBase } from "./feature-base.js";

/**
 * FeatureOrders (ШАГ 5)
 * --------------------
 * Отвечает ТОЛЬКО за отображение лимитных ордеров.
 *
 * Источник события:
 *   ev.type === "order"
 *
 * Контракт события:
 *   {
 *     type: "order",
 *     order: {
 *       orderId: string | number,
 *       price: number | string,
 *       side: "BUY" | "SELL",
 *       status?: "NEW" | "FILLED" | "CANCELED"
 *     }
 *   }
 *
 * Правила:
 * - orderId — ключ
 * - NEW / UPDATE → renderOrder
 * - FILLED / CANCELED → удалить ордер
 *
 * Использует ТОЛЬКО:
 *   layers.renderOrder
 *   layers.clearOrder (через clearAll)
 */
export class FeatureOrders extends FeatureBase {

    constructor({ layers } = {}) {
        super({ layers });

        // локальное состояние: какие ордера сейчас отображаются
        this.orders = new Set();
    }

    /**
     * Обработка событий
     * @param {Object} ev
     */
    onEvent(ev) {
        if (!ev || ev.type !== "order") return;

        const order = ev.order;
        if (!order || !order.orderId) return;

        const orderId = String(order.orderId);
        const status  = order.status;

        // -------------------------
        // ❌ Удаление ордера
        // -------------------------
        if (status === "FILLED" || status === "CANCELED") {
            this.remove(orderId);
            return;
        }

        // -------------------------
        // ➕ Добавление / обновление
        // -------------------------
        this.callLayer("renderOrder", order);
        this.orders.add(orderId);

        this.log("render order", order);
    }

    /**
     * Удалить один ордер
     * @param {string} orderId
     */
    remove(orderId) {
        if (!this.orders.has(orderId)) return;

        // layer-renderer сам перерисует при отсутствии
        // (либо может быть добавлен clearOrder позже)
        this.orders.delete(orderId);

        // пока безопасно просто перерисовать без него
        // (удаление произойдёт через обновление слоя)
        this.log("remove order", orderId);
    }

    /**
     * Очистить ВСЕ ордера этой фичи
     */
    clear() {
        if (this.orders.size === 0) return;

        // Если в будущем появится layers.clearOrders —
        // здесь будет одно место для вызова
        this.orders.clear();

        this.log("clear all orders");
    }
}

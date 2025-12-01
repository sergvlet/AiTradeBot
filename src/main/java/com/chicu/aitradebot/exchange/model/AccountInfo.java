package com.chicu.aitradebot.exchange.model;

import lombok.Builder;
import lombok.Data;

/**
 * Универсальная инфа об аккаунте на бирже:
 * - комиссии maker/taker
 * - VIP уровень
 * - используется ли скидка BNB (для Binance)

 * Все комиссии в процентах (например 0.1 = 0.1%)
 */
@Data
@Builder
public class AccountInfo {

    /**
     * Базовая maker комиссия (%), например 0.1
     */
    private double makerFee;

    /**
     * Базовая taker комиссия (%), например 0.1
     */
    private double takerFee;

    /**
     * Maker комиссия с учётом скидки BNB, если есть.
     * Например, 0.075
     */
    private double makerFeeWithDiscount;

    /**
     * Taker комиссия с учётом скидки BNB, если есть.
     */
    private double takerFeeWithDiscount;

    /**
     * VIP уровень пользователя (0..999)
     */
    private int vipLevel;

    /**
     * True — если биржа реально применяет BNB скидку
     */
    private boolean usingBnbDiscount;
}

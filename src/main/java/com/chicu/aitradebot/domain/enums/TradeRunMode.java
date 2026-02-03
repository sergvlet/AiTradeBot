package com.chicu.aitradebot.domain.enums;

/**
 * Режим исполнения сделок.
 * COLLECT  — сбор данных без реальных ордеров
 * PAPER    — бумажная торговля (если симулятор подключен)
 * LIVE     — реальные ордера
 */
public enum TradeRunMode {
    COLLECT,
    PAPER,
    LIVE
}

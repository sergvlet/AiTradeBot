package com.chicu.aitradebot.market.model;

/**
 * Где именно действует ограничение биржи.
 *
 * Используется:
 * - ExchangeAIGuard (валидация / авто-округление)
 * - UI (бейджи: SYMBOL / ACCOUNT / UNKNOWN)
 * - логика AI-aware поведения
 */
public enum ExchangeLimitScope {

    /**
     * Ограничение задано на уровне торговой пары
     * (пример: Binance tickSize, stepSize, minNotional)
     */
    SYMBOL,

    /**
     * Ограничение действует на уровне аккаунта или рынка,
     * а не конкретного символа (пример: Bybit)
     */
    ACCOUNT,

    /**
     * Биржа не предоставляет это ограничение явно,
     * либо оно не удалось определить
     */
    UNKNOWN;

    // =====================================================
    // HELPERS (UI / AI)
    // =====================================================

    /** Строгое ли ограничение (можно ли жёстко валидировать) */
    public boolean isStrict() {
        return this == SYMBOL;
    }

    /** Можно ли полагаться на значение без риска */
    public boolean isReliable() {
        return this == SYMBOL || this == ACCOUNT;
    }

    /** Нужно ли AI вести себя осторожно */
    public boolean requiresCaution() {
        return this == ACCOUNT || this == UNKNOWN;
    }
}

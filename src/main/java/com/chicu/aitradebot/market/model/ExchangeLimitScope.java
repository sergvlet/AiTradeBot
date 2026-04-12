package com.chicu.aitradebot.market.model;

import java.util.Locale;

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
    // PARSE / NULL-SAFE
    // =====================================================

    /**
     * Null-safe парсер (чтобы везде не писать try/catch).
     */
    public static ExchangeLimitScope of(String v) {
        if (v == null || v.isBlank()) return UNKNOWN;
        try {
            return ExchangeLimitScope.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    /**
     * Null-safe: если прилетел null, считаем UNKNOWN.
     */
    public static ExchangeLimitScope safe(ExchangeLimitScope v) {
        return v != null ? v : UNKNOWN;
    }

    // =====================================================
    // POLICY (AI / GUARD)
    // =====================================================

    /**
     * Строгое ли ограничение (можно ли жёстко валидировать).
     *
     * Важно: ACCOUNT тоже может быть строгим (Bybit реально отклонит),
     * просто оно менее "надёжно" / может отличаться по аккаунтам/режимам.
     */
    public boolean isStrict() {
        return this == SYMBOL || this == ACCOUNT;
    }

    /**
     * Можно ли полагаться на значение без риска.
     * SYMBOL — максимально надёжно.
     * ACCOUNT — обычно надёжно, но иногда зависит от типа аккаунта/рынка.
     */
    public boolean isReliable() {
        return this == SYMBOL;
    }

    /**
     * Нужно ли AI вести себя осторожно (например, не пытаться "поднимать qty"
     * без подтверждения баланса/лимитов).
     */
    public boolean requiresCaution() {
        return this != SYMBOL;
    }

    // =====================================================
    // UI HELPERS
    // =====================================================

    /**
     * Короткая метка для UI.
     */
    public String label() {
        return switch (this) {
            case SYMBOL -> "SYMBOL";
            case ACCOUNT -> "ACCOUNT";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    /**
     * Рекомендуемый класс бейджа (Bootstrap).
     * Можно использовать и в Thymeleaf, и в JS.
     */
    public String badgeClass() {
        return switch (this) {
            case SYMBOL -> "bg-success";
            case ACCOUNT -> "bg-warning text-dark";
            case UNKNOWN -> "bg-secondary";
        };
    }
}

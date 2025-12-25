package com.chicu.aitradebot.strategy.core.context;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;

import java.math.BigDecimal;

/**
 * 🧠 StrategyContext (v4)
 *
 * ЕДИНЫЙ контракт передачи данных в стратегию.
 *
 * ❌ Стратегия НЕ знает:
 *   - про ExchangeClient
 *   - про БД
 *   - про ордера
 *
 * ✅ Стратегия читает ТОЛЬКО отсюда.
 */
public interface StrategyContext {

    // =================================================
    // IDENTIFICATION
    // =================================================

    Long getChatId();

    String getSymbol();

    // =================================================
    // EXCHANGE CONTEXT
    // =================================================

    /**
     * Имя биржи (BINANCE / BYBIT / OKX)
     */
    String getExchange();

    NetworkType getNetworkType();

    // =================================================
    // MARKET DATA
    // =================================================

    /**
     * Текущая цена
     */
    BigDecimal getPrice();

    /**
     * Закрытия свечей (старые → новые)
     */
    double[] getCloses();

    // =================================================
    // SETTINGS SNAPSHOT
    // =================================================

    /**
     * Сырые настройки стратегии (snapshot).
     * Тип зависит от StrategyType.
     */
    Object getSettings();

    /**
     * Алиас для читаемости — явно подчёркивает,
     * что стратегия работает со snapshot'ом.
     */
    default Object getSettingsSnapshot() {
        return getSettings();
    }

    /**
     * Безопасное получение типизированных настроек.
     */
    <T> T getTypedSettings(Class<T> clazz);

    /**
     * 🔁 LIVE-обновление snapshot'а настроек.
     *
     * ⚠️ По умолчанию — no-op.
     * Реальная логика будет в runtime-контексте.
     */
    default void updateSettingsSnapshot(Object newSnapshot) {
        // default no-op (не ломаем старые реализации)
    }

    // =================================================
    // RUNTIME STATE
    // =================================================

    StrategyRuntimeState getState();

    // =================================================
    // STRATEGY TYPE (v4)
    // =================================================

    /**
     * Тип стратегии (SCALPING / FIBONACCI / SMART_FUSION / ...)
     *
     * ⚠️ Реально должен устанавливаться builder'ом.
     * Этот default — fallback для старых путей.
     */
    default StrategyType getStrategyType() {

        // 1️⃣ Если settings — универсальные StrategySettings
        Object raw = getSettings();
        if (raw instanceof StrategySettings s) {
            return s.getType();
        }

        // 2️⃣ Пытаемся через typed settings
        try {
            StrategySettings s = getTypedSettings(StrategySettings.class);
            if (s != null) {
                return s.getType();
            }
        } catch (Exception ignore) {
            // кастомные settings — допустимо
        }

        // 3️⃣ Тип не определён (движок обязан это обработать)
        return null;
    }
}

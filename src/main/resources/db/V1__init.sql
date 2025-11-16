-- ================================
-- V1__init.sql
-- Инициализация структуры базы данных ai-trade-bot
-- ================================

-- === Таблица пользователей ===
CREATE TABLE IF NOT EXISTS user_profiles (
                                             id          BIGSERIAL PRIMARY KEY,
                                             chat_id     BIGINT UNIQUE NOT NULL,
                                             username    VARCHAR(255),
                                             active      BOOLEAN DEFAULT TRUE,
                                             created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- === Таблица настроек стратегий ===
CREATE TABLE IF NOT EXISTS strategy_settings (
                                                 id                 BIGSERIAL PRIMARY KEY,
                                                 type               VARCHAR(100) NOT NULL,
                                                 symbol             VARCHAR(50) NOT NULL,
                                                 timeframe          VARCHAR(20) NOT NULL,
                                                 cached_candles_limit INTEGER DEFAULT 500,

    -- Точные поля в NUMERIC (BigDecimal)
                                                 take_profit_pct    NUMERIC(10,6) DEFAULT 1.000000 NOT NULL,
                                                 stop_loss_pct      NUMERIC(10,6) DEFAULT 1.000000 NOT NULL,
                                                 commission_pct     NUMERIC(10,6) DEFAULT 0.200000 NOT NULL,

                                                 leverage           INTEGER DEFAULT 1,
                                                 version            INTEGER DEFAULT 1,
                                                 active             BOOLEAN DEFAULT TRUE,
                                                 created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                                 updated_at         TIMESTAMP
);

-- === Таблица связей "Пользователь ↔ Стратегия" ===
CREATE TABLE IF NOT EXISTS user_strategies (
                                               id                BIGSERIAL PRIMARY KEY,
                                               user_id           BIGINT NOT NULL REFERENCES user_profiles(id) ON DELETE CASCADE,
                                               strategy_id       BIGINT NOT NULL REFERENCES strategy_settings(id) ON DELETE CASCADE,
                                               active            BOOLEAN DEFAULT FALSE,
                                               started_at        TIMESTAMP,
                                               stopped_at        TIMESTAMP,
                                               total_trades      BIGINT DEFAULT 0,

                                               total_profit_pct  NUMERIC(12,6) DEFAULT 0.000000 NOT NULL,
                                               ml_confidence     NUMERIC(5,4)  DEFAULT 0.0000 NOT NULL
);

-- === Индексы для быстрого доступа ===
CREATE INDEX IF NOT EXISTS idx_user_profiles_chat_id ON user_profiles(chat_id);
CREATE INDEX IF NOT EXISTS idx_strategy_settings_type ON strategy_settings(type);
CREATE INDEX IF NOT EXISTS idx_user_strategies_user_id ON user_strategies(user_id);
CREATE INDEX IF NOT EXISTS idx_user_strategies_strategy_id ON user_strategies(strategy_id);

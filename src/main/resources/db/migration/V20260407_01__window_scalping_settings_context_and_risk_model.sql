ALTER TABLE IF EXISTS window_scalping_strategy_settings
    ADD COLUMN IF NOT EXISTS exchange_name VARCHAR(32),
    ADD COLUMN IF NOT EXISTS network_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS symbol VARCHAR(40),
    ADD COLUMN IF NOT EXISTS timeframe VARCHAR(16),
    ADD COLUMN IF NOT EXISTS auto_tp_sl_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS auto_sl_from_range_factor NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_tp_from_range_factor NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_min_risk_reward NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_sl_min_pct NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_sl_max_pct NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_tp_min_pct NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_tp_max_pct NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_tp_ml_boost_factor NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS auto_tp_weak_signal_factor NUMERIC(19,8),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS version INTEGER;

UPDATE window_scalping_strategy_settings
SET exchange_name = COALESCE(NULLIF(TRIM(exchange_name), ''), 'BINANCE'),
    network_type = COALESCE(NULLIF(TRIM(network_type), ''), 'TESTNET'),
    symbol = COALESCE(NULLIF(TRIM(symbol), ''), 'BTCUSDT'),
    timeframe = COALESCE(NULLIF(TRIM(timeframe), ''), '1m'),
    auto_tp_sl_enabled = COALESCE(auto_tp_sl_enabled, TRUE),
    auto_sl_from_range_factor = COALESCE(auto_sl_from_range_factor, 1.80000000),
    auto_tp_from_range_factor = COALESCE(auto_tp_from_range_factor, 5.50000000),
    auto_min_risk_reward = COALESCE(auto_min_risk_reward, 2.40000000),
    auto_sl_min_pct = COALESCE(auto_sl_min_pct, 0.04000000),
    auto_sl_max_pct = COALESCE(auto_sl_max_pct, 0.18000000),
    auto_tp_min_pct = COALESCE(auto_tp_min_pct, 0.10000000),
    auto_tp_max_pct = COALESCE(auto_tp_max_pct, 0.80000000),
    auto_tp_ml_boost_factor = COALESCE(auto_tp_ml_boost_factor, 1.15000000),
    auto_tp_weak_signal_factor = COALESCE(auto_tp_weak_signal_factor, 0.90000000),
    created_at = COALESCE(created_at, NOW()),
    updated_at = COALESCE(updated_at, NOW())
WHERE TRUE;

ALTER TABLE IF EXISTS window_scalping_strategy_settings
    ALTER COLUMN exchange_name SET DEFAULT 'BINANCE',
    ALTER COLUMN network_type SET DEFAULT 'TESTNET',
    ALTER COLUMN symbol SET DEFAULT 'BTCUSDT',
    ALTER COLUMN timeframe SET DEFAULT '1m',
    ALTER COLUMN auto_tp_sl_enabled SET DEFAULT TRUE,
    ALTER COLUMN auto_sl_from_range_factor SET DEFAULT 1.80000000,
    ALTER COLUMN auto_tp_from_range_factor SET DEFAULT 5.50000000,
    ALTER COLUMN auto_min_risk_reward SET DEFAULT 2.40000000,
    ALTER COLUMN auto_sl_min_pct SET DEFAULT 0.04000000,
    ALTER COLUMN auto_sl_max_pct SET DEFAULT 0.18000000,
    ALTER COLUMN auto_tp_min_pct SET DEFAULT 0.10000000,
    ALTER COLUMN auto_tp_max_pct SET DEFAULT 0.80000000,
    ALTER COLUMN auto_tp_ml_boost_factor SET DEFAULT 1.15000000,
    ALTER COLUMN auto_tp_weak_signal_factor SET DEFAULT 0.90000000;

ALTER TABLE IF EXISTS window_scalping_strategy_settings
    ALTER COLUMN exchange_name SET NOT NULL,
    ALTER COLUMN network_type SET NOT NULL,
    ALTER COLUMN symbol SET NOT NULL,
    ALTER COLUMN timeframe SET NOT NULL,
    ALTER COLUMN auto_tp_sl_enabled SET NOT NULL,
    ALTER COLUMN auto_sl_from_range_factor SET NOT NULL,
    ALTER COLUMN auto_tp_from_range_factor SET NOT NULL,
    ALTER COLUMN auto_min_risk_reward SET NOT NULL,
    ALTER COLUMN auto_sl_min_pct SET NOT NULL,
    ALTER COLUMN auto_sl_max_pct SET NOT NULL,
    ALTER COLUMN auto_tp_min_pct SET NOT NULL,
    ALTER COLUMN auto_tp_max_pct SET NOT NULL,
    ALTER COLUMN auto_tp_ml_boost_factor SET NOT NULL,
    ALTER COLUMN auto_tp_weak_signal_factor SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_window_scalping_chat
    ON window_scalping_strategy_settings (chat_id);

CREATE INDEX IF NOT EXISTS ix_window_scalping_ctx
    ON window_scalping_strategy_settings (chat_id, exchange_name, network_type, symbol, timeframe);

DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'uk_window_scalping_settings_context'
        ) THEN
            ALTER TABLE window_scalping_strategy_settings
                ADD CONSTRAINT uk_window_scalping_settings_context
                    UNIQUE (chat_id, exchange_name, network_type, symbol, timeframe);
        END IF;
    END $$;

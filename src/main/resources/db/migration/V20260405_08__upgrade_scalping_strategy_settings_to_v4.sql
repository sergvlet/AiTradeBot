-- Перевод таблицы scalping_strategy_settings на новую V4-схему
-- Подходит для проекта со старой таблицей, где были только:
-- window_size, price_change_threshold, spread_threshold, created_at, updated_at

ALTER TABLE scalping_strategy_settings
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS min_impulse_pct DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS ema_diff_threshold DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS volume_ratio DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS spread_limit_pct DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS atr_pct_range DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS rsi_filter DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS risk_reward_min DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS order_volume DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS take_profit_pct DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS stop_loss_pct DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS symbol VARCHAR(32),
    ADD COLUMN IF NOT EXISTS timeframe VARCHAR(16),
    ADD COLUMN IF NOT EXISTS cached_candles_limit INTEGER,
    ADD COLUMN IF NOT EXISTS active BOOLEAN,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;

-- Перенос старых значений в новые поля
UPDATE scalping_strategy_settings
SET min_impulse_pct = price_change_threshold
WHERE min_impulse_pct IS NULL
  AND price_change_threshold IS NOT NULL;

UPDATE scalping_strategy_settings
SET spread_limit_pct = spread_threshold
WHERE spread_limit_pct IS NULL
  AND spread_threshold IS NOT NULL;

-- Заполнение дефолтов для новой V4-модели
UPDATE scalping_strategy_settings
SET version = COALESCE(version, 0),
    min_impulse_pct = COALESCE(min_impulse_pct, 0.35),
    ema_diff_threshold = COALESCE(ema_diff_threshold, 0.08),
    volume_ratio = COALESCE(volume_ratio, 1.15),
    spread_limit_pct = COALESCE(spread_limit_pct, 0.12),
    atr_pct_range = COALESCE(atr_pct_range, 0.60),
    rsi_filter = COALESCE(rsi_filter, 52.0),
    risk_reward_min = COALESCE(risk_reward_min, 1.40),
    order_volume = COALESCE(order_volume, 20.0),
    take_profit_pct = COALESCE(take_profit_pct, 0.70),
    stop_loss_pct = COALESCE(stop_loss_pct, 0.35),
    symbol = COALESCE(NULLIF(TRIM(symbol), ''), 'BTCUSDT'),
    timeframe = COALESCE(NULLIF(TRIM(timeframe), ''), '1m'),
    cached_candles_limit = COALESCE(cached_candles_limit, 400),
    active = COALESCE(active, FALSE),
    created_at = COALESCE(created_at, NOW()),
    updated_at = COALESCE(updated_at, NOW());

-- Делаем новые поля обязательными, как требует сущность
ALTER TABLE scalping_strategy_settings
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN window_size SET NOT NULL,
    ALTER COLUMN min_impulse_pct SET NOT NULL,
    ALTER COLUMN ema_diff_threshold SET NOT NULL,
    ALTER COLUMN volume_ratio SET NOT NULL,
    ALTER COLUMN spread_limit_pct SET NOT NULL,
    ALTER COLUMN atr_pct_range SET NOT NULL,
    ALTER COLUMN rsi_filter SET NOT NULL,
    ALTER COLUMN risk_reward_min SET NOT NULL,
    ALTER COLUMN order_volume SET NOT NULL,
    ALTER COLUMN take_profit_pct SET NOT NULL,
    ALTER COLUMN stop_loss_pct SET NOT NULL,
    ALTER COLUMN symbol SET NOT NULL,
    ALTER COLUMN timeframe SET NOT NULL,
    ALTER COLUMN cached_candles_limit SET NOT NULL,
    ALTER COLUMN active SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE scalping_strategy_settings
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN min_impulse_pct SET DEFAULT 0.35,
    ALTER COLUMN ema_diff_threshold SET DEFAULT 0.08,
    ALTER COLUMN volume_ratio SET DEFAULT 1.15,
    ALTER COLUMN spread_limit_pct SET DEFAULT 0.12,
    ALTER COLUMN atr_pct_range SET DEFAULT 0.60,
    ALTER COLUMN rsi_filter SET DEFAULT 52.0,
    ALTER COLUMN risk_reward_min SET DEFAULT 1.40,
    ALTER COLUMN order_volume SET DEFAULT 20.0,
    ALTER COLUMN take_profit_pct SET DEFAULT 0.70,
    ALTER COLUMN stop_loss_pct SET DEFAULT 0.35,
    ALTER COLUMN symbol SET DEFAULT 'BTCUSDT',
    ALTER COLUMN timeframe SET DEFAULT '1m',
    ALTER COLUMN cached_candles_limit SET DEFAULT 400,
    ALTER COLUMN active SET DEFAULT FALSE,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET DEFAULT NOW();

-- Индекс по chat_id
CREATE INDEX IF NOT EXISTS ix_scalping_settings_chat
    ON scalping_strategy_settings (chat_id);

-- Если хочешь полностью убрать старые колонки после перехода, раскомментируй:
-- ALTER TABLE scalping_strategy_settings DROP COLUMN IF EXISTS price_change_threshold;
-- ALTER TABLE scalping_strategy_settings DROP COLUMN IF EXISTS spread_threshold;

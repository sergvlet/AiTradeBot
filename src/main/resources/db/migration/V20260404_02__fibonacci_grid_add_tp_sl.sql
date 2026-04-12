ALTER TABLE fibonacci_grid_strategy_settings
    ADD COLUMN IF NOT EXISTS take_profit_pct NUMERIC(38,18),
    ADD COLUMN IF NOT EXISTS stop_loss_pct NUMERIC(38,18);

UPDATE fibonacci_grid_strategy_settings
SET take_profit_pct = COALESCE(take_profit_pct, 0.80),
    stop_loss_pct   = COALESCE(stop_loss_pct, 1.20);

ALTER TABLE fibonacci_grid_strategy_settings
    ALTER COLUMN take_profit_pct SET NOT NULL,
    ALTER COLUMN stop_loss_pct SET NOT NULL;
